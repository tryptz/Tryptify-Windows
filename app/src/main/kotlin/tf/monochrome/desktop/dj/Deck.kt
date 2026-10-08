package tf.monochrome.desktop.dj

import tf.monochrome.desktop.audio.stretch.PitchEngine
import tf.monochrome.desktop.audio.stretch.PitchQuality
import tf.monochrome.desktop.audio.stretch.StretchNative
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One deck of the DJ console: a [DeckTrack] played at any speed, from any
 * point, with cue, hot cues, loops, beat jumps, a jog wheel, keylock and
 * sync, through its own channel strip (trim, three-band EQ, filter, fader).
 *
 * Threads: the controls below are safe from any thread. Buttons are queued
 * as commands and run on the audio thread at the start of the next block, so
 * the playback state has one writer and needs no locks while it plays.
 * Knobs and faders are plain volatile fields read once a block. While the
 * deck is not attached to the output ([attach]), commands run at once on the
 * caller's thread instead, so a deck can be loaded and cued before the
 * console takes the output.
 */
class Deck(val index: Int) {

    // ── Continuous controls (any thread) ───────────────────────────────

    /** Channel fader, 0..1. */
    @Volatile var volume: Float = 1f
    /** Trim (gain) knob, 0..1, 0.5 = unity. */
    @Volatile var trim: Float = 0.5f
    /** EQ knobs, 0..1, 0.5 = flat. */
    @Volatile var eqLow: Float = 0.5f
    @Volatile var eqMid: Float = 0.5f
    @Volatile var eqHigh: Float = 0.5f
    /** Filter knob, -1 (low-pass) .. 1 (high-pass), 0 = off. */
    @Volatile var filter: Float = 0f
    /** Keep the key when the tempo moves. */
    @Volatile var keylock: Boolean = false
    /** Snap cues, loops and jumps to the beat grid. */
    @Volatile var quantize: Boolean = true
    /** The tempo fader's range, ± as a fraction ([DjMath.TEMPO_RANGES]). */
    @Volatile var tempoRange: Float = DjMath.DEFAULT_TEMPO_RANGE
    /** A finger on the jog wheel's platter: the record follows the wheel. */
    @Volatile var jogTouched: Boolean = false
    /** Momentary speed offset from the nudge buttons (pitch bend), as a fraction. */
    @Volatile var nudge: Float = 0f
    /** Sync lock: follows the other deck's tempo and holds the beats in phase. */
    @Volatile var syncLock: Boolean = false
    /** With [syncLock] on both decks, the one whose tempo the other follows. */
    @Volatile var isLeader: Boolean = false

    /** Playback speed set by the tempo fader or sync (1 = as recorded). */
    @Volatile var speed: Double = 1.0
        private set

    /** The tempo fader's position for [speed] over [tempoRange], -1..1. */
    val tempoFader: Float get() = DjMath.faderFor(speed, tempoRange)

    /** Called (on the caller's thread) when a person changes this deck's tempo. */
    @Volatile var onUserTempo: ((Deck) -> Unit)? = null

    /** The other deck: the one sync follows. */
    @Volatile var peer: Deck? = null

    /**
     * Called (on the caller's thread) when the deck starts playing while no
     * output pulls it ([attach]): a controller's PLAY pressed before the
     * console took the output, which would otherwise play in silence.
     */
    @Volatile var onDetachedStart: ((Deck) -> Unit)? = null

    fun setTempoFader(position: Float) = setSpeed(DjMath.speedFor(position, tempoRange))

    fun setSpeed(value: Double) {
        speed = value.coerceIn(MIN_SPEED, MAX_SPEED)
        onUserTempo?.invoke(this)
    }

    /** Jog wheel movement, in revolutions (+ is clockwise). Accumulates until the next block. */
    fun jog(revolutions: Double) {
        jogMicroRevs.addAndGet(Math.round(revolutions * 1e6))
    }

    private val jogMicroRevs = AtomicLong()

    // ── State, for display (written on the audio thread) ───────────────

