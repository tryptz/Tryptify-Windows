package tf.monochrome.desktop.player.engine

import androidx.media3.common.audio.AudioProcessor
import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import tf.monochrome.desktop.audio.atmos.AtmosAudioProcessor
import tf.monochrome.desktop.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.desktop.audio.dsp.DownmixProcessor
import tf.monochrome.desktop.audio.dsp.MixBusProcessor
import tf.monochrome.desktop.audio.dsp.UpmixProcessor
import tf.monochrome.desktop.audio.eq.AutoEqProcessor
import tf.monochrome.desktop.audio.eq.ParametricEqProcessor
import tf.monochrome.desktop.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.desktop.audio.resample.FloatSonicAudioProcessor
import tf.monochrome.desktop.audio.resample.VariRateAudioProcessor
import tf.monochrome.desktop.audio.sink.AudioSink
import tf.monochrome.desktop.audio.sink.JavaSoundSink
import tf.monochrome.desktop.audio.sink.WasapiSink
import tf.monochrome.desktop.audio.stretch.StretchAudioProcessor
import tf.monochrome.desktop.audio.tempo.BpmTapProcessor
import tf.monochrome.desktop.audio.usb.ToFloatPcmAudioProcessor
import tf.monochrome.desktop.audio.wasapi.WasapiNative
import tf.monochrome.desktop.visualizer.ProjectMAudioBus
import tf.monochrome.desktop.visualizer.ProjectMAudioTapProcessor

/**
 * Wires the engine: the processor chain in the order the Android USB/hi-res
 * path ran it (playback-architecture brief §2.4), and the default output.
 */
@Module
object PlaybackModule {

    /** Which output the engine opens next; changed from Settings through [OutputSelection]. */
    @Provides
    @Singleton
    fun outputSelection(): OutputSelection = OutputSelection()

    @Provides
    @Singleton
    fun chainProcessors(
        channelDetector: ChannelDetectorProcessor,
        bpmTap: BpmTapProcessor,
        atmos: AtmosAudioProcessor,
        upmix: UpmixProcessor,
        mixBus: MixBusProcessor,
        downmix: DownmixProcessor,
        autoEq: AutoEqProcessor,
        parametricEq: ParametricEqProcessor,
        spectrumTap: SpectrumAnalyzerTap,
        audioBus: ProjectMAudioBus,
        variRate: VariRateAudioProcessor,
        stretch: StretchAudioProcessor,
        floatSonic: FloatSonicAudioProcessor,
    ): ChainProcessors = ChainProcessors(
        listOf(
            ToFloatPcmAudioProcessor(),
            channelDetector, bpmTap, atmos, upmix, mixBus, downmix,
            autoEq, parametricEq, spectrumTap, ProjectMAudioTapProcessor(audioBus),
            variRate, stretch, floatSonic,
        ),
    )

    /** The preserve-pitch time-stretch stage; the engine controller drives its speed. */
    @Provides
    @Singleton
    fun floatSonic(): FloatSonicAudioProcessor = FloatSonicAudioProcessor()

    @Provides
    @Singleton
    fun playbackEngine(processors: ChainProcessors, selection: OutputSelection): PlaybackEngine =
        PlaybackEngine(processors.list, sinkFactory = { selection.createSink() })
}

/** The ordered processor list, wrapped so Dagger can tell it from any other List. */
class ChainProcessors(val list: List<AudioProcessor>)

/** The listener's output choice: WASAPI (shared, or exclusive on a device) or Java Sound. */
class OutputSelection {
    enum class Kind { WASAPI_SHARED, WASAPI_EXCLUSIVE, JAVA_SOUND }

    @Volatile var kind: Kind = if (WasapiNative.isAvailable) Kind.WASAPI_SHARED else Kind.JAVA_SOUND
    @Volatile var deviceId: String? = null
    @Volatile var exclusiveBufferMillis: Int = 40

    fun createSink(): AudioSink = when (kind) {
        Kind.WASAPI_SHARED -> if (WasapiNative.isAvailable) WasapiSink(deviceId, isExclusive = false) else JavaSoundSink()
        Kind.WASAPI_EXCLUSIVE -> if (WasapiNative.isAvailable) WasapiSink(deviceId, isExclusive = true, bufferMillis = exclusiveBufferMillis) else JavaSoundSink()
        Kind.JAVA_SOUND -> JavaSoundSink()
    }
}
