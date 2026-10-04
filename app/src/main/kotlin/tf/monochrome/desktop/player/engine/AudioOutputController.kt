package tf.monochrome.desktop.player.engine

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.desktop.audio.sink.AudioSink
import tf.monochrome.desktop.audio.sink.FallbackAudioSink
import tf.monochrome.desktop.audio.sink.JavaSoundSink
import tf.monochrome.desktop.audio.sink.LibusbUacSink
import tf.monochrome.desktop.audio.sink.OutputMode
import tf.monochrome.desktop.audio.sink.WasapiSink
import tf.monochrome.desktop.audio.sink.outputMode
import tf.monochrome.desktop.audio.wasapi.WasapiNative
import tf.monochrome.desktop.data.preferences.DesktopDataStores
import tf.monochrome.desktop.platform.AppPaths
import tf.monochrome.desktop.platform.AppScope

/**
 * Where the sound goes: the desktop's answer to Android's audio routing.
 *
 * Android had one output, the system mixer, and a USB DAC path beside it. On
 * Windows the listener picks the endpoint and how to reach it: WASAPI shared
 * (through the Windows mixer, which resamples to the device's mix format),
 * WASAPI exclusive (the app owns the device; the stream reaches the DAC at
 * its own rate and depth, which is the bit-perfect path) or Java Sound (the
 * fallback when the WASAPI library is missing). The choice is persisted and
 * applied by handing the engine a new sink factory, which reopens the device
 * at the current position.
 *
 * The libusb DAC path sits on top of that choice rather than replacing it:
 * while `UsbExclusiveController` has a DAC it can claim, it installs the USB
 * sink with [overrideSink], and the listener's choice stays persisted
 * underneath as the fallback -- for a track the DAC cannot take, and for
 * everything once the DAC is gone or the toggle is off.
 *
 * Every factory handed to the engine builds a [FallbackAudioSink] over the
 * route, best first: the USB override when set, the listener's choice, WASAPI
 * shared on the same device when exclusive mode refuses a format (the music
 * stays on the device the listener picked), then Java Sound.
 *
 * The device list follows WASAPI's endpoint notifications. If the chosen
 * device is unplugged, output falls back to the default device rather than
 * going silent, and the choice is kept so it is used again when the device
 * returns.
 */
