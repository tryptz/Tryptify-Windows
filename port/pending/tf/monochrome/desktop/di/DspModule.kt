package tf.monochrome.desktop.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tf.monochrome.desktop.audio.dsp.DspEngineManager
import tf.monochrome.desktop.audio.dsp.MixBusProcessor
import tf.monochrome.desktop.audio.dsp.UpmixProcessor
import tf.monochrome.desktop.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.desktop.audio.dsp.oxford.CompressorEffect
import tf.monochrome.desktop.audio.dsp.oxford.InflatorEffect
import tf.monochrome.desktop.data.preferences.PreferencesManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DspModule {

    @Provides
    @Singleton
    fun provideMixBusProcessor(
        inflator: InflatorEffect,
        compressor: CompressorEffect,
        crossfeed: CrossfeedEffect,
    ): MixBusProcessor = MixBusProcessor(inflator, compressor, crossfeed)

    @Provides
    @Singleton
    fun provideDspEngineManager(
        processor: MixBusProcessor,
        preferences: PreferencesManager,
        upmixProcessor: UpmixProcessor,
    ): DspEngineManager = DspEngineManager(processor, preferences, upmixProcessor)
}
