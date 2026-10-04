package tf.monochrome.desktop.visualizer.gl

import kotlin.math.max

/** The visualizer's frame-rate ceiling on the desktop, whatever the display runs at. */
internal const val MAX_VISUALIZER_FPS = 60

/**
 * When the render thread may draw the next frame.
 *
 * Frames are asked for by the composable once per display refresh, so on a
 * 60 Hz panel this simply lets every request through and the visualizer stays
 * in step with the display. On a faster panel the requests come quicker than
 * [fps], and each one waits for its turn on a fixed schedule instead.
 *
 * The schedule is a due time rather than "an interval since the last frame".
 * Measured from the last frame, any lateness — a request that arrived a
 * millisecond after the slot opened — is added to every interval after it,
 * and the rate drifts below the ceiling for good. A due time absorbs it: the
 * next frame is due one interval after the last *due* time, not after the
 * moment it actually ran.
 *
 * [toleranceNanos] lets a request that is a little early through. Requests
 * timed by a 60 Hz display land within a millisecond or so either side of the
 * slot; turning the early ones away would drop the visualizer to 30 fps on
 * exactly the display it was meant to match. With no tolerance, frames are
 * never closer together than one interval — what the engine's own Target FPS
 * cap needs, see [ProjectMGlHost].
 */
internal class FramePacer(fps: Int, private val toleranceNanos: Long) {
    private val intervalNanos = 1_000_000_000L / fps.coerceAtLeast(1)
    private var nextDueNanos = 0L
    private var started = false

    /** How long a frame asked for at [nowNanos] has to wait; 0 or less means now. */
    fun delayNanos(nowNanos: Long): Long =
        if (!started) 0L else nextDueNanos - toleranceNanos - nowNanos

    /**
     * Records a frame drawn at [nowNanos].
     *
     * After a long gap — playback paused, the window minimised — the due time
     * is far behind, and catching up to it would let every request through
     * back to back until it had. So the next frame is never due sooner than
     * one interval (less the tolerance) after this one.
     */
    fun onFrameRendered(nowNanos: Long) {
        nextDueNanos = if (!started) {
            nowNanos + intervalNanos
        } else {
            max(nextDueNanos + intervalNanos, nowNanos + intervalNanos - toleranceNanos)
        }
        started = true
    }

    fun reset() {
        started = false
    }
}
