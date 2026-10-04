// Desktop stand-in for the handful of Media3 Util helpers the audio code uses.
package androidx.media3.common.util

import androidx.media3.common.C

object Util {
    @JvmStatic
    fun isEncodingLinearPcm(encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    /** Linear PCM wider than 16 bits, which the platform mixer would truncate. */
    @JvmStatic
    fun isEncodingHighResolutionPcm(encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    @JvmStatic
    fun getPcmFrameSize(encoding: Int, channelCount: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> channelCount
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> channelCount * 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> channelCount * 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> channelCount * 4
        else -> throw IllegalArgumentException("not a PCM encoding: $encoding")
    }

    /**
     * Android's AudioFormat channel mask for a channel count. Kept for the
     * callers that log or compare it; the desktop sinks take a channel count.
     */
    @JvmStatic
    fun getAudioTrackChannelConfig(channelCount: Int): Int = when (channelCount) {
        1 -> 0x4            // CHANNEL_OUT_MONO
        2 -> 0xC            // CHANNEL_OUT_STEREO
        3 -> 0x1C           // STEREO | FRONT_CENTER
        4 -> 0xCC           // CHANNEL_OUT_QUAD
        5 -> 0xDC           // QUAD | FRONT_CENTER
        6 -> 0xFC           // CHANNEL_OUT_5POINT1
        7 -> 0x4FC          // 5POINT1 | BACK_CENTER
        8 -> 0x18FC         // CHANNEL_OUT_7POINT1_SURROUND
        10 -> 0xC18FC       // 7POINT1 + top front left/right (5.1.4-ish)
        12 -> 0x3CC18FC     // CHANNEL_OUT_7POINT1POINT4
        else -> 0           // CHANNEL_INVALID
    }

    @JvmStatic
    fun constrainValue(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(value, max))

    @JvmStatic
    fun constrainValue(value: Long, min: Long, max: Long): Long = maxOf(min, minOf(value, max))

    @JvmStatic
    fun constrainValue(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(value, max))

    @JvmStatic
    fun usToMs(timeUs: Long): Long = if (timeUs == C.TIME_UNSET || timeUs == C.TIME_END_OF_SOURCE) timeUs else timeUs / 1000

    @JvmStatic
    fun msToUs(timeMs: Long): Long = if (timeMs == C.TIME_UNSET || timeMs == C.TIME_END_OF_SOURCE) timeMs else timeMs * 1000

    @JvmStatic
    fun sampleCountToDurationUs(sampleCount: Long, sampleRate: Int): Long =
        sampleCount * C.MICROS_PER_SECOND / sampleRate

    @JvmStatic
    fun durationUsToSampleCount(durationUs: Long, sampleRate: Int): Long =
        durationUs * sampleRate / C.MICROS_PER_SECOND
}
