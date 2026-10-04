package tf.monochrome.desktop.audio.usb

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.desktop.audio.sink.LibusbUacSink
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.player.engine.AudioOutputController

/**
 * Bridges the user-facing "Exclusive USB DAC" toggle to the actual
 * libusb driver lifecycle, and exposes an honest status flow the UI
 * can render. Without this, the Settings switch is just a persisted
 * boolean — there's no way for the user to tell whether their DAC is
 * actually being claimed.
 *
 * While the toggle is on and a DAC can be claimed, the engine's output is
 * the DAC: the controller installs a [LibusbUacSink] in front of the
 * listener's own output choice ([AudioOutputController.overrideSink]), and
 * takes it away again when the toggle goes off or the DAC goes, which puts
 * the listener's WASAPI or Java Sound choice back. A track the DAC cannot
 * take plays on that choice meanwhile (the sink refuses it; [Status.Error]
 * and [lastStartError] say why).
 *
 * Desktop: what replaced Android's plumbing.
 *  - **No permission dialog.** Android asked the user for the device; on
 *    Windows the gate is the driver binding: libusb can claim a DAC only once
 *    its audio interfaces are bound to WinUSB (or libusbK), which Zadig does.
 *    [Status.AwaitingPermission] therefore means "a DAC is attached but its
 *    interfaces cannot be claimed", and [lastOpenError] says what to do. The
 *    check is a real claim, tried and released ([LibusbUacNative.probeClaim]):
 *    a DAC with a HID interface opens fine on Windows and still refuses its
 *    audio interfaces, so a successful open alone proves nothing.
 *  - **No attach/detach broadcasts.** libusb has no hotplug on Windows, so the
 *    bus is re-read every [POLL_MS] while the toggle is on. Between polls a
 *    dead stream is noticed by the sink itself (its wedge check), which calls
 *    back here.
 *
 * Concurrency: a single reconcile coroutine consumes a conflated channel of
 * "something changed" ticks — the preference, the poll and the sink's
 * callbacks all feed it — so reconcile never overlaps with itself, and every
 * driver open/close and output re-route happens on that one coroutine.
 */
