package tf.monochrome.desktop.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame hand-off between the render thread and the UI.
 *
 * The rule that matters is the one a picture cannot show until it tears: the
 * render thread must never be handed the slot the UI is drawing. The rest —
 * at most three slots, the newest frame wins, nothing leaks once the
 * composable has gone — is what keeps memory flat at 60 frames a second.
 */
class TripleBufferTest {

    private class Slot(val id: Int)

    @Test
    fun `nothing to show before the first frame`() {
        assertNull(TripleBuffer<Slot>().acquire())
    }

    @Test
    fun `the newest frame wins`() {
        val buffer = TripleBuffer<Slot>()
        val a = Slot(1)
        val b = Slot(2)
        buffer.publish(a)
        buffer.publish(b)
        assertSame(b, buffer.acquire())
        // The unseen one went back to the producer.
        assertSame(a, buffer.takeSpare())
    }

    @Test
    fun `the front frame stays put until a newer one arrives`() {
        val buffer = TripleBuffer<Slot>()
        val a = Slot(1)
        buffer.publish(a)
        assertSame(a, buffer.acquire())
        assertSame(a, buffer.acquire())
        assertNull("the shown frame is not a spare", buffer.takeSpare())
    }

    @Test
    fun `the producer is never handed the frame on screen`() {
        val buffer = TripleBuffer<Slot>()
        var next = 0
        val onScreen = ArrayList<Slot>()
        repeat(1_000) { round ->
            // The producer writes one or two frames per UI frame.
            repeat(1 + round % 2) {
                val slot = buffer.takeSpare() ?: Slot(next++)
                if (onScreen.isNotEmpty()) assertNotSame(onScreen.last(), slot)
                buffer.publish(slot)
            }
            buffer.acquire()?.let { onScreen += it }
        }
        assertTrue("never more than three slots: $next", next <= 3)
    }

    @Test
    fun `slots released by the consumer are reused`() {
        val buffer = TripleBuffer<Slot>()
        val a = Slot(1)
        val b = Slot(2)
        buffer.publish(a)
        buffer.acquire()
        buffer.publish(b)
        assertSame(b, buffer.acquire())
        assertSame(a, buffer.takeSpare())
    }

    @Test
    fun `close returns every slot it holds and refuses more`() {
        val buffer = TripleBuffer<Slot>()
        val a = Slot(1)
        val b = Slot(2)
        val c = Slot(3)
        buffer.publish(a)
        buffer.acquire()
        buffer.publish(b)
        buffer.giveBack(c)
        val held = buffer.close()
        assertEquals(setOf(a, b, c), held.toSet())
        assertTrue(buffer.isClosed)
        assertFalse(buffer.publish(Slot(4)))
        assertFalse(buffer.giveBack(Slot(5)))
        assertNull(buffer.takeSpare())
        assertNull(buffer.acquire())
        assertTrue(buffer.close().isEmpty())
    }
}
