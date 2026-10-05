package tf.monochrome.desktop.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A deck's transport on a synthetic track: play, tempo, cue, hot cues,
 * loops, jumps, the jog wheel and sync. The track is a slow ramp, so the
 * sample a deck plays says where it is.
 */
class DeckTest {

    private val sr = 48_000
    private val block = 480
    private val l = FloatArray(block)
    private val r = FloatArray(block)

    /** 120 BPM: a beat is 24 000 frames. */
    private val beat = 24_000.0

    private fun track(seconds: Int = 60, bpm: Double = 120.0): DeckTrack {
        val n = seconds * sr
        val ramp = FloatArray(n) { 0.1f + 0.8f * it / n }
        return DeckTrack.fromSamples(ramp, ramp.copyOf(), sr).also { it.grid = BeatGrid(bpm, 0.0, 1f) }
    }

    private fun deck(t: DeckTrack = track()): Deck = Deck(0).apply {
        quantize = false
        attach()
        load(t)
        render(l, r, block, sr, 1f)
    }

    private fun Deck.run(blocks: Int) = repeat(blocks) { render(l, r, block, sr, 1f) }

    @Test
    fun playsAtTheTempoFadersSpeed() {
        val d = deck()
        d.play()
        d.run(100)
        assertEquals(100.0 * block, d.playPosition, 1.0)
        d.tempoRange = 0.08f
        d.setTempoFader(1f)
        assertEquals(1.08, d.speed, 1e-6)          // the range is a Float: 1.0799999982
        val from = d.playPosition
        d.run(100)
        // The new speed glides in across the first block (no step in pitch): half a block's worth of the change is lost.
        assertEquals(100.0 * block * 1.08 - 0.5 * 0.08 * block, d.playPosition - from, 2.0)
        assertEquals(120.0 * 1.08, d.bpm, 1e-4)
    }

    @Test
    fun outputIsTheTrackOnceTheGatesOpen() {
        val t = track()
        val d = deck(t)
        d.play()
        d.run(10)
        val at = d.playPosition.toLong()
        d.render(l, r, block, sr, 1f)
        // The ramp read back where the deck says it is, through a flat strip at unity.
        assertEquals(t.sample(at, 0), l[0], 1e-3f)
    }

    @Test
    fun cueSetsReturnsAndPreviews() {
        val d = deck()
        d.play()
        d.run(50)
        d.pause()
        d.run(5)
        val here = d.playPosition
        d.cueDown()
        d.cueUp()
        d.run(1)
        assertEquals(here, d.cuePoint, 1.0)
        d.play()
        d.run(50)
        // Cue while playing: back to the cue point, stopped.
        d.cueDown()
        d.run(5)
        d.cueUp()
        assertFalse(d.playing)
        assertEquals(here, d.playPosition, 1.0)
        // Held at the cue point: plays while held, back to the cue on release.
        d.cueDown()
        d.run(20)
        assertTrue(d.playing)
        assertTrue(d.playPosition > here + 1000)
        d.cueUp()
        d.run(5)
        assertFalse(d.playing)
        assertEquals(here, d.playPosition, 1.0)
        // Play while previewing latches it.
        d.cueDown()
        d.run(5)
        d.play()
        d.cueUp()
        d.run(5)
        assertTrue(d.playing)
    }

    @Test
    fun hotCuesSetThenJump() {
        val d = deck()
        d.play()
        d.run(100)
        d.hotCue(0)
        d.run(1)
        val cue = d.hotCues[0]
        assertFalse(cue.isNaN())
        d.run(100)
        d.hotCue(0)
        d.run(1)
        assertEquals(cue + block, d.playPosition, 2.0)
        d.clearHotCue(0)
        d.run(1)
        assertTrue(d.hotCues[0].isNaN())
    }

    @Test
    fun quantizedHotCueKeepsThePhase() {
        val d = deck()
        d.quantize = true
        d.play()
        d.run(110)                       // 52 800: 2.2 beats in
        d.hotCue(1)                      // set on the nearest beat: 48 000
        d.run(1)
        assertEquals(2 * beat, d.hotCues[1], 0.0)
        d.run(200)
        val phaseBefore = DjMath.beatPhase(d.playPosition, 0.0, beat)
        d.hotCue(1)
        d.run(1)
        val phaseAfter = DjMath.beatPhase(d.playPosition, 0.0, beat)
        assertEquals(phaseBefore, phaseAfter, 0.03)
        assertTrue(abs(d.playPosition - 2 * beat) < beat)
    }

    @Test
    fun autoLoopHoldsThePlayheadAndResizes() {
        val d = deck()
        d.quantize = true
        d.play()
        d.run(60)                        // 28 800, in beat 1
        d.autoLoop(1.0)
        d.run(1)
        assertTrue(d.loopActive)
        assertEquals(beat, d.loopIn, 0.0)
        assertEquals(2 * beat, d.loopOut, 0.0)
        repeat(400) {
            d.render(l, r, block, sr, 1f)
            assertTrue(d.playPosition >= beat - 1 && d.playPosition < 2 * beat + 1)
        }
        d.resizeLoop(-1)
        d.run(1)
        assertEquals(0.5, d.loopBeats, 0.0)
        assertEquals(1.5 * beat, d.loopOut, 0.0)
        repeat(200) {
            d.render(l, r, block, sr, 1f)
            assertTrue(d.playPosition < 1.5 * beat + 1)
        }
        // Out of the loop, the record plays on.
        d.reloop()
        d.run(200)
        assertTrue(d.playPosition > 2 * beat)
    }

