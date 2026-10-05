package tf.monochrome.desktop.dj.controller

import kotlin.math.abs

/**
 * Soft takeover for one absolute control (a knob or fader on hardware).
 *
 * The hardware and the software can disagree: the screen moved a fader, sync
 * moved the tempo, or the controller was plugged in with its knobs anywhere.
 * Following the hardware then would jump the sound to wherever the knob
 * happens to be. So the hardware is ignored until it reaches the software's
 * value (within [threshold]) or passes it, and followed from then on, until
 * something else moves the value again.
 *
 * Values are in the control's own units ([threshold] too), so a -1..1
 * crossfader and a 0..1 EQ each pick their own.
 */
class SoftTakeover(private val threshold: Float = 0.03f) {
    /** The hardware's last position, NaN before the first. */
    private var hardware = Float.NaN
    /** What this control last wrote, NaN while it doesn't hold the value. */
    private var written = Float.NaN

    /**
     * The hardware moved to [position] while the value is [current]: true
     * if the value should follow it (the caller then sets it to [position]).
     */
    fun accept(position: Float, current: Float): Boolean {
        val previous = hardware
        hardware = position
        val held = !written.isNaN() && abs(current - written) <= HELD_EPSILON
        val caught = held ||
            abs(position - current) <= threshold ||
            (!previous.isNaN() && (previous - current) * (position - current) <= 0f)
        written = if (caught) position else Float.NaN
        return caught
    }

    /** Forget the hardware's position, as after a reconnect. */
    fun reset() {
        hardware = Float.NaN
        written = Float.NaN
    }

    private companion object {
        /** A value read back can differ from the one written by its round trip (the tempo goes through a speed). */
        const val HELD_EPSILON = 1e-3f
    }
}