    @Volatile var track: DeckTrack? = null
        private set
    /**
     * Where the listener is in the track, frames at its own rate. Negative
     * before the track starts: a deck synced or jumped there plays silence
     * until it reaches the first frame (pre-roll), so it stays in phase.
     */
    @Volatile var playPosition: Double = 0.0
        private set
    @Volatile var playing: Boolean = false
        private set
    @Volatile var cuePoint: Double = 0.0
        private set
    /** Hot cue positions, NaN where unset. A fresh array on every change. */
    @Volatile var hotCues: DoubleArray = DoubleArray(HOT_CUES) { Double.NaN }
        private set
    @Volatile var loopIn: Double = Double.NaN
        private set
    @Volatile var loopOut: Double = Double.NaN
        private set
    @Volatile var loopActive: Boolean = false
        private set
    /** The auto loop's size, beats. */
    @Volatile var loopBeats: Double = DjMath.DEFAULT_LOOP_BEATS
        private set
    /** The beat jump's size, beats. */
    @Volatile var jumpBeats: Double = DjMath.DEFAULT_JUMP_BEATS
        private set
    /** Pre-fader peak, 0..1+, with a falloff. */
    @Volatile var peak: Float = 0f
        private set

    /** Tempo now, from the grid and [speed]; 0 without a grid. */
    val bpm: Double get() = (track?.grid?.bpm ?: 0.0) * speed

    // ── Commands ───────────────────────────────────────────────────────

    /**
     * Held by whoever runs the playback state: the audio thread while
     * attached, the caller while not. The audio thread only ever tries it, so
     * it never waits on another thread.
     */
    private val lock = ReentrantLock()
    private val commands = ConcurrentLinkedQueue<Deck.() -> Unit>()
    @Volatile private var attached = false

    private fun post(command: Deck.() -> Unit) {
        commands.add(command)
        if (!attached) lock.withLock { if (!attached) drain() }
    }

    private fun drain() {
        while (true) {
            val c = commands.poll() ?: return
            c()
        }
    }

    /** The output starts pulling [render]: commands wait for the audio thread from now on. */
    fun attach() = lock.withLock {
        attached = true
        clock = 0L
    }

    /** The output stopped pulling: commands run at once again, and the deck stops. */
    fun detach() = lock.withLock {
        attached = false
        drain()
        stopNow()
    }

    fun load(t: DeckTrack) = post {
        track = t
        position = 0.0
        playPosition = 0.0
        playing = false
        cuePreview = false
        cuePoint = 0.0
        hot.fill(Double.NaN)
        hotCues = hot.copyOf()
        loopIn = Double.NaN
        loopOut = Double.NaN
        loopActive = false
        untouched = true
        seenGrid = null
        seenFirstSound = -2L
        step = 0.0
        gate = 0f
        fadeLeft = 0
        park = Double.NaN
        scratching = false
        jogBend = 0.0
        jogMicroRevs.set(0L)
        filters?.reset()
        keyWarm = 0
        keyMix = 0f
        stretchReset = true
        peak = 0f
    }

    /** Takes the track off the deck, unless it is playing. */
    fun eject() = post { if (!playing) { track = null; playPosition = 0.0 } }

    fun play() = post { startPlay() }

    fun pause() = post { if (playing) { playing = false; cuePreview = false } }

    fun togglePlay() = post { if (playing && !cuePreview) { playing = false } else startPlay() }

    /** The cue button pressed: CDJ cue (see [cueUp]). */
    fun cueDown() = post {
        val t = track ?: return@post
        untouched = false
        if (playing && !cuePreview) {
            // Playing: back to the cue point, stopped.
            stopAt(cuePoint)
        } else if (!playing) {
            if (abs(position - cuePoint) <= CUE_TOLERANCE) {
                // At the cue point: play while the button is held.
                playing = true
                cuePreview = true
                if (!attached) onDetachedStart?.invoke(this@Deck)
            } else {
                // Elsewhere: the cue point is here now.
                cuePoint = snap(t, position).coerceAtLeast(0.0)
                if (cuePoint != position) jumpTo(cuePoint)
            }
        }
    }

    /** The cue button released: a preview stops and returns to the cue point; one that play latched goes on. */
    fun cueUp() = post {
        if (cuePreview) {
            cuePreview = false
            stopAt(cuePoint)
        }
    }

    /** Back to the start of the track (shift + cue), playing on if it was playing. */
    fun rewind() = post { untouched = false; jumpTo(0.0) }

