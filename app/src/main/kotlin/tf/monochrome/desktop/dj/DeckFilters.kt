package tf.monochrome.desktop.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.tan

/** RBJ biquad, transposed direct form II, with coefficients that can be retuned between samples. */
internal class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    fun setLowPass(sampleRate: Float, hz: Float, q: Float) {
        val k = tan(PI * hz.coerceIn(10f, sampleRate * 0.45f) / sampleRate).toFloat()
        val norm = 1f / (1f + k / q + k * k)
        b0 = k * k * norm
        b1 = 2f * b0
        b2 = b0
        a1 = 2f * (k * k - 1f) * norm
        a2 = (1f - k / q + k * k) * norm
    }

    fun setHighPass(sampleRate: Float, hz: Float, q: Float) {
        val k = tan(PI * hz.coerceIn(10f, sampleRate * 0.45f) / sampleRate).toFloat()
        val norm = 1f / (1f + k / q + k * k)
        b0 = norm
        b1 = -2f * norm
        b2 = norm
        a1 = 2f * (k * k - 1f) * norm
        a2 = (1f - k / q + k * k) * norm
    }

    /** Unity gain at every frequency, with the phase of a low-pass and high-pass pair at [hz] summed. */
    fun setAllPass(sampleRate: Float, hz: Float, q: Float) {
        val k = tan(PI * hz.coerceIn(10f, sampleRate * 0.45f) / sampleRate).toFloat()
        val norm = 1f / (1f + k / q + k * k)
        b0 = (1f - k / q + k * k) * norm
        b1 = 2f * (k * k - 1f) * norm
        b2 = 1f
        a1 = b1
        a2 = b0
    }
}

/**
 * A deck's tone controls: the three-band isolator EQ and the one-knob
 * filter, on stereo float blocks in place.
 *
 * The isolator is a Linkwitz-Riley crossover (4th order, as a DJ mixer's):
 * the lows split off at [LOW_SPLIT_HZ], what is left splits again at
 * [HIGH_SPLIT_HZ], and the lows pass an all-pass at [HIGH_SPLIT_HZ] so the
 * three bands stay in phase. Their sum is the input through an all-pass:
 * every frequency at its level, so a knob a hair off the centre changes
 * nothing you hear, and a band at gain 0 is a true kill (a band taken as
 * "input minus the others" would leave the phase error behind, about -7 dB
 * of a killed 40 Hz kick).
 *
 * With every band at the centre the samples pass untouched; leaving the
 * centre crossfades from them to the crossover over a block, and back.
 * Gains glide across each block and the filter is retuned every [SUB_BLOCK]
 * frames, so turning a knob never steps (no zipper noise).
 */
class DeckFilters(private val sampleRate: Int) {

    private val sr = sampleRate.toFloat()
    private val lowLp = Array(2) { lr4 { setLowPass(sr, LOW_SPLIT_HZ, BUTTERWORTH_Q) } }
    private val lowHp = Array(2) { lr4 { setHighPass(sr, LOW_SPLIT_HZ, BUTTERWORTH_Q) } }
    private val midLp = Array(2) { lr4 { setLowPass(sr, HIGH_SPLIT_HZ, BUTTERWORTH_Q) } }
    private val midHp = Array(2) { lr4 { setHighPass(sr, HIGH_SPLIT_HZ, BUTTERWORTH_Q) } }
    private val lowAp = Array(2) { Biquad().apply { setAllPass(sr, HIGH_SPLIT_HZ, BUTTERWORTH_Q) } }
    private val filter = Array(2) { Biquad() }

    /** A Linkwitz-Riley 4th-order section: the same Butterworth biquad twice. */
    private fun lr4(tune: Biquad.() -> Unit) = Array(2) { Biquad().apply(tune) }

    private fun Array<Biquad>.run(x: Float) = this[1].process(this[0].process(x))

