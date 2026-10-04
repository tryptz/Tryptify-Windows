package tf.monochrome.desktop.audio.usb

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin face of the libusb-backed USB Audio Class direct-output driver:
 * libusb claims the DAC's streaming interface and an isochronous-transfer
 * pump on a dedicated audio thread feeds it, with no operating-system mixer
 * in between. The native side is `native/usb/libusb_uac_driver.cpp`, reached
 * through `usb_jni.cpp`; the JNI symbol names are
 * `Java_tf_monochrome_desktop_audio_usb_LibusbUacDriver_<name>`.
 *
 * **How a device is reached on the desktop.** Android handed the app a
 * `UsbDevice` and a file descriptor once the user had granted the USB
 * permission dialog; the driver wrapped that descriptor
 * (`libusb_wrap_sys_device`). There is no file descriptor and no permission
 * dialog here: libusb enumerates the bus itself, so [refreshDevices] lists
 * what is attached and [open] opens one by its ids (`libusb_open`).
 *
 * **Windows needs a driver binding, not a permission.** Out of the box a DAC
 * is bound to Microsoft's `usbaudio2.sys`, which owns its interfaces; libusb
 * can enumerate the device and read its descriptors but `libusb_open` or the
 * interface claim fails. The audio interface has to be bound to WinUSB (or
 * libusbK) first -- Zadig does this in two clicks and it survives re-plugs --
 * after which Windows no longer sees the DAC as a sound device and this
 * driver can have it exclusively. That is the trade: this path is the
 * advanced option, and the default bit-perfect output on Windows is WASAPI
 * exclusive mode (`audio/sink/WasapiSink`). A failed [open] records why in
 * [lastOpenError] so the Settings UI can say so instead of silently falling
 * back.
 *
 * Desktop: the Android permission flow (`requestPermission`, the
 * `ACTION_USB_PERMISSION` broadcast, the `PendingIntent`) is gone -- it has
 * no equivalent; the driver binding above is the gate. The attach/detach
 * broadcasts that `UsbExclusiveController` listened to are replaced by
 * [refreshDevices], which re-reads the bus on demand; a libusb hotplug
 * callback through JNI would make that push rather than pull and is left for
 * the native side.
 *
 * Linux (the smoke-test platform) has no driver binding step: the kernel's
 * `snd-usb-audio` is detached automatically on claim
 * (`libusb_set_auto_detach_kernel_driver`), subject to the udev permissions
 * on `/dev/bus/usb`.
 */
@Singleton
class LibusbUacDriver @Inject constructor() {

    /**
     * False when `monochrome_usb` could not be loaded or libusb failed to
     * initialise; every call is then a safe no-op that reports "nothing
     * attached", and the app runs without the bypass path. The library is
     * optional on the desktop in a way it was not on Android: a Linux build
     * without libusb, or a Windows install missing the DLL, must still play.
     */
    val isAvailable: Boolean

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * Reason the most recent [start] failed, or null if it
     * succeeded / hasn't been attempted. Carries both the categorised
     * code and the native-side detail line so the UI can render
     * actionable text without having to fish through the log.
     */
    private val _lastStartError = MutableStateFlow<StartFailure?>(null)
    val lastStartError: StateFlow<StartFailure?> = _lastStartError.asStateFlow()

    /**
     * Why the most recent [open] failed, in words the Settings UI can show
     * (on Windows: almost always "not bound to WinUSB"), or null once an open
     * succeeds. Separate from [lastStartError], which is about negotiating a
     * stream on a device that *is* open.
     */
    private val _lastOpenError = MutableStateFlow<String?>(null)
    val lastOpenError: StateFlow<String?> = _lastOpenError.asStateFlow()

