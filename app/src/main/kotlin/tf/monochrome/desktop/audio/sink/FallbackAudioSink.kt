package tf.monochrome.desktop.audio.sink

import android.util.Log
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer

/**
 * An output that tries several sinks in order at every [configure] and plays
 * through the first one that takes the format -- the desktop's version of the
 * Android `ForwardingAudioSink` delegate: when the USB DAC cannot take a
 * track's rate, the track goes to the listener's own output, and the next
 * track the DAC can take goes back to it.
 *
 * The choice is made per configure only, never mid-stream: the engine's clock
 * counts one sink's frames, so changing sinks under it would jump the
 * position. Losing a device mid-stream is the owner's business (it reopens the
 * engine's output, which re-anchors the clock).
 *
 * Candidates are built lazily and a candidate that is not carrying the audio
 * is released and forgotten, so a later fallback opens a fresh one that reads
 * the listener's choice as it is then. Not thread-safe beyond what the engine
 * needs: everything but the read-only properties runs on its render thread.
 */
class FallbackAudioSink(private val candidates: List<() -> AudioSink>) : AudioSink {

    init {
        require(candidates.isNotEmpty()) { "no output to fall back to" }
    }

    private val instances = arrayOfNulls<AudioSink>(candidates.size)

    /** The sink carrying the audio since the last successful [configure], or null. */
    @Volatile var active: AudioSink? = null
        private set

    /** Index in the candidate list of [active]; -1 when none. */
    @Volatile var activeIndex: Int = -1
        private set

    /** True when the audio is on a later candidate because an earlier one refused the format. */
    val isFallback: Boolean get() = activeIndex > 0

    /**
     * True when the last [configure] found no candidate that would take the
     * format, so the owner (the engine) opened something of its own. Kept
     * through [release], which is what the engine does next.
     */
    @Volatile var refusedAll: Boolean = false
        private set

    override fun configure(format: AudioFormat): AudioFormat {
        var failure: SinkException? = null
        for (i in candidates.indices) {
            val sink = instances[i] ?: candidates[i]().also { instances[i] = it }
            try {
                val negotiated = sink.configure(format)
                for (j in instances.indices) {
                    if (j != i) instances[j]?.let { it.release(); instances[j] = null }
                }
                active = sink
                activeIndex = i
                refusedAll = false
                if (i > 0) Log.i(TAG, "${sink.javaClass.simpleName} took $format after ${failure?.message}")
                return negotiated
            } catch (e: SinkException) {
                Log.w(TAG, "${sink.javaClass.simpleName} refused $format: ${e.message}")
                sink.release()
                instances[i] = null
                failure = e
            }
        }
        active = null
        activeIndex = -1
        refusedAll = true
        throw SinkException("no output could take $format", failure)
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int = active?.write(buffer, frames) ?: 0
    override fun playedFrames(): Long = active?.playedFrames() ?: 0L
    override fun pendingFrames(): Long = active?.pendingFrames() ?: 0L
    override fun drain() { active?.drain() }
    override fun play() { active?.play() }
    override fun pause() { active?.pause() }
    override fun flush() { active?.flush() }
    override fun stop() { active?.stop() }

    override fun release() {
        for (j in instances.indices) {
            instances[j]?.release()
            instances[j] = null
        }
        active = null
        activeIndex = -1
    }

    override val isOpen: Boolean get() = active?.isOpen ?: false
    override val isExclusive: Boolean get() = active?.isExclusive ?: false
    override val latencyFrames: Int get() = active?.latencyFrames ?: 0
    override val outputFormat: AudioFormat? get() = active?.outputFormat
    override val deviceName: String? get() = active?.deviceName

    private companion object {
        const val TAG = "FallbackAudioSink"
    }
}
