package tf.monochrome.desktop.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The console's arithmetic: what every knob, fader and the sync button compute. */
class DjMathTest {

    @Test
    fun tempoFaderRoundTrips() {
        for (range in DjMath.TEMPO_RANGES) {
            for (f in listOf(-1f, -0.5f, 0f, 0.25f, 1f)) {
                assertEquals(f, DjMath.faderFor(DjMath.speedFor(f, range), range), 1e-5f)
            }
        }
        assertEquals(1.08, DjMath.speedFor(1f, 0.08f), 1e-6)
        // A speed outside the range pins the fader to its end.
        assertEquals(1f, DjMath.faderFor(1.5, 0.08f))
    }

    @Test
    fun keylockUndoesTheSpeedsPitch() {
        assertEquals(-12f, DjMath.keylockSemitones(2.0), 1e-4f)
        assertEquals(12f, DjMath.keylockSemitones(0.5), 1e-4f)
        assertEquals(0f, DjMath.keylockSemitones(1.0), 1e-6f)
    }

    @Test
    fun syncMatchesTempoAndCountsHalfAndDoubleTime() {
        assertEquals(128.0 / 125.0, DjMath.syncSpeed(125.0, 128.0), 1e-9)
        // 70 to 140 is a match at speed 1, two leader beats to each of the deck's.
        assertEquals(1.0, DjMath.syncSpeed(70.0, 140.0), 1e-9)
        assertEquals(2.0, DjMath.syncMultiple(70.0, 140.0), 1e-9)
        assertEquals(1.0, DjMath.syncSpeed(140.0, 70.0), 1e-9)
        assertEquals(0.5, DjMath.syncMultiple(140.0, 70.0), 1e-9)
        assertEquals(1.0, DjMath.syncSpeed(0.0, 128.0), 0.0)
    }

    @Test
    fun beatPhaseAndGrid() {
        val bf = DjMath.beatFrames(120.0, 48_000)
        assertEquals(24_000.0, bf, 1e-9)
        assertEquals(0.5, DjMath.beatPhase(1_000.0 + 12_000.0, 1_000.0, bf), 1e-9)
        assertEquals(25_000.0, DjMath.nearestBeat(20_000.0, 1_000.0, bf), 1e-9)
        assertEquals(1_000.0, DjMath.beatAtOrBefore(20_000.0, 1_000.0, bf), 1e-9)
        // A position exactly on a beat is that beat, not the one before.
        assertEquals(25_000.0, DjMath.beatAtOrBefore(25_000.0, 1_000.0, bf), 1e-9)
        assertEquals(0.2, DjMath.phaseDelta(0.9, 0.1), 1e-9)
        assertEquals(-0.2, DjMath.phaseDelta(0.1, 0.9), 1e-9)
    }

    @Test
    fun crossfaderCurves() {
        val g = FloatArray(2)
        DjMath.crossfaderGains(-1f, DjMath.CrossfaderCurve.SMOOTH, g)
        assertEquals(1f, g[0], 1e-6f); assertEquals(0f, g[1], 1e-6f)
        DjMath.crossfaderGains(0f, DjMath.CrossfaderCurve.SMOOTH, g)
        // Constant power: the two gains' squares add to one everywhere.
        assertEquals(1f / sqrt(2f), g[0], 1e-5f); assertEquals(1f / sqrt(2f), g[1], 1e-5f)
        DjMath.crossfaderGains(0f, DjMath.CrossfaderCurve.BLEND, g)
        assertEquals(1f, g[0]); assertEquals(1f, g[1])
        // A scratch cut is fully open a few percent from the far end.
        DjMath.crossfaderGains(-0.9f, DjMath.CrossfaderCurve.CUT, g)
        assertEquals(1f, g[0]); assertEquals(1f, g[1])
        DjMath.crossfaderGains(-1f, DjMath.CrossfaderCurve.CUT, g)
        assertEquals(0f, g[1])
    }

    @Test
    fun channelStripLaws() {
        assertEquals(1f, DjMath.eqGain(0.5f), 1e-6f)
        assertEquals(0f, DjMath.eqGain(0f), 0f)
        assertEquals(DjMath.dbToGain(6f), DjMath.eqGain(1f), 1e-5f)
        assertEquals(0f, DjMath.trimDb(0.5f), 0f)
        assertEquals(-24f, DjMath.trimDb(0f), 1e-5f)
        assertEquals(12f, DjMath.trimDb(1f), 1e-5f)
        assertEquals(0f, DjMath.faderGain(0f), 0f)
        assertEquals(1f, DjMath.faderGain(1f), 0f)
        assertEquals(0f, DjMath.filterCutoff(0.02f), 0f)
        assertEquals(-80f, DjMath.filterCutoff(-1f), 1e-2f)
        assertEquals(8_000f, DjMath.filterCutoff(1f), 1e-1f)
        // Just past the dead zone the filter starts wide open.
        assertTrue(DjMath.filterCutoff(-0.04f) < -15_000f)
        assertTrue(DjMath.filterCutoff(0.04f) < 25f)
    }

    @Test
    fun loopAndJumpSizes() {
        assertEquals(8.0, DjMath.stepSize(DjMath.LOOP_SIZES, 4.0, 1), 0.0)
        assertEquals(2.0, DjMath.stepSize(DjMath.LOOP_SIZES, 4.0, -1), 0.0)
        assertEquals(64.0, DjMath.stepSize(DjMath.LOOP_SIZES, 64.0, 1), 0.0)
        assertEquals(1.0 / 32, DjMath.stepSize(DjMath.LOOP_SIZES, 1.0 / 32, -1), 0.0)
        assertEquals("1/4", DjMath.beatsLabel(0.25))
        assertEquals("16", DjMath.beatsLabel(16.0))
    }

    @Test
    fun theScreensDbKnobsLandWhereTheEngineReadsThem() {
        // The screen turns its knobs in dB; the engine keeps 0..1 with unity at the centre.
        assertEquals(0.5f, DjMath.trimKnob(0f), 0f)
        assertEquals(0.5f, DjMath.eqKnob(0f), 0f)
        assertEquals(0f, DjMath.trimKnob(DjMath.TRIM_MIN_DB), 0f)
        assertEquals(1f, DjMath.trimKnob(DjMath.TRIM_MAX_DB), 0f)
        assertEquals(1f, DjMath.eqKnob(DjMath.EQ_MAX_DB), 0f)
        // All the way down is the kill, not merely -26 dB.
        assertEquals(0f, DjMath.eqGain(DjMath.eqKnob(DjMath.EQ_MIN_DB)), 0f)
        for (db in listOf(-24f, -9.5f, -0.1f, 0.1f, 3f, 12f)) {
            assertEquals(db, DjMath.trimDb(DjMath.trimKnob(db)), 1e-4f)
        }
        for (db in listOf(-26f, -12f, -0.1f, 0.1f, 6f)) {
            assertEquals(db, DjMath.eqDb(DjMath.eqKnob(db)), 1e-4f)
        }
        for (k in listOf(0.1f, 0.3f, 0.7f, 0.95f)) {
            assertEquals(DjMath.dbToGain(DjMath.eqDb(k)), DjMath.eqGain(k), 1e-5f)
        }
    }
}