    /**
     * Snapshot of the iso pump's current state: negotiated rate, bit
     * depth, alt setting, clock entity, feedback endpoint presence,
     * UAC version. Null when not streaming. Refreshed on every
     * [start]/[stop] transition.
     *
     * The Settings UI binds to this to show "192 kHz · 24-bit · async
     * feedback ✓ · clock #9 · UAC2 HS" beneath the toggle so the user
     * has visible proof of what they're getting.
     */
    private val _diagnostics = MutableStateFlow<BypassDiagnostics?>(null)
    val diagnostics: StateFlow<BypassDiagnostics?> = _diagnostics.asStateFlow()

    /**
     * Per-clock-entity GET_RANGE table snapshot. Empty list before
     * any [start] succeeds, or if every clock entity refused both
     * SET_CUR and GET_RANGE. UAC1 devices return synthetic entries
     * (clockId=0) sourced from the AS_FORMAT_TYPE rate table.
     */
    private val _supportedRates = MutableStateFlow<List<ClockRateRange>>(emptyList())
    val supportedRates: StateFlow<List<ClockRateRange>> = _supportedRates.asStateFlow()

    /**
     * Everything libusb could see on the bus at the last [refreshDevices],
     * audio or not. The controller filters on [UsbAttachedDevice.hasAudioStreaming].
     */
    private val _attachedDevices = MutableStateFlow<List<UsbAttachedDevice>>(emptyList())
    val attachedDevices: StateFlow<List<UsbAttachedDevice>> = _attachedDevices.asStateFlow()

    /** Device the driver currently owns, or null. */
    private val _device = MutableStateFlow<UsbAttachedDevice?>(null)
    val device: StateFlow<UsbAttachedDevice?> = _device.asStateFlow()

    /** Identity of the DAC the driver currently owns, or null.
     *  Populated the moment [open] succeeds -- available before any stream
     *  is negotiated, so the UI can say *which* DAC it is even when nothing
     *  is streaming. */
    private val _dacInfo = MutableStateFlow<DacInfo?>(null)
    val dacInfo: StateFlow<DacInfo?> = _dacInfo.asStateFlow()

    init {
        isAvailable = try {
            UsbNativeLoader.ensureLoaded()
            nativeInit()
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError from the load, or the loader object's
            // ExceptionInInitializerError / NoClassDefFoundError wrapping it.
            Log.w(TAG, "libusb driver unavailable: ${e.message}")
            false
        }
    }

    // ── enumeration ──────────────────────────────────────────────────────

    /**
     * Re-reads the bus and returns what is attached, updating
     * [attachedDevices]. Cheap (descriptor reads only, no device is opened),
     * so the controller can call it when its toggle flips, on a timer while
     * waiting for a DAC, and to notice that the device it holds has gone.
     */
    fun refreshDevices(): List<UsbAttachedDevice> {
        if (!isAvailable) return emptyList()
        val devices = UsbAttachedDevice.decodeAll(nativeListDevices())
        _attachedDevices.value = devices
        return devices
    }

    /** The attached devices that have an AudioStreaming interface -- the DACs. */
    fun audioDevices(): List<UsbAttachedDevice> = refreshDevices().filter { it.hasAudioStreaming }

    /** Whether the device the driver owns is still on the bus, after a fresh enumeration. */
    fun isOwnedDeviceAttached(): Boolean {
        val owned = _device.value ?: return false
        return refreshDevices().any { it.sameDevice(owned) }
    }

    // ── open / close ─────────────────────────────────────────────────────

    /**
     * Opens [device] for exclusive PCM output. Returns false on any failure
     * and records why in [lastOpenError]; on Windows the usual reason is that
     * the DAC is still bound to `usbaudio2.sys` (see the class KDoc). An
     * already-open device is closed first.
     */
    fun open(device: UsbAttachedDevice): Boolean =
        open(device.vendorId, device.productId, device.bus, device.address)

