package tf.monochrome.desktop.audio.dsp.model

data class BusLevels(
    val peakDbL: Float = -60f,
    val peakDbR: Float = -60f,
    val holdDbL: Float = -60f,
    val holdDbR: Float = -60f
)
