package tf.monochrome.desktop.audio.pipeline

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.desktop.audio.UsbAudioRouter
import tf.monochrome.desktop.audio.sink.OutputMode
import tf.monochrome.desktop.audio.usb.DacInfo
import tf.monochrome.desktop.audio.usb.LibusbUacDriver
import tf.monochrome.desktop.platform.AppScope
import tf.monochrome.desktop.player.engine.AudioOutputController
import tf.monochrome.desktop.player.engine.OutputSelection
import tf.monochrome.desktop.player.engine.PlaybackEngine

/**
 * Where the sound is going.
 *
 * [name], [typeLabel] and [kind] are what the Android panel had. The rest is
 * what the desktop can say that Android could not: how the audio leaves the
 * app ([mode]) and the format the device was actually opened with -- for USB
 * exclusive what the DAC negotiated, for WASAPI what the stream was opened
 * at. Null where nothing has been opened yet.
 */
data class RoutedOutput(
    val name: String,
    val typeLabel: String,
    val kind: OutputKind,
    val mode: OutputMode? = null,
    val sampleRateHz: Int? = null,
    /** Valid bits per sample on the way to the device (32 for float). */
    val bitsPerSample: Int? = null,
    val isFloat: Boolean = false,
    val channels: Int? = null,
    /** True when the preferred route refused this stream and a fallback carries it. */
    val isFallback: Boolean = false,
    /**
     * The device's own name, or null when it has none. [name] falls back to
     * [typeLabel] for display; this does not, so a headphone is never mistaken
     * for one called "Bluetooth" (see OutputEq).
     *
     * Desktop: a USB DAC's USB product string, the same whether WASAPI or
     * libusb carries it; otherwise the endpoint's name.
     */
    val productName: String? = null,
)

/**
 * Which output device is carrying playback, and at what rate.
 *
 * Desktop: **this is a reading, not inference.** Android had no public "what
 * is the current output route" call and picked from the connected outputs in
 * the platform's priority order. Here the engine's output is the app's own
 * sink, so the answer comes from it: the sink [AudioOutputController] built
 * (which route, which device, what it negotiated), the engine's negotiated
 * format, and for USB exclusive the iso pump's own diagnostics. Before
 * anything has played it describes the route that will be used. Re-read every
 * [POLL_MS] while something collects [routed].
 */