    private var lowGain = 1f
    private var midGain = 1f
    private var highGain = 1f
    /** The filter's current cutoff: negative a low-pass, positive a high-pass, 0 off. */
    private var cutoff = 0f
    /** 0 passes the input untouched, 1 is the crossover's output. */
    private var wet = 0f

    fun reset() {
        for (c in 0..1) {
            for (b in lowLp[c]) b.reset()
            for (b in lowHp[c]) b.reset()
            for (b in midLp[c]) b.reset()
            for (b in midHp[c]) b.reset()
            lowAp[c].reset()
        }
        for (b in filter) b.reset()
    }

    /**
     * Runs [frames] frames of [l] and [r] through the EQ (linear band gains)
     * and the filter ([targetCutoff] as [DjMath.filterCutoff] gives it).
     */
    fun process(l: FloatArray, r: FloatArray, frames: Int, low: Float, mid: Float, high: Float, targetCutoff: Float) {
        if (frames <= 0) return
        val l0 = lowGain
        val m0 = midGain
        val h0 = highGain
        val dl = (low - l0) / frames
        val dm = (mid - m0) / frames
        val dh = (high - h0) / frames
        val atCentre = low == 1f && mid == 1f && high == 1f
        val w0 = wet
        val dw = ((if (atCentre) 0f else 1f) - w0) / frames
        val bypass = w0 == 0f && atCentre
        for (i in 0 until frames) {
            val gl = l0 + dl * (i + 1)
            val gm = m0 + dm * (i + 1)
            val gh = h0 + dh * (i + 1)
            val w = w0 + dw * (i + 1)
            // Run the crossover even when bypassed, so its state is current
            // the moment a knob leaves the centre.
            for (c in 0..1) {
                val x = if (c == 0) l[i] else r[i]
                val lo = lowAp[c].process(lowLp[c].run(x))
                val rest = lowHp[c].run(x)
                val md = midLp[c].run(rest)
                val hi = midHp[c].run(rest)
                if (!bypass) {
                    val y = gl * lo + gm * md + gh * hi
                    val out = x + w * (y - x)
                    if (c == 0) l[i] = out else r[i] = out
                }
            }
        }
        wet = if (atCentre) 0f else 1f
        lowGain = low
        midGain = mid
        highGain = high
        runFilter(l, r, frames, targetCutoff)
    }

    private fun runFilter(l: FloatArray, r: FloatArray, frames: Int, target: Float) {
        val from = cutoff
        if (from == 0f && target == 0f) return
        // Crossing from low-pass to high-pass goes through "off": no glide between modes.
        if (from == 0f || target == 0f || (from < 0f) != (target < 0f)) {
            if (from == 0f && target != 0f) for (b in filter) b.reset()
            cutoff = target
            if (target == 0f) return
            tune(target)
            for (i in 0 until frames) {
                l[i] = filter[0].process(l[i])
                r[i] = filter[1].process(r[i])
            }
            return
        }
        var i = 0
        val logFrom = ln(abs(from))
        val logTo = ln(abs(target))
        while (i < frames) {
            val n = minOf(SUB_BLOCK, frames - i)
            val t = (i + n).toFloat() / frames
            val hz = exp(logFrom + (logTo - logFrom) * t)
            tune(if (target < 0f) -hz else hz)
            for (j in i until i + n) {
                l[j] = filter[0].process(l[j])
                r[j] = filter[1].process(r[j])
            }
            i += n
        }
        cutoff = target
    }

    private fun tune(signedHz: Float) {
        for (b in filter) {
            if (signedHz < 0f) b.setLowPass(sr, -signedHz, FILTER_Q) else b.setHighPass(sr, signedHz, FILTER_Q)
        }
    }

    companion object {
        const val LOW_SPLIT_HZ = 250f
        const val HIGH_SPLIT_HZ = 2_500f
        private const val BUTTERWORTH_Q = 0.70710678f
        /** A touch of resonance, as a DJ filter has. */
        private const val FILTER_Q = 0.9f
        private const val SUB_BLOCK = 32
    }
}
