package tf.monochrome.desktop.dj

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The deck waveforms' geometry: which frames of the track each pixel column
 * shows, how loud each column is, where the beat and bar lines fall, and a
 * playhead that moves on every screen frame rather than every audio block.
 * Pure, like [DjMath], so the tests can hold all of it; the drawing is
 * ui/dj/DeckWaveform.kt.
 *
 * Units: positions are frames at the track's own rate, as in [DjMath]; x is
 * pixels from the view's left edge; a beat index counts beats from bar 1's
 * downbeat ([barAnchor]), negative before it.
 */
object DeckWave {

    /** Beats in a bar. The analyzer finds the beats, not the metre; dance music is in four. */
    const val BEATS_PER_BAR = 4

    /** The most grid lines [forEachBeat] draws in one call; past it the caller asked for a smear. */
    const val MAX_GRID_LINES = 4096

    /**
     * The zoom: frames of the track under one pixel when [secondsVisible]
     * seconds of listening fill [widthPx]. It grows with [speed], as Mixxx's
     * does, so two decks at one tempo draw their beats the same distance
     * apart whatever tempo each track was recorded at, and a mix can be
     * read by eye.
     */
    fun framesPerPx(speed: Double, sampleRate: Int, secondsVisible: Double, widthPx: Float): Double =
        speed.coerceAtLeast(MIN_SPEED) * sampleRate * secondsVisible / widthPx.coerceAtLeast(1f)

    /** The frame under [x], in a view that has [position] at [playheadX]. */
    fun frameAt(x: Float, position: Double, playheadX: Float, framesPerPx: Double): Double =
        position + (x - playheadX) * framesPerPx

    /** Where [frame] is drawn, in a view that has [position] at [playheadX]. */
    fun xOf(frame: Double, position: Double, playheadX: Float, framesPerPx: Double): Float =
        (playheadX + (frame - position) / framesPerPx).toFloat()

    /**
     * The first column's frame for a view that starts at [frame], snapped to
     * whole columns of the track rather than of the screen. Each column then
     * keeps the same slice of audio while the view scrolls and only its x
     * moves; slices that slid with the screen would change their peaks every
     * frame, and the waveform would shimmer.
     */
    fun alignedStart(frame: Double, framesPerPx: Double): Double = floor(frame / framesPerPx) * framesPerPx

    /**
     * The level of band [band] (0 low, 1 mid, 2 high) in each of [out]'s
     * columns, the first starting at [firstFrame] and each [framesPerPx]
     * wide; 0 where nothing is decoded.
     *
     * A column wider than a waveform bin shows the loudest bin it covers, so
     * a kick is never averaged away when zoomed out (Mixxx's waveforms do the
     * same). A narrower one reads between the two nearest bins, so a
     * zoomed-in waveform slopes instead of standing in steps.
     */
    fun columnPeaks(track: DeckTrack, band: Int, firstFrame: Double, framesPerPx: Double, out: FloatArray) {
        val binFrames = track.binFrames.toDouble()
        val binsPerColumn = framesPerPx / binFrames
        val lastBin = track.waveBins - 1.0
        for (i in out.indices) {
            val start = (firstFrame + i * framesPerPx) / binFrames
            val end = start + binsPerColumn
            out[i] = if (binsPerColumn >= 1.0) {
                var peak = 0f
                var bin = floor(start).coerceAtLeast(0.0).toInt()
                val last = (ceil(end) - 1.0).coerceAtMost(lastBin).toInt()
                while (bin <= last) {
                    peak = max(peak, track.wavePeak(band, bin))
                    bin++
                }
                peak
            } else {
                // Bin b stands for the middle of its frames, b + 0.5.
                val at = (start + end) * 0.5 - 0.5
                val below = floor(at)
                val f = (at - below).toFloat()
                val bin = below.toInt()
                track.wavePeak(band, bin) * (1f - f) + track.wavePeak(band, bin + 1) * f
            }
        }
    }

    /**
     * How tall a band's waveform stands under an EQ knob at [knob]: √ of the
     * knob's gain, the peaks being stored on a square-root scale. A killed
     * band disappears from the waveform, as on Mixxx's, so the screen shows
     * what the mix lets through.
     */
    fun eqScale(knob: Float): Float = sqrt(DjMath.eqGain(knob))

    // ── The grid ────────────────────────────────────────────────────────

    /**
     * The beat counted as bar 1's downbeat. The analyzer finds where the
     * beats fall but not which of them starts a bar, so this takes the beat
     * nearest the track's first sound: most dance music starts on the one,
     * and it is where the deck puts the first cue. Before the first sound is
     * found, the grid's own first beat.
     */
    fun barAnchor(grid: BeatGrid, firstSoundFrame: Long, sampleRate: Int): Double =
        if (firstSoundFrame < 0) {
            grid.firstBeat
        } else {
            DjMath.nearestBeat(firstSoundFrame.toDouble(), grid.firstBeat, grid.beatFrames(sampleRate))
        }

