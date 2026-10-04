// Desktop stand-in for the Media3 constants the audio code reads; see
// audio/AudioProcessor.kt for why these live under Media3's package name.
package androidx.media3.common

object C {
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
    const val TIME_END_OF_SOURCE: Long = Long.MIN_VALUE
    const val INDEX_UNSET: Int = -1
    const val LENGTH_UNSET: Int = -1
    const val RATE_UNSET: Int = -1
    const val POSITION_UNSET: Int = -1
    const val PERCENTAGE_UNSET: Int = -1
    const val MICROS_PER_SECOND: Long = 1_000_000L
    const val NANOS_PER_SECOND: Long = 1_000_000_000L
    const val MILLIS_PER_SECOND: Long = 1_000L

    const val RESULT_END_OF_INPUT: Int = -1
    const val RESULT_MAX_LENGTH_EXCEEDED: Int = -2
    const val RESULT_NOTHING_READ: Int = -3
    const val RESULT_BUFFER_READ: Int = -4
    const val RESULT_FORMAT_READ: Int = -5

    // Encodings. Values match Media3 so persisted or logged numbers agree.
    const val ENCODING_INVALID: Int = 0
    const val ENCODING_PCM_16BIT: Int = 2
    const val ENCODING_PCM_8BIT: Int = 3
    const val ENCODING_PCM_FLOAT: Int = 4
    const val ENCODING_PCM_16BIT_BIG_ENDIAN: Int = 0x10000000
    const val ENCODING_PCM_24BIT: Int = 0x15000000
    const val ENCODING_PCM_24BIT_BIG_ENDIAN: Int = 0x50000000
    const val ENCODING_PCM_32BIT: Int = 0x16000000
    const val ENCODING_PCM_32BIT_BIG_ENDIAN: Int = 0x60000000
    const val ENCODING_E_AC3: Int = 6
    const val ENCODING_E_AC3_JOC: Int = 18

    const val WAKE_MODE_NONE: Int = 0
    const val WAKE_MODE_LOCAL: Int = 1
    const val WAKE_MODE_NETWORK: Int = 2

    const val USAGE_MEDIA: Int = 1
    const val AUDIO_CONTENT_TYPE_MUSIC: Int = 2

    const val TRACK_TYPE_AUDIO: Int = 1
    const val TRACK_TYPE_VIDEO: Int = 2
}