    /**
     * Opens the first device matching [vendorId]:[productId]; [bus] and
     * [address] narrow it to one of two identical DACs and may be -1 for any.
     */
    fun open(vendorId: Int, productId: Int, bus: Int = -1, address: Int = -1): Boolean {
        val idHex = "%04x:%04x".format(vendorId, productId)
        if (!isAvailable) {
            _lastOpenError.value = "The libusb driver is not available on this system."
            return false
        }
        if (_isOpen.value || nativeIsOpen()) close()
        if (!nativeOpenByIds(vendorId, productId, bus, address)) {
            val message = "libusb could not open USB device $idHex. " +
                "On Windows the DAC's audio interface must be bound to WinUSB (use Zadig); " +
                "while usbaudio2.sys owns it, libusb cannot claim it. On Linux, check the udev " +
                "permissions on /dev/bus/usb."
            Log.e(TAG, message)
            _lastOpenError.value = message
            return false
        }
        // Record the device as the bus reports it, so bus/address are real even
        // when the caller passed -1 for either.
        val opened = refreshDevices().firstOrNull {
            it.vendorId == vendorId && it.productId == productId &&
                (bus < 0 || it.bus == bus) && (address < 0 || it.address == address)
        } ?: UsbAttachedDevice(vendorId, productId, bus, address, hasAudioStreaming = true)
        _device.value = opened
        _dacInfo.value = DacInfo.fromAttached(opened)
        _lastOpenError.value = null
        _isOpen.value = true
        Log.i(TAG, "opened $opened")
        return true
    }

    /**
     * Opens the first attached device with an AudioStreaming interface --
     * what the controller does when the toggle is on and no DAC is remembered.
     */
    fun openFirstAudioDevice(): Boolean {
        val dac = audioDevices().firstOrNull()
        if (dac == null) {
            _lastOpenError.value = if (isAvailable) {
                "No USB Audio Class device is attached."
            } else {
                "The libusb driver is not available on this system."
            }
            return false
        }
        return open(dac)
    }

    fun close() {
        if (!isAvailable) return
        if (!_isOpen.value && !nativeIsOpen()) return
        nativeClose()
        _device.value = null
        _dacInfo.value = null
        _isOpen.value = false
        // Without this, after a DAC unplug isStreaming stayed at its last
        // value (often true), and everything gated on it -- the bypass volume
        // controller, the sink's "still ours" checks -- kept acting on a DAC
        // that was gone.
        _isStreaming.value = false
    }

    // ── streaming ────────────────────────────────────────────────────────