    /** Hot cue [slot]: set it here when empty, jump to it when set. */
    fun hotCue(slot: Int) = post {
        val t = track ?: return@post
        if (slot !in 0 until HOT_CUES) return@post
        untouched = false
        val here = heard()
        val cue = hot[slot]
        if (cue.isNaN()) {
            hot[slot] = snap(t, here).coerceAtLeast(0.0)
            hotCues = hot.copyOf()
        } else {
            val g = t.grid
            if (playing && quantize && g != null) {
                // Land on the cue's beat with the phase the deck has now, so the mix stays in time.
                val bf = g.beatFrames(t.sampleRate)
                jumpTo(position + (cue - DjMath.nearestBeat(here, g.firstBeat, bf)))
            } else {
                jumpTo(cue + (position - here))
                if (!playing) cuePoint = cue
            }
        }
    }

    fun clearHotCue(slot: Int) = post {
        if (slot !in 0 until HOT_CUES) return@post
        hot[slot] = Double.NaN
        hotCues = hot.copyOf()
    }

    fun loopInHere() = post {
        val t = track ?: return@post
        untouched = false
        loopActive = false
        loopIn = snap(t, heard())
        loopOut = Double.NaN
    }

    fun loopOutHere() = post {
        val t = track ?: return@post
        if (loopIn.isNaN()) return@post
        val out = snap(t, heard())
        if (out <= loopIn + MIN_LOOP_FRAMES) return@post
        loopOut = out
        loopActive = true
        t.grid?.let { loopBeats = DjMath.stepSize(DjMath.LOOP_SIZES, (out - loopIn) / it.beatFrames(t.sampleRate), 0) }
    }

    /** Leaves an active loop, or goes back into the last one. */
    fun reloop() = post {
        if (loopActive) {
            loopActive = false
        } else if (!loopIn.isNaN() && !loopOut.isNaN()) {
            loopActive = true
            if (position < loopIn || position >= loopOut) jumpTo(loopIn)
        }
    }

    /** A loop of [beats] from the beat at or before here (here, without quantize or a grid). */
    fun autoLoop(beats: Double = loopBeats) = post {
        val t = track ?: return@post
        untouched = false
        val bf = beatLength(t)
        val here = heard()
        val g = t.grid
        val start = if (quantize && g != null) DjMath.beatAtOrBefore(here, g.firstBeat, bf) else here
        loopIn = start.coerceAtLeast(0.0)
        loopOut = loopIn + beats * bf
        loopBeats = beats
        loopActive = true
        if (position >= loopOut) jumpTo(wrapIntoLoop(position))
    }

    /** The loop button: an auto loop of [loopBeats], or out of the loop if one is on. */
    fun toggleAutoLoop() = post { if (loopActive) loopActive = false else autoLoop(loopBeats) }

    /** Halves (-1) or doubles (+1) the loop size, and an active loop with it. */
    fun resizeLoop(steps: Int) = post {
        val next = DjMath.stepSize(DjMath.LOOP_SIZES, loopBeats, steps)
        val factor = next / loopBeats
        loopBeats = next
        if (!loopIn.isNaN() && !loopOut.isNaN()) {
            loopOut = loopIn + (loopOut - loopIn) * factor
            if (loopActive && position >= loopOut) jumpTo(wrapIntoLoop(position))
        }
    }

    fun resizeJump(steps: Int) = post { jumpBeats = DjMath.stepSize(DjMath.JUMP_SIZES, jumpBeats, steps) }

    /** Jumps [beats] beats (negative is back), carrying an active loop along. */
    fun beatJump(beats: Double) = post {
        val t = track ?: return@post
        untouched = false
        val d = beats * beatLength(t)
        if (loopActive) {
            loopIn += d
            loopOut += d
        }
        jumpTo((position + d).coerceAtLeast(minPosition(t)))
    }

    /** Jumps to [fraction] (0..1) of the track: a click on the overview. */
    fun seekFraction(fraction: Float) = post {
        val t = track ?: return@post
        untouched = false
        val target = fraction.coerceIn(0f, 1f) * t.lengthFrames.toDouble()
        if (loopActive && (target < loopIn || target >= loopOut)) loopActive = false
        if (playing) jumpTo(target) else { jumpTo(target); park = Double.NaN }
    }

