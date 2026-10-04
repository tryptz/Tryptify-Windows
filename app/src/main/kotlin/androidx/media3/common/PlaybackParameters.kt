// Desktop stand-in for Media3's PlaybackParameters: a speed and a pitch.
package androidx.media3.common

class PlaybackParameters @JvmOverloads constructor(
    @JvmField val speed: Float,
    @JvmField val pitch: Float = speed,
) {
    init {
        require(speed > 0f) { "speed must be positive" }
        require(pitch > 0f) { "pitch must be positive" }
    }

    fun withSpeed(speed: Float): PlaybackParameters = PlaybackParameters(speed, pitch)

    /** The media time advanced by [timeMs] of wall-clock playback at this speed, in microseconds. */
    fun getMediaTimeUsForPlayoutTimeMs(timeMs: Long): Long = (timeMs * 1000 * speed).toLong()

    override fun equals(other: Any?): Boolean = other is PlaybackParameters && speed == other.speed && pitch == other.pitch
    override fun hashCode(): Int = 31 * speed.hashCode() + pitch.hashCode()
    override fun toString(): String = "PlaybackParameters(speed=$speed, pitch=$pitch)"

    companion object {
        @JvmField val DEFAULT = PlaybackParameters(1f)
    }
}