@Singleton
class OutputDeviceProbe @Inject constructor(
    private val output: AudioOutputController,
    private val engine: PlaybackEngine,
    private val driver: LibusbUacDriver,
    usbRouter: UsbAudioRouter,
    @AppScope scope: CoroutineScope,
) {
    private val ticks = flow {
        while (true) {
            emit(Unit)
            delay(POLL_MS)
        }
    }

    val routed: StateFlow<RoutedOutput?> =
        combine(ticks, usbRouter.usbOutputDevice) { _, dac -> currentOutput(dac) }
            .distinctUntilChanged()
            // Naming a WASAPI device enumerates the endpoints.
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The rate the device runs at, when the app can know it: the stream's own
     * rate in WASAPI exclusive mode, and what the DAC negotiated over USB.
     *
     * Desktop: null in WASAPI shared mode and on Java Sound. There the
     * Windows mixer converts to the endpoint's mix format, which the WASAPI
     * library does not report yet -- and the stream's rate would wrongly say
     * no conversion happens. Null rather than a guess, as on Android.
     */
    fun halSampleRateHz(): Int? = when (output.currentMode) {
        OutputMode.USB_EXCLUSIVE -> driver.diagnostics.value?.sampleRateHz?.takeIf { it > 0 }
        OutputMode.WASAPI_EXCLUSIVE -> output.currentFormat?.sampleRate?.takeIf { it > 0 }
        else -> null
    }

    /**
     * Whether the platform's spatializer processes this stream.
     *
     * Desktop: Windows' spatial sound (Windows Sonic, Dolby Atmos for
     * Headphones) and every other audio effect sit in the shared-mode
     * mixer. Exclusive streams -- WASAPI exclusive and the libusb path --
     * bypass it, so the answer there is [SpatialAudio.UNAVAILABLE]. In
     * shared mode the endpoint's setting is not readable through the WASAPI
     * library, so null ("cannot say"), as Android before 12L. [sampleRate] and
     * [channelCount] are kept for the Android signature; Windows applies the
     * setting to the endpoint, whatever the stream's format.
     */
    @Suppress("UNUSED_PARAMETER")
    fun spatialAudio(sampleRate: Int?, channelCount: Int?): SpatialAudio? = when (output.currentMode) {
        OutputMode.USB_EXCLUSIVE, OutputMode.WASAPI_EXCLUSIVE -> SpatialAudio.UNAVAILABLE
        else -> null
    }

    private fun currentOutput(dac: DacInfo?): RoutedOutput? {
        val current = output.currentOutput()
        if (current?.mode == OutputMode.USB_EXCLUSIVE) {
            val stream = driver.diagnostics.value
            return RoutedOutput(
                name = current.deviceName ?: dac?.displayName ?: USB_DAC,
                typeLabel = OutputMode.USB_EXCLUSIVE.label,
                kind = OutputKind.USB,
                mode = OutputMode.USB_EXCLUSIVE,
                sampleRateHz = stream?.sampleRateHz ?: current.format?.sampleRate,
                bitsPerSample = stream?.bitsPerSample,
                channels = stream?.channels ?: current.format?.channelCount,
                isFallback = current.isFallback,
                productName = productNameOf((driver.dacInfo.value ?: dac)?.product),
            )
        }
        if (current != null) {
            val mode = current.mode
            val name = current.deviceName ?: (if (mode == OutputMode.JAVA_SOUND) JAVA_SOUND_DEVICE else null)
            return routedFor(mode, name, current.format, dac, current.isFallback)
        }
        // Every candidate refused and the engine opened its own Java Sound line.
        if (output.engineFellBack) {
            return routedFor(OutputMode.JAVA_SOUND, JAVA_SOUND_DEVICE, engine.sinkFormat, dac, isFallback = true)
        }
        // Nothing has played since the route was set: the route that will be used.
        val state = output.state.value
        if (state.usbExclusive) {
            val owned = driver.dacInfo.value ?: dac
            return RoutedOutput(
                owned?.displayName ?: USB_DAC, OutputMode.USB_EXCLUSIVE.label, OutputKind.USB, OutputMode.USB_EXCLUSIVE,
                productName = productNameOf(owned?.product),
            )
        }
        val mode = when (state.kind) {
            OutputSelection.Kind.WASAPI_SHARED -> OutputMode.WASAPI_SHARED
            OutputSelection.Kind.WASAPI_EXCLUSIVE -> OutputMode.WASAPI_EXCLUSIVE
            OutputSelection.Kind.JAVA_SOUND -> OutputMode.JAVA_SOUND
        }
        val name = if (mode == OutputMode.JAVA_SOUND) {
            JAVA_SOUND_DEVICE
        } else {
            val devices = output.devices.value
            val id = if (state.usingFallback) null else state.deviceId
            (if (id == null) devices.firstOrNull { it.isDefault } else devices.firstOrNull { it.id == id })?.name
        }
        return routedFor(mode, name, null, dac, isFallback = false)
    }

    private fun routedFor(mode: OutputMode?, name: String?, format: AudioFormat?, dac: DacInfo?, isFallback: Boolean): RoutedOutput? {
        if (mode == null && name == null) return null
        val shape = format?.let(::pcmShape)
        val kind = outputKindFor(mode, name, dac?.product)
        return RoutedOutput(
            name = name ?: mode?.label ?: return null,
            typeLabel = mode?.label ?: "Audio output",
            kind = kind,
            mode = mode,
            sampleRateHz = format?.sampleRate?.takeIf { it > 0 },
            bitsPerSample = shape?.first,
            isFloat = shape?.second ?: false,
            channels = format?.channelCount?.takeIf { it > 0 },
            isFallback = isFallback,
            productName = productNameOf(if (kind == OutputKind.USB) dac?.product else name),
        )
    }

    private fun productNameOf(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val POLL_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val USB_DAC = "USB DAC"
        private const val JAVA_SOUND_DEVICE = "Java Sound (default device)"

        /** Windows names the HFP endpoint of a Bluetooth headset "Headset (… Hands-Free AG Audio)". */
        private val HANDS_FREE = listOf("hands-free", "handsfree")

        /** Endpoint names Windows gives display audio (HDMI / DisplayPort) on the common GPUs. */
        private val DISPLAY_AUDIO = listOf(
            "hdmi", "displayport", "display audio",
            "nvidia high definition audio", "amd high definition audio",
        )

        /**
         * The [OutputKind] for a route, from what Windows tells: the libusb
         * path is USB by definition; a WASAPI endpoint is named after its
         * device, so a USB DAC's product name, the hands-free marker of a
         * Bluetooth call link and the display-audio drivers can be recognised.
         * Anything else -- built-in speakers, line out, an A2DP headset, whose
         * endpoint name carries no marker -- is [OutputKind.OTHER] rather than
         * a guess.
         */
        internal fun outputKindFor(mode: OutputMode?, endpointName: String?, dacProduct: String?): OutputKind {
            if (mode == OutputMode.USB_EXCLUSIVE) return OutputKind.USB
            val name = endpointName?.lowercase() ?: return OutputKind.OTHER
            val product = dacProduct?.trim()?.lowercase()
            return when {
                !product.isNullOrEmpty() && name.contains(product) -> OutputKind.USB
                HANDS_FREE.any { name.contains(it) } -> OutputKind.BLUETOOTH_SCO
                DISPLAY_AUDIO.any { name.contains(it) } -> OutputKind.HDMI
                else -> OutputKind.OTHER
            }
        }

        /**
         * Valid bits and float-ness of what a sink was opened with. A 32-bit
         * integer sink carries 24 valid bits left-justified (WasapiSink's
         * PCM24_IN_32, packed so by PcmPacker); nothing the chain produces is
         * a 32-bit integer.
         */
        internal fun pcmShape(format: AudioFormat): Pair<Int, Boolean>? = when (format.encoding) {
            C.ENCODING_PCM_16BIT -> 16 to false
            C.ENCODING_PCM_24BIT -> 24 to false
            C.ENCODING_PCM_32BIT -> 24 to false
            C.ENCODING_PCM_FLOAT -> 32 to true
            else -> null
        }
    }
}