    /** Matches the other deck's tempo and, when both play, its beat phase. */
    fun sync() = post { syncNow(phase = true) }

    /** The beat grid at half or double the tempo. */
    fun scaleGrid(factor: Double) = post {
        val t = track ?: return@post
        val g = t.grid ?: return@post
        t.gridLocked = true
        t.grid = g.scaled(factor, t.sampleRate)
    }

    /** Puts a beat where the listener is now, keeping the tempo. */
    fun setGridHere() = post {
        val t = track ?: return@post
        val g = t.grid ?: return@post
        val bf = g.beatFrames(t.sampleRate)
        val here = heard()
        t.gridLocked = true
        t.grid = g.copy(firstBeat = here - floor(here / bf) * bf)
    }

    /** Moves the grid by [beats] (a fraction of a beat, + is later). */
    fun shiftGrid(beats: Double) = post {
        val t = track ?: return@post
        val g = t.grid ?: return@post
        val bf = g.beatFrames(t.sampleRate)
        val first = g.firstBeat + beats * bf
        t.gridLocked = true
        t.grid = g.copy(firstBeat = first - floor(first / bf) * bf)
    }

    // ── Playback state (audio thread, or the caller's while detached) ──

    private var position = 0.0
    private var cuePreview = false
    private val hot = DoubleArray(HOT_CUES) { Double.NaN }
    /** Nothing has been done with the track since it was loaded: the cue may still move to its first beat. */
    private var untouched = true
    private var seenGrid: BeatGrid? = null
    private var seenFirstSound = -2L

    /** Track frames per output frame at the end of the last block. */
    private var step = 0.0
    /** Output gain of the play/pause gate, 0..1. */
    private var gate = 0f
    /** Where the gate parks the playhead once it has closed (a cue stops on its point). */
    private var park = Double.NaN

    private var scratching = false
    private var scratchTarget = 0.0
    private var inertia = false
    private var jogBend = 0.0
    private var phaseTrim = 0.0

    private var fadePos = 0.0
    private var fadeLeft = 0

    private fun startPlay() {
        val t = track ?: return
        if (t.frames == 0L) return
        untouched = false
        if (cuePreview) {
            // Play during a cue preview latches it: releasing cue no longer stops.
            cuePreview = false
            return
        }
        if (playing) return
        if (t.complete && position >= t.frames - 1) return
        playing = true
        park = Double.NaN
        if (syncLock) syncNow(phase = true)
        if (!attached) onDetachedStart?.invoke(this)
    }

    private fun stopAt(target: Double) {
        playing = false
        cuePreview = false
        jumpTo(target)
        park = target
    }

    private fun stopNow() {
        playing = false
        cuePreview = false
        step = 0.0
        gate = 0f
        fadeLeft = 0
        if (!park.isNaN()) { position = park; park = Double.NaN }
        playPosition = position
        scratching = false
    }

    /** Moves the playhead to [target], crossfading from where it was so the jump never clicks. */
    private fun jumpTo(target: Double) {
        if (gate > 0f) {
            fadePos = position
            fadeLeft = FADE_FRAMES
        }
        position = target
        if (!playing && gate == 0f) playPosition = target
        if (scratching) scratchTarget = target
    }

    private fun wrapIntoLoop(p: Double): Double {
        val len = loopOut - loopIn
        if (len <= 0.0) return loopIn
        val off = (p - loopIn) % len
        return loopIn + if (off < 0) off + len else off
    }

    /** Where the listener is: behind the playhead by keylock's latency. */
    private fun heard(): Double = if (keyMix > 0.5f) position - keyLatency * step else position

    /**
     * [heard] when the other deck's [clock] reads [otherClock]. Both decks
     * render in the same output callback, one after the other, so the one
     * that went first is a block ahead; comparing them as they stand would
     * hold a synced deck a block (about 10 ms) early.
     */
    private fun heardAt(otherClock: Long): Double {
        val ahead = clock - otherClock
        if (ahead == 0L || abs(ahead) > MAX_CLOCK_SKEW_FRAMES) return heard()
        return heard() - ahead * step
    }

