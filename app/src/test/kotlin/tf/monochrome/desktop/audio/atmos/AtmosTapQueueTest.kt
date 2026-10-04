package tf.monochrome.desktop.audio.atmos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The hand-off of raw E-AC-3 access units from the decode thread to the Atmos
 * frame buffer. The renderer anchors on the oldest unit buffered after a flush
 * and keeps only 128 of them, so a unit must arrive about when its PCM does,
 * and the first one after a seek must be the one the audio starts in.
 */
class AtmosTapQueueTest {

    // 1536-sample E-AC-3 frames at 48 kHz are 32 ms apart.
    private val frameUs = 32_000L

    private fun queueOf(vararg times: Long) = AtmosTapQueue().apply {
        for (t in times) offer(t, byteArrayOf(t.toByte()))
    }

    private fun AtmosTapQueue.drain(startUs: Long, untilUs: Long): List<Long> {
        val out = mutableListOf<Long>()
        release(startUs, untilUs) { timeUs, _ -> out += timeUs }
        return out
    }

    @Test
    fun `a unit is released only once its audio reaches the chain`() {
        val q = queueOf(0, frameUs, 2 * frameUs, 3 * frameUs)
        assertEquals(listOf(0L), q.drain(0, 20_000))
        assertEquals(listOf(frameUs), q.drain(0, 40_000))
        assertEquals(emptyList<Long>(), q.drain(0, 60_000))
        assertEquals(listOf(2 * frameUs, 3 * frameUs), q.drain(0, 100_000))
        assertEquals(0, q.size())
    }

    @Test
    fun `after a seek the units before the one covering the target are dropped`() {
        // The decoder restarts at the unit before the target and trims the
        // samples ahead of it; those units' audio is never played.
        val q = queueOf(0, frameUs, 2 * frameUs, 3 * frameUs)
        q.restart()
        assertEquals(listOf(2 * frameUs), q.drain(startUs = 70_000, untilUs = 90_000))
        assertEquals(listOf(3 * frameUs), q.drain(startUs = 70_000, untilUs = 110_000))
    }

    @Test
    fun `a target on a unit boundary starts with that unit`() {
        val q = queueOf(0, frameUs, 2 * frameUs)
        q.restart()
        assertEquals(listOf(frameUs), q.drain(startUs = frameUs, untilUs = frameUs + 1))
    }

    @Test
    fun `a fresh stream keeps its first unit`() {
        val q = queueOf(0, frameUs)
        assertEquals(listOf(0L), q.drain(startUs = 0, untilUs = 1))
    }

    @Test
    fun `clearing at a seek forgets everything queued`() {
        val q = queueOf(0, frameUs, 2 * frameUs)
        q.clear()
        q.restart()
        assertEquals(emptyList<Long>(), q.drain(0, Long.MAX_VALUE))
    }

    @Test
    fun `keys and bytes are the access unit's own`() {
        val bytes = byteArrayOf(0x0B, 0x77, 1, 2, 3)
        val q = AtmosTapQueue().apply { offer(123_456L, bytes) }
        var seenTime = -1L
        var seenBytes: ByteArray? = null
        q.release(0, 200_000) { t, b -> seenTime = t; seenBytes = b }
        assertEquals(123_456L, seenTime)
        assertSame(bytes, seenBytes)
    }
}
