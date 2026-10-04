package tf.monochrome.desktop.player.engine

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A single-producer, single-consumer byte ring for decoded PCM. The decode
 * thread writes, the render thread reads; both see each other's progress
 * through two monotonically increasing counters, so neither takes a lock on
 * the audio path. Capacity is fixed at construction.
 */
class PcmRing(val capacity: Int) {
    private val data = ByteArray(capacity)
    private val written = AtomicLong(0)
    private val read = AtomicLong(0)

    fun available(): Int = (written.get() - read.get()).toInt()
    fun free(): Int = capacity - available()
    fun isEmpty(): Boolean = available() == 0

    /** Copies as many bytes as fit from [src]'s position; returns the count. */
    fun write(src: ByteBuffer): Int {
        val n = minOf(src.remaining(), free())
        if (n <= 0) return 0
        val w = written.get()
        val start = (w % capacity).toInt()
        val first = minOf(n, capacity - start)
        src.get(data, start, first)
        if (n > first) src.get(data, 0, n - first)
        written.set(w + n)
        return n
    }

    /** Copies up to [maxBytes] into [dst] from its position; returns the count. */
    fun read(dst: ByteBuffer, maxBytes: Int): Int {
        val n = minOf(maxBytes, available(), dst.remaining())
        if (n <= 0) return 0
        val r = read.get()
        val start = (r % capacity).toInt()
        val first = minOf(n, capacity - start)
        dst.put(data, start, first)
        if (n > first) dst.put(data, 0, n - first)
        read.set(r + n)
        return n
    }

    /** Drops everything buffered. Only safe when the producer is paused. */
    fun clear() {
        read.set(written.get())
    }
}