    /** How far before the first frame the playhead may go (pre-roll). */
    private fun minPosition(t: DeckTrack): Double = -MAX_PREROLL_SECONDS * t.sampleRate

    private fun beatLength(t: DeckTrack): Double =
        t.grid?.beatFrames(t.sampleRate) ?: DjMath.beatFrames(FALLBACK_BPM, t.sampleRate)

    /** [p] on the nearest beat when quantize is on and there is a grid. */
    private fun snap(t: DeckTrack, p: Double): Double {
        val g = t.grid ?: return p
        if (!quantize) return p
        return DjMath.nearestBeat(p, g.firstBeat, g.beatFrames(t.sampleRate))
    }

    private fun syncNow(phase: Boolean) {
        val t = track ?: return
        val g = t.grid ?: return
        val p = peer ?: return
        val pt = p.track ?: return
        val pg = pt.grid ?: return
        val leaderBpm = pg.bpm * p.speed
        speed = DjMath.syncSpeed(g.bpm, leaderBpm).coerceIn(MIN_SPEED, MAX_SPEED)
        if (phase && playing && p.playing) {
            val d = phaseError(t, g, p, pt, pg)
            jumpTo((position + d * g.beatFrames(t.sampleRate)).coerceAtLeast(minPosition(t)))
        }
    }

    /** How far this deck's beat is behind the leader's, in this deck's beats (-0.5..0.5). */
    private fun phaseError(t: DeckTrack, g: BeatGrid, p: Deck, pt: DeckTrack, pg: BeatGrid): Double {
        val multiple = DjMath.syncMultiple(g.bpm, pg.bpm * p.speed)
        val leaderBeats = (p.heardAt(clock) - pg.firstBeat) / pg.beatFrames(pt.sampleRate)
        var target = leaderBeats / multiple
        target -= floor(target)
        val mine = DjMath.beatPhase(heard(), g.firstBeat, g.beatFrames(t.sampleRate))
        return DjMath.phaseDelta(mine, target)
    }

    /** Sync lock: the leader's tempo, and a small speed trim that pulls the beats back into phase. */
    private fun followSync(t: DeckTrack) {
        phaseTrim = 0.0
        if (!syncLock || isLeader) return
        val g = t.grid ?: return
        val p = peer ?: return
        val pt = p.track ?: return
        val pg = pt.grid ?: return
        speed = DjMath.syncSpeed(g.bpm, pg.bpm * p.speed).coerceIn(MIN_SPEED, MAX_SPEED)
        if (playing && p.playing && !scratching && !p.scratching) {
            val err = phaseError(t, g, p, pt, pg)
            if (abs(err) > PHASE_DEAD_BEATS) {
                // Close the gap over about PHASE_SECONDS: err beats at (bpm/60) beats a second.
                val bps = g.bpm * speed / 60.0
                phaseTrim = (err / (PHASE_SECONDS * bps)).coerceIn(-PHASE_MAX_TRIM, PHASE_MAX_TRIM)
            }
        }
    }

    /** While nothing has been done with the track, its cue sits on the first beat (or the first sound). */
    private fun placeInitialCue(t: DeckTrack) {
        if (!untouched || playing) return
        val g = t.grid
        val fs = t.firstSoundFrame
        if (g === seenGrid && fs == seenFirstSound) return
        seenGrid = g
        seenFirstSound = fs
        if (fs < 0) return
        var cue = fs.toDouble()
        if (g != null) {
            val bf = g.beatFrames(t.sampleRate)
            val beat = DjMath.nearestBeat(cue, g.firstBeat, bf)
            if (beat >= 0.0 && abs(beat - cue) <= bf * INITIAL_CUE_SNAP_BEATS) cue = beat
        }
        cuePoint = cue
        position = cue
        playPosition = cue
    }

    // ── Rendering (audio thread) ───────────────────────────────────────

