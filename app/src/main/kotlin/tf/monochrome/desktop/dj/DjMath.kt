package tf.monochrome.desktop.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * The DJ console's arithmetic: tempo, beat phase, sync, and the gain laws of
 * the channel strip. Pure functions, so the tests can hold every one of them.
 *
 * Units: speed is a playback-rate multiplier (1 = as recorded); positions are
 * frames at the track's own rate; a beat phase is 0..1 within a beat.
 */
object DjMath {

    /** The tempo fader's ranges, ± as a fraction: the usual DJ set (6, 8, 10, 16, 50 %). */
    val TEMPO_RANGES = floatArrayOf(0.06f, 0.08f, 0.10f, 0.16f, 0.50f)
    const val DEFAULT_TEMPO_RANGE = 0.08f

    /** Speed for a tempo fader at [fader] (-1..1, + is faster) over ± [range]. */
    fun speedFor(fader: Float, range: Float): Double = 1.0 + fader.coerceIn(-1f, 1f) * range

    /** The fader position that gives [speed] over ± [range], clamped to the fader. */
    fun faderFor(speed: Double, range: Float): Float =
        ((speed - 1.0) / range).toFloat().coerceIn(-1f, 1f)

    /**
     * Keylock: the transposition that undoes the pitch a speed change brings,
     * so a track played at [speed] keeps its key (1.06 → about -1 semitone).
     */
    fun keylockSemitones(speed: Double): Float = (-12.0 * ln(speed) / ln(2.0)).toFloat()

    /** Frames per beat at [bpm] for a track at [sampleRate]. */
    fun beatFrames(bpm: Double, sampleRate: Int): Double = 60.0 * sampleRate / bpm

    /** Where [position] falls within its beat, 0..1, on a grid starting at [firstBeat]. */
    fun beatPhase(position: Double, firstBeat: Double, beatFrames: Double): Double {
        val beats = (position - firstBeat) / beatFrames
        return beats - floor(beats)
    }

    /** The grid line nearest [position]. */
    fun nearestBeat(position: Double, firstBeat: Double, beatFrames: Double): Double {
        val beats = Math.round((position - firstBeat) / beatFrames)
        return firstBeat + beats * beatFrames
    }

    /** The grid line at or before [position]. */
    fun beatAtOrBefore(position: Double, firstBeat: Double, beatFrames: Double): Double =
        firstBeat + floor((position - firstBeat) / beatFrames + 1e-9) * beatFrames

    /** The shortest way from phase [from] to phase [to], in beats: -0.5..0.5. */
    fun phaseDelta(from: Double, to: Double): Double {
        var d = to - from
        d -= floor(d + 0.5)
        return d
    }

    /**
     * Sync's tempo: the speed that brings a track of [bpm] to [targetBpm],
     * counting a half- or double-time match as a match (a 70 BPM track syncs
     * to 140 at speed 1.0, not 2.0). Returns the speed nearest 1.
     */
    fun syncSpeed(bpm: Double, targetBpm: Double): Double {
        if (bpm <= 0.0 || targetBpm <= 0.0) return 1.0
        var best = targetBpm / bpm
        for (m in doubleArrayOf(0.5, 2.0)) {
            val s = targetBpm / (bpm * m)
            if (abs(ln(s)) < abs(ln(best))) best = s
        }
        return best
    }

    /** The beat-length ratio [syncSpeed] picked: 0.5, 1 or 2 leader beats to one of the deck's. */
    fun syncMultiple(bpm: Double, targetBpm: Double): Double {
        if (bpm <= 0.0 || targetBpm <= 0.0) return 1.0
        val s = syncSpeed(bpm, targetBpm)
        return targetBpm / (bpm * s)
    }

    /** Crossfader curves. */
    enum class CrossfaderCurve {
        /** Constant power: -3 dB each in the middle, so a blend keeps its loudness. */
        SMOOTH,
        /** Both decks at full level over the middle; each fades only on its far half. */
        BLEND,
        /** Scratch cut: full until the last few percent of travel. */
        CUT,
    }

