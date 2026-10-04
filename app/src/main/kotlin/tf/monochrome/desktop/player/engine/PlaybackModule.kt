package tf.monochrome.desktop.player.engine

import android.util.Base64
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
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
import tf.monochrome.desktop.data.cache.DeezerPartialDataSource
import tf.monochrome.desktop.data.cache.DeezerStreamCacheManager
import tf.monochrome.desktop.data.cache.QobuzPartialDataSource
import tf.monochrome.desktop.data.cache.QobuzStreamCacheManager
import tf.monochrome.desktop.data.cache.SchemeRoutingDataSource
import tf.monochrome.desktop.platform.AppPaths
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
    fun playbackEngine(
        processors: ChainProcessors,
        selection: OutputSelection,
        qobuzCache: QobuzStreamCacheManager,
        deezerCache: DeezerStreamCacheManager,
        paths: AppPaths,
    ): PlaybackEngine {
        val engine = PlaybackEngine(processors.list, sinkFactory = { selection.createSink() })
        engine.sourceOpener = streamOpener(engine, qobuzCache, deezerCache, paths)
        return engine
    }

    /**
     * What PlaybackService.buildDataSourceFactory did, split by who is better
     * at it. `qobuz://` and `deezer://` go through the app's own sources (the
     * partial cache that plays a download while it is still arriving, and the
     * Deezer stripe decryption), behind the same SchemeRoutingDataSource.
     * Everything else goes to libavformat directly, so radio keeps its native
     * HTTP reconnects, ICY handling and HLS, which a byte pipe cannot give it;
     * an inline DASH manifest goes to its DASH demuxer.
     */
    private fun streamOpener(
        engine: PlaybackEngine,
        qobuzCache: QobuzStreamCacheManager,
        deezerCache: DeezerStreamCacheManager,
        paths: AppPaths,
    ): PlaybackEngine.SourceOpener {
        val qobuz = QobuzPartialDataSource.Factory(qobuzCache)
        val deezer = DeezerPartialDataSource.Factory(deezerCache)
        val routed = DataSource.Factory {
            SchemeRoutingDataSource(FileDataSource(), qobuz.createDataSource(), deezer.createDataSource())
        }
        val direct = engine.sourceOpener
        return PlaybackEngine.SourceOpener { item ->
            val uri = item.localConfiguration?.uri ?: throw IllegalArgumentException("MediaItem without a uri: $item")
            when (uri.scheme?.lowercase()) {
                "qobuz", "deezer" -> FfmpegDecoder.open(routed.createDataSource(), uri)
                "data" -> {
                    val manifest = inlineDashManifest(uri.toString())
                    if (manifest != null) FfmpegDecoder.openDash(manifest, paths.cacheDir.resolve("dash"), engine.userAgent)
                    else direct.open(item)
                }
                else -> direct.open(item)
            }
        }
    }

    /** The MPD inside `data:application/dash+xml;base64,…`, or null for any other data: URI. */
    internal fun inlineDashManifest(uri: String): String? {
        val prefix = "data:${MimeTypes.APPLICATION_MPD};base64,"
        if (!uri.startsWith(prefix)) return null
        return String(Base64.decode(uri.substring(prefix.length), Base64.DEFAULT), Charsets.UTF_8)
    }
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