@Singleton
class UsbExclusiveController @Inject constructor(
    private val driver: LibusbUacDriver,
    private val preferences: PreferencesManager,
    private val output: AudioOutputController,
) {
    enum class Status {
        Disabled,
        NoDevice,
        /** Desktop: a DAC is attached but its audio interfaces cannot be
         *  claimed — on Windows it is still bound to the Windows audio driver
         *  and needs WinUSB (Zadig). [lastOpenError] has the detail. */
        AwaitingPermission,
        /** libusb opened the DAC, its interfaces can be claimed, and the
         *  engine's output points at it; nothing is streaming yet. */
        DeviceOpen,
        /** Not reached on the desktop, as on Android: the interface claim is
         *  part of starting a stream, which reports [Streaming]. */
        InterfaceClaimed,
        /** The iso pump is live. */
        Streaming,
        /** Opening, claiming or streaming failed: [lastStartError] (a stream
         *  the DAC refused) or [lastOpenError] (the device) says which. */
        Error,
    }

    private val _status = MutableStateFlow(Status.Disabled)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** What the iso pump is currently doing — null when not streaming.
     *  Forwarded straight from the driver so the UI binds to one place
     *  (the controller) for everything bypass-related instead of
     *  reaching into the driver from ViewModels. */
    val diagnostics: StateFlow<BypassDiagnostics?> = driver.diagnostics

    /**
     * Categorised + detailed reason the most recent stream start failed.
     * Null when bypass is succeeding or hasn't been attempted.
     *
     * Desktop: held here rather than forwarded from the driver. The sink
     * that was refused is released straight away so the track can move to
     * the fallback output, and the driver's stop() clears its own copy; this
     * one stays until a stream starts or the toggle goes off.
     */
    private val _lastStartError = MutableStateFlow<StartFailure?>(null)
    val lastStartError: StateFlow<StartFailure?> = _lastStartError.asStateFlow()

    /** GET_RANGE inventory — what rates the DAC actually supports. */
    val supportedRates: StateFlow<List<ClockRateRange>> = driver.supportedRates

    /** Identity of the DAC the driver currently owns (manufacturer, product,
     *  VID:PID, USB version) — read from the USB descriptors, available the
     *  moment the device is opened, even before any stream is negotiated. */
    val dacInfo: StateFlow<DacInfo?> = driver.dacInfo

    /**
     * Desktop: why the attached DAC cannot be used, in words for the Settings
     * card — not bound to WinUSB, libusb missing, or the stream died and the
     * controller gave up — or null. Shown with [Status.AwaitingPermission]
     * and [Status.Error].
     */
    private val _lastOpenError = MutableStateFlow<String?>(null)
    val lastOpenError: StateFlow<String?> = _lastOpenError.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var enabled = false
    private val tick = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    /** Set by the sink (render thread) when its stream dies; taken by reconcile. */
    private val pendingLoss = AtomicReference<String?>(null)

    // Reconcile-coroutine state.
    private var routed = false
    private var lastEnabled = false
    private var failedDevice: UsbAttachedDevice? = null
    private var failedAtNs = 0L
    private var gaveUpOn: UsbAttachedDevice? = null
    private var lastRecoveryNs = 0L

    /** Called once at startup, from `AppLifecycle.onCreate` (Android: `MonochromeApp.onCreate`). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            preferences.usbExclusiveBitPerfectEnabled
                .distinctUntilChanged()
                .collect {
                    enabled = it
                    tick.trySend(Unit)
                }
        }
        scope.launch {
            for (ignored in tick) {
                try {
                    reconcile()
                } catch (e: Exception) {
                    // One bad pass must not end the loop every later toggle
                    // and unplug depends on.
                    Log.e(TAG, "reconcile failed", e)
                }
            }
        }
        // Desktop: the poll that stands in for USB attach/detach broadcasts.
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (enabled) tick.trySend(Unit)
            }
        }
        // Mirror the driver's streaming flag onto our public Status so the
        // Settings UI flips DeviceOpen → Streaming the moment the sink
        // starts the iso pump for a track. When the pump tears down (the
        // sink released, the toggle off) we fall back to DeviceOpen, which
        // is honest: the DAC handle is still ours, just no audio is flowing.
        scope.launch {
            driver.isStreaming.collect { streaming ->
                if (streaming) {
                    _lastStartError.value = null
                    _status.value = Status.Streaming
                } else if (_status.value == Status.Streaming) {
                    _status.value = if (driver.isOpen.value) Status.DeviceOpen else Status.NoDevice
                }
            }
        }
        // A start failure the driver reports outside a sink's configure.
        scope.launch {
            driver.lastStartError.collect { err ->
                if (err != null && enabled && !driver.isStreaming.value) noteFailure(err)
            }
        }
    }

    private fun noteFailure(failure: StartFailure) {
        _lastStartError.value = failure
        _status.value = Status.Error
    }

    /** The sink turned a track's format down (render thread). */
    private fun onSinkRefused(failure: StartFailure?) {
        if (!enabled) return
        noteFailure(failure ?: StartFailure(StartError.NoMatchingAlt, "no usable alternate setting"))
    }

    /** The sink's stream died under it (render thread): reconcile now, not at the next poll. */
    private fun onSinkLost(reason: String) {
        pendingLoss.set(reason)
        tick.trySend(Unit)
    }

    private fun reconcile() {
        val loss = pendingLoss.getAndSet(null)
        if (enabled != lastEnabled) {
            // A flip of the toggle is the user's "try again".
            lastEnabled = enabled
            failedDevice = null
            gaveUpOn = null
            lastRecoveryNs = 0L
        }
        if (!enabled) {
            unroute()
            if (driver.isOpen.value) driver.close()
            _lastStartError.value = null
            _lastOpenError.value = null
            _status.value = Status.Disabled
            return
        }
        if (!driver.isAvailable || !LibusbUacNative.isAvailable) {
            unroute()
            _lastOpenError.value = "The libusb driver (monochrome_usb) is missing or out of date, " +
                "so the DAC cannot be driven directly. WASAPI exclusive mode under Audio output is the " +
                "other bit-perfect path."
            _status.value = Status.Error
            return
        }

        if (driver.isOpen.value) {
            if (driver.isOwnedDeviceAttached()) {
                if (loss != null) recover(loss) else if (!routed) route()
                return
            }
            Log.i(TAG, "DAC ${driver.device.value} is gone; restoring the selected output")
            unroute()
            driver.close()
        }

        val skip = gaveUpOn
        val dac = driver.audioDevices().firstOrNull { skip == null || !skip.sameDevice(it) }
        if (dac == null) {
            unroute()
            // The DAC given up on is still plugged in: keep saying why.
            if (skip != null && driver.attachedDevices.value.any { skip.sameDevice(it) }) return
            gaveUpOn = null
            failedDevice = null
            _lastOpenError.value = null
            _status.value = Status.NoDevice
            return
        }
        // A DAC that could not be claimed is tried again only now and then:
        // every attempt is a libusb open, and on Windows it fails the same way
        // until the binding changes (which re-enumerates the device anyway).
        val failed = failedDevice
        if (failed != null && failed.sameDevice(dac) && System.nanoTime() - failedAtNs < OPEN_RETRY_NS) return

        if (!driver.open(dac)) {
            refuse(dac, driver.lastOpenError.value ?: "libusb could not open USB device ${dac.idHex}.")
            return
        }
        val unclaimable = LibusbUacNative.probeClaim()
        if (unclaimable != null) {
            driver.close()
            refuse(
                dac,
                "The DAC (${dac.idHex}) is still bound to the system's USB audio driver ($unclaimable). " +
                    "On Windows, bind its AudioControl and AudioStreaming interfaces to WinUSB with Zadig; " +
                    "on Linux, allow access to /dev/bus/usb.",
            )
            return
        }
        failedDevice = null
        _lastOpenError.value = null
        _status.value = Status.DeviceOpen
        route()
    }

    private fun refuse(dac: UsbAttachedDevice, message: String) {
        unroute()
        failedDevice = dac
        failedAtNs = System.nanoTime()
        _lastOpenError.value = message
        _status.value = Status.AwaitingPermission
        Log.w(TAG, message)
    }

    /**
     * The DAC is still attached and open but its stream died (the pump
     * stalled, or the driver stopped under the sink). One fresh stream is
     * tried; a second loss within [RECOVERY_WINDOW_NS] gives up on this DAC
     * until it is re-plugged or the toggle flips, and the listener's own
     * output takes over.
     */
    private fun recover(reason: String) {
        val now = System.nanoTime()
        if (lastRecoveryNs != 0L && now - lastRecoveryNs < RECOVERY_WINDOW_NS) {
            gaveUpOn = driver.device.value
            unroute()
            driver.close()
            _lastOpenError.value = "The DAC stopped taking audio ($reason), so playback moved to the " +
                "selected audio output. Re-plug the DAC, or turn Exclusive USB DAC off and on, to try again."
            _status.value = Status.Error
            Log.w(TAG, "giving up on the DAC: $reason")
            return
        }
        lastRecoveryNs = now
        Log.i(TAG, "restarting the USB stream: $reason")
        route()
    }

    /** Points the engine at the DAC; a fresh sink each time, so a dead stream is rebuilt. */
    private fun route() {
        output.overrideSink { LibusbUacSink(driver, onLost = ::onSinkLost, onRefused = ::onSinkRefused) }
        routed = true
    }

    /** Gives the engine back the listener's own output. */
    private fun unroute() {
        if (!routed) return
        output.overrideSink(null)
        routed = false
    }

    companion object {
        private const val TAG = "UsbExclusiveCtl"

        /** How often the bus is re-read while the toggle is on: libusb has no hotplug on Windows. */
        const val POLL_MS = 2_000L

        private const val OPEN_RETRY_NS = 10_000_000_000L
        private const val RECOVERY_WINDOW_NS = 10_000_000_000L
    }
}