@Singleton
class AudioOutputController @Inject constructor(
    private val paths: AppPaths,
    private val engine: PlaybackEngine,
    private val selection: OutputSelection,
    @AppScope private val scope: CoroutineScope,
) {
    data class Device(val id: String, val name: String, val isDefault: Boolean)

    data class State(
        val kind: OutputSelection.Kind,
        /** Null means "the Windows default device". */
        val deviceId: String?,
        val exclusiveBufferMillis: Int,
        /** True while the chosen device is missing and the default stands in. */
        val usingFallback: Boolean = false,
        /**
         * True while the USB DAC path is installed over [kind]: the engine
         * plays to the DAC through libusb, and [kind] / [deviceId] are what it
         * falls back to. A track the DAC refuses still plays on the fallback
         * ([currentOutput] says which one is carrying the audio).
         */
        val usbExclusive: Boolean = false,
    )

    /** What is carrying the audio right now, as far as this controller's sink can tell. */
    data class CurrentOutput(
        val mode: OutputMode?,
        val deviceName: String?,
        /** The format the engine hands the sink; for USB that is the chain's, not the DAC's. */
        val format: AudioFormat?,
        /** True when a later candidate took the stream because the preferred route refused it. */
        val isFallback: Boolean,
    )

    private val store get() = DesktopDataStores.get(paths, "audio_output")

    val availableKinds: List<OutputSelection.Kind> =
        if (WasapiNative.isAvailable) OutputSelection.Kind.entries.toList() else listOf(OutputSelection.Kind.JAVA_SOUND)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private val _state = MutableStateFlow(State(selection.kind, selection.deviceId, selection.exclusiveBufferMillis))
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false

    /** The USB sink factory while the DAC path is installed. */
    @Volatile private var usbOverride: (() -> AudioSink)? = null

    /** The sink the engine built from this controller's factory most recently. */
    @Volatile private var engineSink: FallbackAudioSink? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val prefs = store.data.first()
            val kind = prefs[KIND]?.let { saved -> OutputSelection.Kind.entries.firstOrNull { it.name == saved } }
                ?.takeIf { it in availableKinds } ?: selection.kind
            val buffer = prefs[EXCLUSIVE_BUFFER_MS]?.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS) ?: selection.exclusiveBufferMillis
            refreshDevices()
            apply(State(kind, prefs[DEVICE_ID], buffer), reopen = false)
            // The engine was built with a bare factory; from here on it builds
            // through this controller's, which is what gives currentOutput()
            // and the fallback chain something to work with. Nothing is open
            // at startup, so this swaps the factory rather than reopening.
            reopenEngine()
            watchDevices()
        }
    }

    fun select(kind: OutputSelection.Kind, deviceId: String?) {
        if (kind !in availableKinds) return
        val next = _state.value.copy(kind = kind, deviceId = deviceId)
        scope.launch {
            persist(next)
            apply(next, reopen = true)
        }
    }

    fun setExclusiveBufferMillis(ms: Int) {
        val next = _state.value.copy(exclusiveBufferMillis = ms.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS))
        scope.launch {
            persist(next)
            apply(next, reopen = next.kind == OutputSelection.Kind.WASAPI_EXCLUSIVE)
        }
    }

    fun refreshDevices() {
        _devices.value = WasapiNative.listDevices().map { Device(it.id, it.name, it.isDefault) }
    }

    /**
     * Installs [factory] -- the libusb DAC sink -- in front of the listener's
     * choice, or with null removes it and restores that choice. Reopens the
     * engine's output at the current position either way. Called by
     * `UsbExclusiveController` when a claimable DAC appears or goes and when
     * its toggle flips; the listener's persisted choice is not touched.
     */
    fun overrideSink(factory: (() -> AudioSink)?) {
        usbOverride = factory
        _state.update { it.copy(usbExclusive = factory != null) }
        reopenEngine()
        Log.i(TAG, if (factory != null) "output: USB DAC (libusb) over ${selection.kind}" else "output: ${selection.kind} restored")
    }

    /**
     * What is carrying the audio now. Null before the engine has opened an
     * output through this controller (nothing has played since the route last
     * changed), and when every candidate refused and the engine opened its
     * own last-resort Java Sound sink ([engineFellBack]).
     */
    fun currentOutput(): CurrentOutput? {
        val sink = engineSink ?: return null
        val active = sink.active ?: return null
        return CurrentOutput(active.outputMode(), deviceNameOf(active), active.outputFormat, sink.isFallback)
    }

    /**
     * A WASAPI sink's device from the cached endpoint list, which follows the
     * endpoint notifications: asking the sink enumerates the endpoints through
     * JNI, and the pipeline panel asks every second, from the UI thread. The
     * sink was built from [selection] (a fallback is built fresh when needed),
     * so the selection names its device.
     */
    private fun deviceNameOf(active: AudioSink): String? {
        if (active !is WasapiSink) return active.deviceName
        val id = selection.deviceId
        val cached = _devices.value.firstOrNull { if (id == null) it.isDefault else it.id == id }
        return cached?.name ?: active.deviceName
    }

    /** The route carrying the audio now, without naming the device: cheap, any thread. */
    val currentMode: OutputMode? get() = engineSink?.active?.outputMode()

    /** What the sink carrying the audio was opened with (the engine's side of it); cheap, any thread. */
    val currentFormat: AudioFormat? get() = engineSink?.active?.outputFormat

    /**
     * True when no output of the route would take the last format and the
     * engine opened its own last-resort Java Sound line ([PlaybackEngine.sinkFormat]
     * describes it).
     */
    val engineFellBack: Boolean get() = engineSink?.refusedAll == true

    /** True while the libusb sink itself, not a fallback, is what the engine writes to. */
    val isUsbCarryingAudio: Boolean get() = engineSink?.active is LibusbUacSink

    private suspend fun persist(state: State) {
        store.edit { prefs ->
            prefs[KIND] = state.kind.name
            if (state.deviceId != null) prefs[DEVICE_ID] = state.deviceId else prefs.remove(DEVICE_ID)
            prefs[EXCLUSIVE_BUFFER_MS] = state.exclusiveBufferMillis
        }
    }

    /**
     * Points the sink factory at [wanted], substituting the default device
     * while the chosen one is absent. While the USB DAC is carrying the audio
     * the engine is left alone: the listener's choice matters again only once
     * the DAC refuses a track or goes, and the next fallback reads it fresh
     * (see [FallbackAudioSink]); reopening here would cut the DAC's stream for
     * a change it does not hear.
     */
    private fun apply(wanted: State, reopen: Boolean) {
        val present = wanted.deviceId == null || _devices.value.any { it.id == wanted.deviceId }
        val missing = !present && wanted.kind != OutputSelection.Kind.JAVA_SOUND
        selection.kind = wanted.kind
        selection.deviceId = if (missing) null else wanted.deviceId
        selection.exclusiveBufferMillis = wanted.exclusiveBufferMillis
        _state.value = wanted.copy(usingFallback = missing, usbExclusive = usbOverride != null)
        if (reopen && !isUsbCarryingAudio) reopenEngine()
        Log.i(TAG, "output ${wanted.kind} device=${selection.deviceId ?: "default"}${if (missing) " (chosen device absent)" else ""}")
    }

    /**
     * Hands the engine a factory for the route as it stands when the engine
     * calls it (at its next configure). The sink it replaces is forgotten
     * now: the engine releases it, and until something plays again there is
     * no current output to report.
     */
    private fun reopenEngine() {
        engineSink = null
        engine.setSink { FallbackAudioSink(candidates()).also { engineSink = it } }
    }

    private fun candidates(): List<() -> AudioSink> = buildList {
        usbOverride?.let { add(it) }
        add { selection.createSink() }
        if (selection.kind == OutputSelection.Kind.WASAPI_EXCLUSIVE && WasapiNative.isAvailable) {
            // Exclusive mode refuses formats the device cannot take natively;
            // shared mode takes anything (Windows converts), on the same device.
            add { WasapiSink(selection.deviceId, isExclusive = false) }
        }
        if (selection.kind != OutputSelection.Kind.JAVA_SOUND) add { JavaSoundSink() }
    }

    private fun watchDevices() {
        if (!WasapiNative.isAvailable) return
        scope.launch {
            var generation = WasapiNative.nativeDeviceGeneration()
            while (isActive) {
                delay(DEVICE_POLL_MS)
                val now = WasapiNative.nativeDeviceGeneration()
                if (now == generation) continue
                generation = now
                val before = _state.value
                val defaultBefore = _devices.value.firstOrNull { it.isDefault }?.id
                refreshDevices()
                val defaultNow = _devices.value.firstOrNull { it.isDefault }?.id
                val present = before.deviceId == null || _devices.value.any { it.id == before.deviceId }
                // Reopen only when the route really changes: the chosen device
                // left or came back, or the Windows default moved while the
                // default is what plays (directly or standing in for a missing
                // device). Plugging in an unrelated device changes nothing.
                val routeMoved = present == before.usingFallback
                val defaultMoved = defaultNow != defaultBefore && (before.deviceId == null || before.usingFallback)
                if (routeMoved || defaultMoved) apply(before, reopen = true)
            }
        }
    }

    private companion object {
        const val TAG = "AudioOutput"
        val KIND = stringPreferencesKey("kind")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val EXCLUSIVE_BUFFER_MS = intPreferencesKey("exclusive_buffer_ms")
        const val MIN_BUFFER_MS = 10
        const val MAX_BUFFER_MS = 500
        const val DEVICE_POLL_MS = 1_000L
    }
}
