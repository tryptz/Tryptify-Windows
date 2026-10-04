package tf.monochrome.desktop.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visualizer's frame pacing, driven the way the render thread drives it:
 * requests arrive at a display's refresh rate, a request that is too early
 * waits until [FramePacer.delayNanos] says its slot is open, and the frame is
 * then drawn and recorded.
 *
 * Every failure here is silent on screen until someone counts: a pacer that
 * turns away a 60 Hz display's slightly early requests halves the frame rate
 * on the most common panel there is, and one that lets a 144 Hz panel through
 * is reading back and uploading 2.4 times the pixels the cap exists to bound.
 */
class FramePacerTest {

    private val second = 1_000_000_000L
    private val tolerance = 2_000_000L

    /**
     * Requests at [hz] with a deterministic ±[jitterNanos] wobble for [seconds];
     * returns the times frames were drawn.
     */
    private fun simulate(
        pacer: FramePacer,
        hz: Double,
        seconds: Int,
        jitterNanos: Long = 0L,
    ): List<Long> {
        val period = (second / hz).toLong()
        val drawn = ArrayList<Long>()
        var request = 0L
        var index = 0
        while (request < seconds * second) {
            val wobble = if (jitterNanos == 0L) 0L else ((index * 7919L) % (2 * jitterNanos + 1)) - jitterNanos
            val asked = request + wobble
            val wait = pacer.delayNanos(asked)
            val at = if (wait > 0) asked + wait else asked
            // A request that would wait past the next one is overtaken by it,
            // as the render thread coalesces them.
            if (at < request + period) {
                pacer.onFrameRendered(at)
                drawn += at
            }
            request += period
            index++
        }
        return drawn
    }

    @Test
    fun `a 60 Hz display gets every frame even with early requests`() {
        val drawn = simulate(FramePacer(60, tolerance), hz = 60.0, seconds = 5, jitterNanos = 1_000_000L)
        val requests = (5 * second + second / 60 - 1) / (second / 60)
        assertEquals(requests, drawn.size.toLong())
    }

    @Test
    fun `a 120 Hz display is held to 60`() {
        val drawn = simulate(FramePacer(60, tolerance), hz = 120.0, seconds = 5)
        assertTrue("${drawn.size}", drawn.size in 295..301)
    }

    @Test
    fun `a 144 Hz display is held at or under 60`() {
        val drawn = simulate(FramePacer(60, tolerance), hz = 144.0, seconds = 5)
        assertTrue("${drawn.size}", drawn.size in 280..301)
    }

    @Test
    fun `no second ever holds more than the cap`() {
        val drawn = simulate(FramePacer(60, tolerance), hz = 240.0, seconds = 10, jitterNanos = 500_000L)
        var start = 0
        for (end in drawn.indices) {
            while (drawn[end] - drawn[start] >= second) start++
            assertTrue("window ending ${drawn[end]}", end - start + 1 <= 61)
        }
    }

    @Test
    fun `a long pause does not release a burst`() {
        val pacer = FramePacer(60, tolerance)
        pacer.onFrameRendered(0L)
        // Ten seconds later two requests arrive a millisecond apart.
        val first = 10 * second
        assertTrue(pacer.delayNanos(first) <= 0L)
        pacer.onFrameRendered(first)
        assertTrue(pacer.delayNanos(first + 1_000_000L) > 0L)
    }

    @Test
    fun `without tolerance frames are never closer than one interval`() {
        val pacer = FramePacer(30, 0L)
        val drawn = simulate(pacer, hz = 60.0, seconds = 5, jitterNanos = 1_000_000L)
        val interval = second / 30
        for (i in 1 until drawn.size) {
            assertTrue("gap ${drawn[i] - drawn[i - 1]}", drawn[i] - drawn[i - 1] >= interval)
        }
    }

    @Test
    fun `the first request is never held`() {
        assertTrue(FramePacer(60, tolerance).delayNanos(123_456_789L) <= 0L)
    }

    @Test
    fun `reset forgets the schedule`() {
        val pacer = FramePacer(60, tolerance)
        pacer.onFrameRendered(0L)
        assertTrue(pacer.delayNanos(1_000_000L) > 0L)
        pacer.reset()
        assertTrue(pacer.delayNanos(1_000_000L) <= 0L)
    }
}
