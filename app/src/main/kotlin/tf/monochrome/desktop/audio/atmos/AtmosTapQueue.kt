package tf.monochrome.desktop.audio.atmos

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One E-AC-3 stream's raw access units, held between the decode thread that
 * reads them and the moment their PCM reaches the processor chain, then handed
 * to [AtmosFrameBuffer] for [AtmosAudioProcessor].
 *
 * Android's tap (`AtmosTapMediaSourceFactory`, kept in port/dropped) recorded
 * each access unit as the renderer pulled it into the decoder, so a frame
 * reached the buffer only the decoder's latency ahead of its PCM reaching the
 * processor. The processor depends on that: it anchors its clock on the oldest
 * frame buffered after a flush, and the buffer keeps just 128 frames (about
 * four seconds). The desktop decoder instead runs up to ten seconds ahead of
 * playback into its PCM ring, so recording at decode time would overflow the
 * buffer and anchor the processor on a frame seconds after the audio it is
 * rendering. This queue restores Android's timing: the decode thread [offer]s
 * every unit as it reads it, and the render thread [release]s each one when it
 * hands the chain the PCM that unit's time falls in. Keys are the same as on
 * Android: the unit's presentation time in microseconds.
 *
 * After an open or a seek the decoder starts at the access unit before the
 * target and trims the samples ahead of it, so the queue begins with units
 * whose audio is never played. The first [release] after [restart] drops those
 * and keeps the one unit whose time span covers the first sample, so the
 * processor anchors on the frame the audio actually starts in.
 *
 * Threads: [offer] and [clear] on the decode thread, [restart] and [release] on
 * the render thread, which does not read the stream while a seek is pending.
 */
class AtmosTapQueue {

    private val frames = ConcurrentLinkedQueue<AtmosFrameBuffer.TappedFrame>()
    private var primed = false   // render thread only

    /** Decode thread: an access unit read from the container, at its presentation time. */
    fun offer(timeUs: Long, bytes: ByteArray) {
        frames.add(AtmosFrameBuffer.TappedFrame(timeUs, bytes))
    }

    /** Decode thread, at a seek: everything queued belongs to audio before it. */
    fun clear() = frames.clear()

    /** Render thread: the stream (re)starts, so the next [release] begins with the covering unit. */
    fun restart() {
        primed = false
    }

    /**
     * Render thread: the chain has been handed the stream's PCM from
     * [startUs] (its first sample since the last [restart]) up to [untilUs].
     * Every unit whose presentation time falls before [untilUs] goes to
     * [sink], in order; on the first call after [restart], the units before the
     * one covering [startUs] are dropped instead.
     */
    fun release(startUs: Long, untilUs: Long, sink: (timeUs: Long, bytes: ByteArray) -> Unit) {
        if (!primed) {
            primed = true
            var covering: AtmosFrameBuffer.TappedFrame? = null
            while (true) {
                val head = frames.peek() ?: break
                if (head.timeUs > startUs) break
                covering = frames.poll()
            }
            covering?.let { sink(it.timeUs, it.bytes) }
        }
        while (true) {
            val head = frames.peek() ?: break
            if (head.timeUs >= untilUs) break
            frames.poll()
            sink(head.timeUs, head.bytes)
        }
    }

    /** Units queued and not yet released. */
    fun size(): Int = frames.size
}