    /**
     * The two decks' gains for a crossfader at [position] (-1 = all A, 1 = all
     * B) on [curve]: index 0 is deck A, 1 deck B.
     */
    fun crossfaderGains(position: Float, curve: CrossfaderCurve, out: FloatArray = FloatArray(2)): FloatArray {
        val t = ((position.coerceIn(-1f, 1f) + 1f) * 0.5f)
        when (curve) {
            CrossfaderCurve.SMOOTH -> {
                out[0] = cos(t * PI / 2).toFloat()
                out[1] = sin(t * PI / 2).toFloat()
            }
            CrossfaderCurve.BLEND -> {
                out[0] = (2f * (1f - t)).coerceAtMost(1f)
                out[1] = (2f * t).coerceAtMost(1f)
            }
            CrossfaderCurve.CUT -> {
                out[0] = ((1f - t) / CUT_WIDTH).coerceAtMost(1f)
                out[1] = (t / CUT_WIDTH).coerceAtMost(1f)
            }
        }
        return out
    }

    private const val CUT_WIDTH = 0.04f

    /** Channel fader [value] 0..1 to gain: squared, roughly the audio taper of a hardware fader. */
    fun faderGain(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return v * v
    }

    /** The trim knob's range, dB: -24 at the bottom, 0 in the middle, +12 at the top. */
    const val TRIM_MIN_DB = -24f
    const val TRIM_MAX_DB = 12f

    /** Trim knob [knob] 0..1 (0.5 = unity) to dB. */
    fun trimDb(knob: Float): Float {
        val k = knob.coerceIn(0f, 1f)
        return if (k < 0.5f) TRIM_MIN_DB * (0.5f - k) * 2f else TRIM_MAX_DB * (k - 0.5f) * 2f
    }

    /** Below this an EQ knob is a kill: the band goes silent. */
    const val EQ_KILL = 0.02f
    const val EQ_MIN_DB = -26f
    const val EQ_MAX_DB = 6f

    /**
     * EQ knob [knob] 0..1 to a linear gain: 0.5 is unity, the top +6 dB, the
     * bottom -26 dB down to a kill below [EQ_KILL].
     */
    fun eqGain(knob: Float): Float {
        val k = knob.coerceIn(0f, 1f)
        if (k <= EQ_KILL) return 0f
        val db = if (k < 0.5f) EQ_MIN_DB * (0.5f - k) / (0.5f - EQ_KILL) else EQ_MAX_DB * (k - 0.5f) * 2f
        return dbToGain(db)
    }

    /** Around the filter knob's centre nothing is filtered, so a knob that rests a hair off does nothing. */
    const val FILTER_DEAD_ZONE = 0.03f
    const val FILTER_LOW_PASS_FROM = 20_000f
    const val FILTER_LOW_PASS_TO = 80f
    const val FILTER_HIGH_PASS_FROM = 20f
    const val FILTER_HIGH_PASS_TO = 8_000f

    /**
     * The one-knob filter: [knob] -1..1, low-pass to the left and high-pass to
     * the right, swept on a log scale. The cutoff in Hz, negative for a
     * low-pass, positive for a high-pass, 0 when the knob is in its dead zone.
     */
    fun filterCutoff(knob: Float): Float {
        val k = knob.coerceIn(-1f, 1f)
        if (abs(k) <= FILTER_DEAD_ZONE) return 0f
        val t = (abs(k) - FILTER_DEAD_ZONE) / (1f - FILTER_DEAD_ZONE)
        return if (k < 0f) {
            -(FILTER_LOW_PASS_FROM * (FILTER_LOW_PASS_TO / FILTER_LOW_PASS_FROM).pow(t))
        } else {
            FILTER_HIGH_PASS_FROM * (FILTER_HIGH_PASS_TO / FILTER_HIGH_PASS_FROM).pow(t)
        }
    }

    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    /** The loop sizes the console steps through, in beats. */
    val LOOP_SIZES = doubleArrayOf(1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)
    const val DEFAULT_LOOP_BEATS = 4.0

    /** The beat-jump sizes, in beats. */
    val JUMP_SIZES = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0)
    const val DEFAULT_JUMP_BEATS = 4.0

    /** The size next to [beats] in [sizes], [steps] places on (negative is smaller), clamped to the list. */
    fun stepSize(sizes: DoubleArray, beats: Double, steps: Int): Double {
        var i = sizes.indices.minByOrNull { abs(ln(sizes[it] / beats)) } ?: return beats
        i = (i + steps).coerceIn(0, sizes.size - 1)
        return sizes[i]
    }

    /** "1/4", "1", "16": a loop or jump size as the console prints it. */
    fun beatsLabel(beats: Double): String =
        if (beats >= 1.0) beats.toInt().toString() else "1/${Math.round(1.0 / beats)}"
}