    /**
     * Negotiates a UAC2 alt setting matching [sampleRate]/[bitsPerSample]/[channels],
     * claims the streaming interface, sets the clock-source rate via
     * a class-specific control transfer, and spins up the iso pump.
     * Returns false on any failure (logged with TAG "LibusbUacDriver" and
     * categorised in [lastStartError]); the sink then falls back to the
     * system output.
     *
     * The DSP / EQ / tap chain is not this driver's concern: the engine runs
     * the chain once, in its render thread, and [write] takes finished PCM.
     */
    fun start(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean {
        if (!isAvailable) return false
        val ok = nativeStart(sampleRate, bitsPerSample, channels)
        _isStreaming.value = ok
        // Always pull the rate inventory: native populates it during
        // start whether we succeeded or not (failure path's GET_RANGE
        // diagnostic loop also caches into supportedRates_), and the
        // UI wants to show "your DAC supports X / Y / Z kHz" even
        // when the requested rate isn't one of them.
        _supportedRates.value = ClockRateRange.decodeAll(nativeSupportedRates())
        if (ok) {
            _diagnostics.value = BypassDiagnostics.fromLongArray(nativeActiveStream())
            _lastStartError.value = null
        } else {
            // No active stream -- but we still want the UI to render
            // the failure category. Diagnostics stays null so the
            // "currently active" block hides.
            _diagnostics.value = null
            val code = StartError.fromCode(nativeLastErrorCode())
            val detail = nativeLastErrorDetail().orEmpty()
            _lastStartError.value = StartFailure(code, detail)
        }
        return ok
    }

    fun stop() {
        if (!isAvailable) return
        nativeStop()
        _isStreaming.value = false
        _diagnostics.value = null
        _lastStartError.value = null
    }

    /**
     * Discards any PCM still queued without releasing the streaming
     * interface. Use between tracks -- releasing the interface lets the
     * operating system briefly re-grab it, after which the next [start]
     * gets `LIBUSB_ERROR_BUSY` and the user has to re-plug.
     */
    fun flushRing() {
        if (isAvailable) nativeFlushRing()
    }

    /**
     * True when the iso pump is already running a stream matching the
     * requested format. Lets the sink skip a stop/start cycle on
     * track-to-track transitions when the format is unchanged (the common
     * case for an album).
     */
    fun isStreamingFormat(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean =
        isAvailable && nativeIsStreamingFormat(sampleRate, bitsPerSample, channels)

    /** Cumulative frames the iso pump has dispatched to the DAC since [start].
     *  The sink's position comes from this -- the DAC's clock -- because the
     *  frames written run ahead of realtime: the render thread fills the ring
     *  much faster than the DAC drains it. */
    fun playedFrames(): Long = if (isAvailable) nativePlayedFrames() else 0L

    /** Frames currently sitting in the ring waiting for the DAC to consume.
     *  How the sink knows it has actually finished playback vs. just queued it. */
    fun pendingFrames(): Long = if (isAvailable) nativePendingFrames() else 0L

    /** Pushes [frames] frames from [buffer] (direct, native-byte-order),
     *  starting at the buffer's CURRENT POSITION. Position is load-bearing
     *  on retries: after a partial write (driver ring ran full) the
     *  unconsumed tail sits at a non-zero position, and the native side
     *  reads relative to that offset -- resending from the buffer's start
     *  would duplicate PCM and scramble the stream. Returns frames accepted;
     *  the caller advances the position by that many frames. */
    fun write(buffer: ByteBuffer, frames: Int): Int {
        if (!isAvailable) return 0
        if (!buffer.isDirect) {
            Log.w(TAG, "write: non-direct ByteBuffer -- caller must copy to a direct buffer first")
            return 0
        }
        return nativeWrite(buffer, buffer.position(), frames)
    }

    // ── JNI ──────────────────────────────────────────────────────────────
    // Names match native/usb/usb_jni.cpp exactly.

    private external fun nativeInit(): Boolean
    /** Android's path: wrap an already-open file descriptor. Unused on the
     *  desktop (there is no fd to wrap) but kept so the JNI surface stays one
     *  file on both sides. */
    @Suppress("unused")
    private external fun nativeOpen(fd: Int): Boolean
    /** Desktop: `libusb_open` the first device matching the ids; bus/address may be -1. */
    private external fun nativeOpenByIds(vendorId: Int, productId: Int, bus: Int, address: Int): Boolean
    /** Desktop: attached devices flattened as [vid, pid, bus, address, hasAudioStreaming] x N. */
    private external fun nativeListDevices(): IntArray?
    private external fun nativeClose()
    private external fun nativeIsOpen(): Boolean
    private external fun nativeStart(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean
    private external fun nativeStop()
    private external fun nativeFlushRing()
    private external fun nativeIsStreamingFormat(sampleRate: Int, bitsPerSample: Int, channels: Int): Boolean
    private external fun nativeWrite(buffer: ByteBuffer, byteOffset: Int, frames: Int): Int
    private external fun nativePlayedFrames(): Long
    private external fun nativePendingFrames(): Long
    /** Numeric category -- see [StartError]; 0 = Ok / no failure recorded. */
    private external fun nativeLastErrorCode(): Int
    /** Free-form detail string from the native failure site. May be empty. */
    private external fun nativeLastErrorDetail(): String?
    /** Flat (clockId, minHz, maxHz, resHz) quads from the device's GET_RANGE,
     *  or null/empty when no clock-entity rate inventory is available. */
    private external fun nativeSupportedRates(): IntArray?
    /** Packed long[] of negotiated stream parameters; null when not streaming.
     *  See [BypassDiagnostics.fromLongArray] for the field layout. */
    private external fun nativeActiveStream(): LongArray?

    private companion object {
        const val TAG = "LibusbUacDriver"
    }
}