    @Test
    fun beatJumpMovesTheLoopToo() {
        val d = deck()
        d.quantize = true
        d.autoLoop(4.0)
        d.run(1)
        d.beatJump(8.0)
        d.run(1)
        assertEquals(8 * beat, d.loopIn, 0.0)
        assertEquals(12 * beat, d.loopOut, 0.0)
        assertEquals(8 * beat, d.playPosition, 1.0)
    }

    @Test
    fun scratchFollowsTheJog() {
        val d = deck()
        d.play()
        d.run(100)
        val from = d.playPosition
        d.jogTouched = true
        d.run(1)
        d.jog(0.25)                      // a quarter turn: 0.45 s of record
        d.run(40)
        val moved = d.playPosition - from
        assertEquals(0.25 * Deck.SCRATCH_SECONDS_PER_REV * sr, moved, 0.03 * 0.25 * Deck.SCRATCH_SECONDS_PER_REV * sr)
        // Held still, the record stops.
        val held = d.playPosition
        d.run(20)
        assertEquals(held, d.playPosition, 50.0)
        // Let go: the motor takes it back up to speed.
        d.jogTouched = false
        d.run(40)
        val p = d.playPosition
        d.run(10)
        assertEquals(10.0 * block, d.playPosition - p, 5.0)
    }

    /** Two decks rendered as the engine renders them: A, then B, every block. */
    private fun lockstep(a: Deck, b: Deck, blocks: Int) = repeat(blocks) {
        a.render(l, r, block, sr, 1f)
        b.render(l, r, block, sr, 1f)
    }

    /** How far B's beat is from A's, in beats, with [bfA] and [bfB] each deck's beat at speed 1. */
    private fun phaseGap(a: Deck, b: Deck, bfA: Double, bfB: Double): Double =
        DjMath.phaseDelta(DjMath.beatPhase(a.playPosition, 0.0, bfA), DjMath.beatPhase(b.playPosition, 0.0, bfB))

    private fun pair(): Pair<Deck, Deck> {
        val a = deck(track(bpm = 128.0))
        val b = deck(track(bpm = 125.0))
        a.peer = b
        b.peer = a
        return a to b
    }

    @Test
    fun syncMatchesTempoAndPhase() {
        val (a, b) = pair()
        val bfA = DjMath.beatFrames(128.0, sr)
        val bfB = DjMath.beatFrames(125.0, sr)
        a.play()
        lockstep(a, b, 37)
        // B starts at 0 while A is 0.79 beats in: sync jumps B back 0.21 of a beat, before its first frame.
        b.play()
        lockstep(a, b, 1)
        b.sync()
        lockstep(a, b, 1)
        assertEquals(128.0 / 125.0, b.speed, 1e-9)
        assertEquals(128.0, b.bpm, 1e-6)
        assertTrue("sync used the pre-roll", b.playPosition < 0.0)
        lockstep(a, b, 50)
        assertEquals(0.0, phaseGap(a, b, bfA, bfB), 0.002)
        // Out of the pre-roll, into the track, still in phase.
        lockstep(a, b, 200)
        assertTrue(b.playPosition > 0.0)
        assertEquals(0.0, phaseGap(a, b, bfA, bfB), 0.002)
    }

    @Test
    fun syncLockFollowsTheLeader() {
        val (a, b) = pair()
        b.syncLock = true
        a.setTempoFader(0.5f)            // 128 · 1.04
        lockstep(a, b, 1)
        assertEquals(128.0 * 1.04, b.bpm, 1e-6)
    }

    @Test
    fun syncLockPullsTheBeatsTogether() {
        val (a, b) = pair()
        val bfA = DjMath.beatFrames(128.0, sr)
        val bfB = DjMath.beatFrames(125.0, sr)
        b.syncLock = true
        a.play()
        b.play()                         // in phase from the start: play with sync lock syncs
        lockstep(a, b, 50)
        assertEquals(0.0, phaseGap(a, b, bfA, bfB), 0.002)
        // Knock B a twentieth of a beat out: the lock's speed trim pulls it back.
        b.beatJump(0.05)
        lockstep(a, b, 1)
        assertTrue("the jump moved B", abs(phaseGap(a, b, bfA, bfB)) > 0.04)
        lockstep(a, b, 600)
        assertEquals(0.0, phaseGap(a, b, bfA, bfB), 0.003)
    }

    @Test
    fun beatJumpBeforeTheStartKeepsCounting() {
        val d = deck()
        d.play()
        d.run(25)                        // 12 000: half a beat in
        d.beatJump(-4.0)
        d.run(1)
        assertEquals(12_000.0 - 4 * beat + block, d.playPosition, 1.0)
        // Silence until the first frame, then the record, still on the same beat phase.
        d.run(200)
        assertEquals(12_000.0 - 4 * beat + 201 * block, d.playPosition, 2.0)
        d.run(4 * 50)
        assertTrue(d.playPosition > 0.0)
        d.render(l, r, block, sr, 1f)
        assertTrue("plays the track once past the first frame", l[0] > 0f)
    }

    @Test
    fun stopsAtTheEnd() {
        val d = deck(track(seconds = 2))
        d.play()
        d.run(250)
        assertFalse(d.playing)
        d.play()
        d.run(1)
        assertFalse("play at the end does nothing", d.playing)
    }
}