    private var outRate = 0
    /** Output frames rendered since [attach]. */
    private var clock = 0L
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)
    private var filters: DeckFilters? = null
    private var stripGain = 0f
    private var trimGain = 1f

    /**
     * Renders the next [frames] frames into [outL] and [outR] (overwriting
     * them) at [rate], after the channel fader and multiplied by [xfader], the
     * crossfader's gain for this deck.
     */
    fun render(outL: FloatArray, outR: FloatArray, frames: Int, rate: Int, xfader: Float) {
        if (frames <= 0) return
        // Busy only while detaching: one block of silence, never a wait.
        if (!lock.tryLock()) {
            silence(outL, outR, frames)
            return
        }
        try {
            drain()
            val t = track
            if (t == null || !attached) silence(outL, outR, frames) else renderTrack(t, outL, outR, frames, rate, xfader)
            clock += frames
        } finally {
            lock.unlock()
        }
    }

    private fun silence(outL: FloatArray, outR: FloatArray, frames: Int) {
        outL.fill(0f, 0, frames)
        outR.fill(0f, 0, frames)
        peak *= PEAK_FALLOFF
        stripGain = 0f
    }

    private fun renderTrack(t: DeckTrack, outL: FloatArray, outR: FloatArray, frames: Int, rate: Int, xfader: Float) {
        if (rate != outRate) {
            outRate = rate
            filters = DeckFilters(rate)
            releaseStretch()
        }
        if (bufL.size < frames) {
            bufL = FloatArray(frames)
            bufR = FloatArray(frames)
        }
        placeInitialCue(t)
        followSync(t)

        val rateRatio = t.sampleRate.toDouble() / rate
        val blockSeconds = frames.toDouble() / rate
        val revs = jogMicroRevs.getAndSet(0L) * 1e-6
        val touched = jogTouched
        if (touched && !scratching) {
            scratching = true
            scratchTarget = position
            park = Double.NaN
            untouched = false
        } else if (!touched && scratching) {
            scratching = false
            inertia = true
        }
        if (scratching) {
            scratchTarget += revs * SCRATCH_SECONDS_PER_REV * t.sampleRate
        } else if (playing) {
            val target = (revs / blockSeconds) * BEND_PER_REV_PER_SECOND
            jogBend += (target.coerceIn(-MAX_BEND, MAX_BEND) - jogBend) * (1.0 - exp(-blockSeconds / BEND_SMOOTH_SECONDS))
        } else {
            jogBend = 0.0
            if (revs != 0.0) {
                untouched = false
                jumpTo((position + revs * SCRATCH_SECONDS_PER_REV * t.sampleRate).coerceIn(0.0, t.frames.toDouble()))
                park = Double.NaN
            }
        }

        // The speed this block ends at.
        val motor = speed * (1.0 + jogBend + nudge + phaseTrim) * rateRatio
        val audible = scratching || playing
        val newStep = when {
            scratching -> {
                val desired = (scratchTarget - position) / (frames * SCRATCH_LAG_BLOCKS)
                step + (desired - step) * SCRATCH_SMOOTH
            }
            playing && inertia -> {
                val s = step + (motor - step) * INERTIA_SMOOTH
                if (abs(s - motor) < motor * 0.01) { inertia = false; motor } else s
            }
            playing -> motor
            else -> { inertia = false; step }
        }
        val gateTarget = if (audible && (!scratching || abs(newStep) > STILL_STEP)) 1f else 0f
        val gateDelta = 1f / (GATE_SECONDS * rate)

        var pos = position
        // A stopped deck starts at full speed, as a CDJ does: the gate's fade is
        // the declick, and a speed ramp would start every play late.
        val s0 = if (step == 0.0 && playing && !scratching && !inertia) newStep else step
        val ds = (newStep - s0) / frames
        val loopOn = loopActive && !loopIn.isNaN() && !loopOut.isNaN() && loopOut > loopIn
        val minPos = minPosition(t)
        var stoppedAtEnd = false
        for (i in 0 until frames) {
            val s = s0 + ds * (i + 1)
            gate = if (gateTarget > gate) min(gateTarget, gate + gateDelta) else max(gateTarget, gate - gateDelta)
            if (gate <= 0f && gateTarget == 0f) {
                bufL[i] = 0f
                bufR[i] = 0f
                if (!park.isNaN()) { pos = park; park = Double.NaN }
                fadeLeft = 0
                continue
            }
            var l = interpolate(t, pos, 0)
            var r = interpolate(t, pos, 1)
            if (fadeLeft > 0) {
                val w = fadeLeft.toFloat() / FADE_FRAMES
                l = l * (1f - w) + interpolate(t, fadePos, 0) * w
                r = r * (1f - w) + interpolate(t, fadePos, 1) * w
                fadePos += s
                fadeLeft--
            }
            bufL[i] = l * gate
            bufR[i] = r * gate
            val prev = pos
            pos += s
            if (loopOn) {
                if (s > 0 && prev < loopOut && pos >= loopOut) {
                    fadePos = pos
                    fadeLeft = FADE_FRAMES
                    pos = loopIn + (pos - loopOut)
                } else if (s < 0 && prev >= loopIn && pos < loopIn) {
                    fadePos = pos
                    fadeLeft = FADE_FRAMES
                    pos = loopOut - (loopIn - pos)
                }
            }
            if (pos < minPos) pos = minPos
            if (t.complete && pos >= t.frames && !scratching && playing) {
                playing = false
                cuePreview = false
                stoppedAtEnd = true
            }
        }
        position = if (stoppedAtEnd) t.frames.toDouble() else pos
        if (scratching) {
            // A record held still stays where the hand holds it.
            if (position > t.frames) position = t.frames.toDouble()
        }
        step = if (gate <= 0f && gateTarget == 0f) 0.0 else newStep

        keylock(bufL, bufR, frames, rate, rateRatio)
        strip(t, outL, outR, frames, xfader)
        playPosition = heard()
    }

    private fun interpolate(t: DeckTrack, pos: Double, ch: Int): Float {
        val i = floor(pos).toLong()
        val f = (pos - i).toFloat()
        val xm1 = t.sample(i - 1, ch)
        val x0 = t.sample(i, ch)
        val x1 = t.sample(i + 1, ch)
        val x2 = t.sample(i + 2, ch)
        // 4-point, 3rd-order Hermite.
        val c1 = 0.5f * (x1 - xm1)
        val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
        val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
        return ((c3 * f + c2) * f + c1) * f + x0
    }

    private fun strip(t: DeckTrack, outL: FloatArray, outR: FloatArray, frames: Int, xfader: Float) {
        // Trim, gliding.
        val trim1 = DjMath.dbToGain(DjMath.trimDb(trim))
        val trim0 = trimGain
        val dt = (trim1 - trim0) / frames
        for (i in 0 until frames) {
            val g = trim0 + dt * (i + 1)
            bufL[i] *= g
            bufR[i] *= g
        }
        trimGain = trim1

        filters!!.process(
            bufL, bufR, frames,
            DjMath.eqGain(eqLow), DjMath.eqGain(eqMid), DjMath.eqGain(eqHigh),
            DjMath.filterCutoff(filter),
        )

        var p = 0f
        for (i in 0 until frames) {
            p = max(p, max(abs(bufL[i]), abs(bufR[i])))
        }
        peak = max(p, peak * PEAK_FALLOFF)

        val g1 = DjMath.faderGain(volume) * xfader
        val g0 = stripGain
        val dg = (g1 - g0) / frames
        for (i in 0 until frames) {
            val g = g0 + dg * (i + 1)
            outL[i] = bufL[i] * g
            outR[i] = bufR[i] * g
        }
        stripGain = g1
    }

    // ── Keylock ────────────────────────────────────────────────────────

    private var stretch = 0L
    private var stretchFailed = false
    private var stretchReset = false
    private var stretchIn: ByteBuffer? = null
    private var stretchOut: ByteBuffer? = null
    private var stretchMax = 0
    private var semitones = 0f
    private var keyLatency = 0
    /** Frames fed since the stretcher was reset: its output is history until this passes its latency. */
    private var keyWarm = 0
    /** How much of the output is the stretched signal, 0..1. */
    private var keyMix = 0f

    private fun keylock(l: FloatArray, r: FloatArray, frames: Int, rate: Int, rateRatio: Double) {
        val want = keylock && !stretchFailed && StretchNative.isAvailable
        if (!want) {
            if (keyMix <= 0f) { keyWarm = 0; return }
        }
        if (stretch == 0L && want) {
            stretch = StretchNative.nativeCreate(2, rate)
            if (stretch == 0L) { stretchFailed = true; return }
            StretchNative.nativeSetEngine(stretch, PitchEngine.WSOLA.nativeId, PitchQuality.BALANCED.nativeId)
            stretchMax = StretchNative.nativeMaxBlockFrames().coerceAtLeast(1)
            stretchIn = ByteBuffer.allocateDirect(stretchMax * 8).order(ByteOrder.nativeOrder())
            stretchOut = ByteBuffer.allocateDirect(stretchMax * 8).order(ByteOrder.nativeOrder())
            keyLatency = StretchNative.nativeLatencyFrames(stretch)
            semitones = Float.NaN
            keyWarm = 0
        }
        if (stretch == 0L) return
        if (stretchReset || keyWarm == 0) {
            if (StretchNative.nativeReset(stretch)) stretchReset = false
        }

        // Undo the pitch the tempo brings; while scratching, the record's own pitch is the point.
        val tempo = speed * (1.0 + nudge + phaseTrim)
        val st = DjMath.keylockSemitones(tempo)
        if (abs(st - semitones) > 0.005f || semitones.isNaN()) {
            StretchNative.nativeSetSemitones(stretch, st)
            semitones = st
        }

        val input = stretchIn!!
        val output = stretchOut!!
        val wetTarget = if (want && !scratching && keyWarm >= keyLatency) 1f else 0f
        val mixDelta = 1f / KEY_FADE_FRAMES
        var done = 0
        while (done < frames) {
            val n = min(stretchMax, frames - done)
            for (i in 0 until n) {
                input.putFloat(i * 8, l[done + i])
                input.putFloat(i * 8 + 4, r[done + i])
            }
            StretchNative.nativeProcess(stretch, input, output, n)
            for (i in 0 until n) {
                keyMix = if (wetTarget > keyMix) min(1f, keyMix + mixDelta) else max(0f, keyMix - mixDelta)
                val j = done + i
                l[j] += (output.getFloat(i * 8) - l[j]) * keyMix
                r[j] += (output.getFloat(i * 8 + 4) - r[j]) * keyMix
            }
            done += n
        }
        keyWarm = min(keyWarm + frames, Int.MAX_VALUE / 2)
        if (!want && keyMix <= 0f) keyWarm = 0
    }

    private fun releaseStretch() {
        if (stretch != 0L) StretchNative.nativeDestroy(stretch)
        stretch = 0L
        stretchIn = null
        stretchOut = null
        keyWarm = 0
        keyMix = 0f
    }

    /** Frees the keylock engine. The deck is unusable after this. */
    fun release() = lock.withLock { releaseStretch() }

    companion object {
        const val HOT_CUES = 8
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 2.5
        /** A 33⅓ rpm record: one turn of the platter is 1.8 s of audio. */
        const val SCRATCH_SECONDS_PER_REV = 1.8
        /** Spinning the jog's edge at one turn a second bends the speed this much. */
        private const val BEND_PER_REV_PER_SECOND = 0.12
        private const val MAX_BEND = 0.6
        private const val BEND_SMOOTH_SECONDS = 0.06
        /** The scratch follows the platter a couple of blocks behind, smoothed. */
        private const val SCRATCH_LAG_BLOCKS = 2.0
        private const val SCRATCH_SMOOTH = 0.5
        /** Letting go of the platter: the motor brings the record back to speed in a few blocks. */
        private const val INERTIA_SMOOTH = 0.35
        /** Below this a held record is still: silent, not a stuck sample. */
        private const val STILL_STEP = 0.004
        private const val GATE_SECONDS = 0.003f
        const val FADE_FRAMES = 128
        private const val KEY_FADE_FRAMES = 1024f
        private const val CUE_TOLERANCE = 1.0
        private const val MIN_LOOP_FRAMES = 64.0
        private const val FALLBACK_BPM = 120.0
        private const val INITIAL_CUE_SNAP_BEATS = 0.125
        private const val PHASE_DEAD_BEATS = 0.002
        private const val PHASE_SECONDS = 1.0
        private const val PHASE_MAX_TRIM = 0.03
        private const val MAX_PREROLL_SECONDS = 60.0
        /** Beyond this the decks' clocks are not from the same output: compare positions as they stand. */
        private const val MAX_CLOCK_SKEW_FRAMES = 16_384L
        /** Per 10 ms block: about 20 dB a second. */
        private const val PEAK_FALLOFF = 0.977f
    }
}
