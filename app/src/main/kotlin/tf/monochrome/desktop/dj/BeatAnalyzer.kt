package tf.monochrome.desktop.dj

import tf.monochrome.desktop.audio.tempo.TempoEstimator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A track's beat grid: a constant tempo and where its first beat lies.
 * [firstBeat] is in frames at the track's rate, within the first beat
 * (0 until one beat's length), so the grid line at or after any position is
 * a whole number of beats on from it.
 */
data class BeatGrid(val bpm: Double, val firstBeat: Double, val confidence: Float) {
    fun beatFrames(sampleRate: Int): Double = DjMath.beatFrames(bpm, sampleRate)

    /** The same grid at another tempo (the ×2 and ÷2 buttons), keeping its first beat. */
    fun scaled(factor: Double, sampleRate: Int): BeatGrid {
        val bpm2 = bpm * factor
        val len = DjMath.beatFrames(bpm2, sampleRate)
        return copy(bpm = bpm2, firstBeat = firstBeat - floor(firstBeat / len) * len)
    }
}

/**
 * Finds a track's tempo and beat grid while it decodes.
 *
 * Two passes over one onset envelope:
 *  - the app's [TempoEstimator] (autocorrelation with a 120 BPM prior) reads
 *    the tempo every few seconds of audio, and the median of its readings is
 *    the coarse tempo;
 *  - the whole track's onset envelope (the rises of a bass-weighted level,
 *    200 steps a second) is then folded at every tempo within ±2 % of it,
 *    0.01 BPM apart, and of half and double it. The fold is one Fourier
 *    coefficient, Σ e[n]·exp(-iωn): its size says how strongly the onsets
 *    line up on that grid, and its angle says where the beats fall. The
 *    strongest tempo wins; its angle gives the first beat.
 *
 * Half and double the coarse tempo are tried because an estimator with a
 * 120 BPM prior hears drum and bass at 87: folded at 87, a kick on every 174
 * BPM beat lands half a turn from the one before and cancels, so the fold
 * itself tells the octaves apart. Only tempos in [MIN_BPM]..[MAX_BPM] are
 * tried (the octave a DJ counts in), and the coarse tempo's own octave is
 * kept unless another scores [OCTAVE_MARGIN] times better.
 *
 * The level is a peak follower (instant rise, [RELEASE_SECONDS] fall), not
 * the energy of each 5 ms step: a 55 Hz kick is longer than a step, so step
 * energies wobble with the wave, and every wobble upwards would count as an
 * onset a few steps after the real one and pull the grid late.
 *
 * A grid this way is constant-tempo, which is right for the electronic
 * music DJ software is mostly asked to mix. A tempo within 0.05 of a whole
 * number that scores nearly as well is snapped to it, since that is what the
 * producer set.
 */
class BeatAnalyzer(private val sampleRate: Int) {

    private val tempo = TempoEstimator(sampleRate)
    private val hop = max(1, (sampleRate / ENVELOPE_HZ.toFloat()).roundToInt())
    private val envelopeHz = sampleRate.toDouble() / hop

    private val bass = Biquad().apply { setLowPass(sampleRate.toFloat(), BASS_HZ, 0.70710678f) }
    private val release = exp(-1.0 / (RELEASE_SECONDS * sampleRate)).toFloat()
    private var hopCount = 0
    private var bassLevel = 0f
    private var fullLevel = 0f
    // The track starts from silence, so a beat on its first frame is an onset.
    private var prevBassLog = 0.0
    private var prevFullLog = 0.0

    private var envelope = FloatArray(1 shl 14)
    private var envelopeLength = 0
    private val readings = ArrayList<Float>()
    private var samplesSinceReading = 0L
    private var samples = 0L

    /** The first frame louder than about -40 dBFS, or -1 while there has been none. */
    var firstSoundFrame: Long = -1L
        private set

    /** Seconds of audio fed so far. */
    val seconds: Double get() = samples.toDouble() / sampleRate

    /** Feeds [count] mono samples, in order from the start of the track. */
    fun feed(mono: FloatArray, count: Int) {
        tempo.feed(mono, count)
        for (i in 0 until count) {
            val x = mono[i]
            if (firstSoundFrame < 0 && abs(x) > SOUND_THRESHOLD) firstSoundFrame = samples + i
            val b = bass.process(x)
            bassLevel = max(b * b, bassLevel * release)
            fullLevel = max(x * x, fullLevel * release)
            if (++hopCount == hop) {
                pushHop()
                hopCount = 0
            }
        }
        samples += count
        samplesSinceReading += count
        if (samplesSinceReading >= READING_EVERY_SECONDS * sampleRate) {
            samplesSinceReading = 0
            if (tempo.seconds >= TempoEstimator.MIN_SECONDS) tempo.estimate()?.let { readings.add(it) }
        }
    }

    private fun pushHop() {
        val bassLog = ln(1.0 + COMPRESSION * bassLevel)
        val fullLog = ln(1.0 + COMPRESSION * fullLevel)
        val flux = max(0.0, bassLog - prevBassLog) + FULL_WEIGHT * max(0.0, fullLog - prevFullLog)
        prevBassLog = bassLog
        prevFullLog = fullLog
        if (envelopeLength == envelope.size) envelope = envelope.copyOf(envelope.size * 2)
        envelope[envelopeLength++] = flux.toFloat()
    }

    /** The coarse tempo so far: the median reading, or null before there is one. */
    fun coarseBpm(): Double? {
        if (readings.isEmpty()) {
            // A short track: one reading from whatever there is.
            if (tempo.seconds < TempoEstimator.MIN_SECONDS) return null
            return tempo.estimate()?.toDouble()
        }
        val sorted = readings.sorted()
        return sorted[sorted.size / 2].toDouble()
    }

    /** The grid from everything fed so far, or null when there is not enough to tell. */
    fun analyze(): BeatGrid? {
        val coarse = coarseBpm() ?: return null
        return pickOctave(envelope, envelopeLength, envelopeHz, coarse)?.let { (bpm, phase, score) ->
            val beatFrames = DjMath.beatFrames(bpm, sampleRate)
            BeatGrid(bpm, phase * beatFrames, score)
        }
    }

    companion object {
        const val ENVELOPE_HZ = 200
        private const val BASS_HZ = 150f
        private const val FULL_WEIGHT = 0.5
        private const val COMPRESSION = 1000.0
        /** The level follower's fall: longer than a kick's cycle, shorter than the gap between beats. */
        private const val RELEASE_SECONDS = 0.05
        private const val READING_EVERY_SECONDS = 5
        private const val SOUND_THRESHOLD = 0.01f

        /** How far either side of the coarse tempo the fold searches, and in what steps. */
        private const val SEARCH_SPAN = 0.02
        private const val SEARCH_STEP_BPM = 0.01
        private const val SNAP_DISTANCE = 0.05
        private const val SNAP_SCORE = 0.97
        const val MIN_BPM = 70.0
        const val MAX_BPM = 180.0
        private const val OCTAVE_MARGIN = 1.5

        /**
         * [refine] at the coarse tempo and at half and double it, within
         * [MIN_BPM]..[MAX_BPM]: the coarse octave unless another scores
         * [OCTAVE_MARGIN] times better.
         */
        internal fun pickOctave(e: FloatArray, n: Int, rate: Double, coarse: Double): Triple<Double, Double, Float>? {
            val inRange = listOf(coarse, coarse / 2, coarse * 2).filter { it in MIN_BPM..MAX_BPM }
            if (inRange.isEmpty()) return refine(e, n, rate, coarse)
            var best: Triple<Double, Double, Float>? = null
            for (c in inRange) {
                val r = refine(e, n, rate, c) ?: continue
                if (best == null || r.third >= best.third * OCTAVE_MARGIN) best = r
            }
            return best
        }

        /**
         * The fold: the best tempo near [coarse] for envelope [e] (the first
         * [n] steps, [rate] steps a second), the beat phase (0..1, where the
         * first beat falls as a fraction of a beat) and the normalised score.
         */
        internal fun refine(e: FloatArray, n: Int, rate: Double, coarse: Double): Triple<Double, Double, Float>? {
            if (n < rate * 4) return null
            var mean = 0.0
            for (i in 0 until n) mean += e[i]
            mean /= n
            var total = 0.0
            for (i in 0 until n) total += abs(e[i] - mean)
            if (total <= 0.0) return null

            fun scoreAt(bpm: Double): Double {
                val s = fold(e, n, rate, bpm, mean)
                return sqrt(s[0] * s[0] + s[1] * s[1])
            }
            var bestBpm = coarse
            var bestScore = -1.0
            var bpm = coarse * (1 - SEARCH_SPAN)
            val end = coarse * (1 + SEARCH_SPAN)
            while (bpm <= end) {
                val score = scoreAt(bpm)
                if (score > bestScore) { bestScore = score; bestBpm = bpm }
                bpm += SEARCH_STEP_BPM
            }
            // Between the steps: the peak of the parabola through the best and its neighbours.
            val below = scoreAt(bestBpm - SEARCH_STEP_BPM)
            val above = scoreAt(bestBpm + SEARCH_STEP_BPM)
            val curve = below - 2 * bestScore + above
            if (curve < 0.0) {
                val shift = (0.5 * (below - above) / curve).coerceIn(-0.5, 0.5)
                bestBpm += shift * SEARCH_STEP_BPM
                bestScore = scoreAt(bestBpm).coerceAtLeast(bestScore)
            }
            val whole = Math.round(bestBpm).toDouble()
            if (abs(bestBpm - whole) <= SNAP_DISTANCE) {
                val s = fold(e, n, rate, whole, mean)
                if (sqrt(s[0] * s[0] + s[1] * s[1]) >= SNAP_SCORE * bestScore) bestBpm = whole
            }
            val s = fold(e, n, rate, bestBpm, mean)
            // Σ e·exp(-iωn) peaks with angle -2π·phase when the onsets sit at
            // n = (phase + k)·period.
            var phase = -atan2(s[1], s[0]) / (2 * PI)
            phase -= floor(phase)
            val score = (sqrt(s[0] * s[0] + s[1] * s[1]) / total).toFloat()
            return Triple(bestBpm, phase, score)
        }

        /** Σ (e[k] - mean)·exp(-iωk) at [bpm]: [re, im]. A rotating phasor, renormalised now and then. */
        private fun fold(e: FloatArray, n: Int, rate: Double, bpm: Double, mean: Double): DoubleArray {
            val w = 2 * PI * bpm / 60.0 / rate
            val cw = cos(w)
            val sw = sin(w)
            var c = 1.0
            var s = 0.0
            var re = 0.0
            var im = 0.0
            for (k in 0 until n) {
                val v = e[k] - mean
                re += v * c
                im -= v * s
                val c2 = c * cw - s * sw
                s = s * cw + c * sw
                c = c2
                if ((k and 1023) == 1023) {
                    val m = sqrt(c * c + s * s)
                    c /= m
                    s /= m
                }
            }
            return doubleArrayOf(re, im)
        }
    }
}