    /** The beat [position] is in, counted from [anchor]: 0 is bar 1's downbeat. */
    fun beatIndex(position: Double, anchor: Double, beatFrames: Double): Long =
        floor((position - anchor) / beatFrames + 1e-9).toLong()

    /** Whether beat [beat] starts a bar. */
    fun isDownbeat(beat: Long): Boolean = Math.floorMod(beat, BEATS_PER_BAR.toLong()) == 0L

    /** The bar beat [beat] is in, counting from 1; 0 and below before bar 1. */
    fun barOf(beat: Long): Long = Math.floorDiv(beat, BEATS_PER_BAR.toLong()) + 1

    /** Beat [beat]'s place in its bar, 1..[BEATS_PER_BAR]. */
    fun beatInBar(beat: Long): Int = Math.floorMod(beat, BEATS_PER_BAR.toLong()).toInt() + 1

    /**
     * How many beats apart to draw grid lines that are [beatPx] pixels per
     * beat, so no two stand closer than [minPx]: every beat, every bar, every
     * 4 bars or every 16. 0 when even that is too close, or there is no beat.
     */
    fun lineStride(beatPx: Float, minPx: Float): Int {
        if (!(beatPx > 0f) || beatPx.isInfinite()) return 0
        for (stride in STRIDES) if (beatPx * stride >= minPx) return stride
        return 0
    }

    /**
     * Calls [line] with the frame and index of every [stride]th beat from
     * [from] to [to], counted from [anchor] so that a stride of a bar or more
     * lands on downbeats.
     */
    inline fun forEachBeat(
        anchor: Double,
        beatFrames: Double,
        from: Double,
        to: Double,
        stride: Int,
        line: (frame: Double, beat: Long) -> Unit,
    ) {
        if (stride <= 0 || !(beatFrames > 0.0) || !from.isFinite() || !to.isFinite() || !anchor.isFinite()) return
        val span = beatFrames * stride
        if ((to - from) / span > MAX_GRID_LINES) return
        var beat = ceil((from - anchor) / span - 1e-9).toLong() * stride
        while (true) {
            val frame = anchor + beat * beatFrames
            if (frame > to) return
            line(frame, beat)
            beat += stride
        }
    }

    // ── The playhead ────────────────────────────────────────────────────

    /**
     * The position a waveform draws. The audio thread moves a deck's
     * playhead once an output block (about 10 ms) and the screen reads it
     * once a frame (about 16 ms), so drawn as read the waveform would step a
     * block one frame and two the next, and judder. This keeps a clock of
     * its own at the deck's speed and pulls it a little toward each reading,
     * as Mixxx's VisualPlayPosition does. A jump (a seek, a cue, a loop's
     * wrap), a stopped or held deck, or a long pause between frames takes
     * the reading as it is.
     */
    class Playhead {
        /** The position last returned; NaN before the first [update]. */
        var position: Double = Double.NaN
            private set

        /**
         * The position to draw [dtSeconds] after the last call, for a deck
         * last read at [measured] and moving at [framesPerSecond] (speed ×
         * the track's sample rate). [moving] is false while the deck is
         * stopped or a hand holds it; the reading is then drawn as it is.
         */
        fun update(measured: Double, moving: Boolean, framesPerSecond: Double, dtSeconds: Double): Double {
            val last = position
            val dt = dtSeconds.coerceAtLeast(0.0)
            position = if (!moving || last.isNaN() || dt > MAX_GAP_SECONDS) {
                measured
            } else {
                val predicted = last + framesPerSecond * dt
                val error = measured - predicted
                if (abs(error) > SNAP_SECONDS * framesPerSecond) measured
                else predicted + error * (1.0 - exp(-dt / SMOOTH_SECONDS))
            }
            return position
        }
    }

    private val STRIDES = intArrayOf(1, BEATS_PER_BAR, 4 * BEATS_PER_BAR, 16 * BEATS_PER_BAR)

    private const val MIN_SPEED = 0.01

    /** How quickly a [Playhead] closes on the reading: a time constant, seconds. */
    private const val SMOOTH_SECONDS = 0.1

    /** A [Playhead] further than this from the reading jumps to it: that is a seek, not drift. */
    private const val SNAP_SECONDS = 0.15

    /** A frame this long after the one before takes the reading as it is. */
    private const val MAX_GAP_SECONDS = 0.25
}
