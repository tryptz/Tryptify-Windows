package tf.monochrome.desktop.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/** The deck's signal path: the EQ and filter, the beat analysis, the in-memory track. */
class DeckDspTest {

    private val sr = 48_000

    private fun sine(hz: Double, frames: Int) = FloatArray(frames) { (0.5 * sin(2 * PI * hz * it / sr)).toFloat() }

    /**
     * Level, dB, of [hz] after the EQ at [low], [mid], [high] and the filter
     * at [cutoff], once settled. RMS against the input's RMS: a sample peak
     * misses the crest of a tone near Nyquist (8 kHz at 48 kHz is six samples
     * a cycle, which reads 1.25 dB low).
     */
    private fun levelDb(hz: Double, low: Float, mid: Float, high: Float, cutoff: Float = 0f): Double {
        val f = DeckFilters(sr)
        val block = 480
        var inSum = 0.0
        var outSum = 0.0
        for (b in 0 until 200) {
            val l = FloatArray(block) { (0.5 * sin(2 * PI * hz * (b * block + it) / sr)).toFloat() }
            val x = l.copyOf()
            val r = l.copyOf()
            f.process(l, r, block, low, mid, high, cutoff)
            if (b >= 100) for (i in 0 until block) {
                inSum += x[i] * x[i]
                outSum += l[i] * l[i]
            }
        }
        return 10 * log10(outSum / inSum)
    }

    @Test
    fun flatEqIsTransparent() {
        val f = DeckFilters(sr)
        val x = sine(1_000.0, 4_800)
        val l = x.copyOf()
        val r = x.copyOf()
        f.process(l, r, l.size, 1f, 1f, 1f, 0f)
        for (i in x.indices) assertEquals(x[i], l[i], 0f)
    }

    @Test
    fun eqBandsReconstructAndKill() {
        // A band a hair off unity runs the crossover: the bands must still add back to the input's level.
        for (hz in listOf(60.0, 1_000.0, 8_000.0)) {
            assertEquals("$hz Hz near unity", 0.0, levelDb(hz, 1.0001f, 1f, 1f), 0.1)
        }
        assertTrue("low kill", levelDb(40.0, 0f, 1f, 1f) < -25.0)
        assertEquals("low kill keeps the mids", 0.0, levelDb(1_000.0, 0f, 1f, 1f), 1.0)
        assertTrue("high kill", levelDb(12_000.0, 1f, 1f, 0f) < -25.0)
        assertEquals("high kill keeps the mids", 0.0, levelDb(800.0, 1f, 1f, 0f), 1.0)
    }

    @Test
    fun filterSweepsBothWays() {
        assertTrue("low-pass at the far left", levelDb(5_000.0, 1f, 1f, 1f, DjMath.filterCutoff(-1f)) < -40.0)
        assertEquals("low-pass keeps the bass", 0.0, levelDb(40.0, 1f, 1f, 1f, DjMath.filterCutoff(-1f)), 1.5)
        assertTrue("high-pass at the far right", levelDb(100.0, 1f, 1f, 1f, DjMath.filterCutoff(1f)) < -40.0)
    }

    /** A kick (a decaying 55 Hz thump) on every beat from [offsetSeconds], over [seconds]. */
    private fun kicks(bpm: Double, offsetSeconds: Double, seconds: Double): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        val beat = 60.0 / bpm * sr
        var b = offsetSeconds * sr
        while (b < n) {
            val start = b.toInt()
            for (i in 0 until (0.15 * sr).toInt()) {
                if (start + i >= n) break
                val t = i.toDouble() / sr
                out[start + i] += (0.8 * exp(-t * 25) * sin(2 * PI * 55 * t)).toFloat()
            }
            b += beat
        }
        return out
    }

    private fun analyze(x: FloatArray): BeatGrid? {
        val a = BeatAnalyzer(sr)
        var i = 0
        while (i < x.size) {
            val n = minOf(4096, x.size - i)
            a.feed(x.copyOfRange(i, i + n), n)
            i += n
        }
        return a.analyze()
    }

    private fun assertGrid(bpm: Double, offset: Double) {
        val g = analyze(kicks(bpm, offset, 60.0)) ?: error("no grid at $bpm")
        assertEquals("tempo of $bpm", bpm, g.bpm, 0.05)
        val bf = g.beatFrames(sr)
        val expected = (offset * sr) % bf
        val err = DjMath.phaseDelta(expected / bf, g.firstBeat / bf) * bf / sr
        assertTrue("first beat of $bpm off by ${err * 1000} ms", abs(err) < 0.015)
    }

    @Test
    fun beatGridOfAKickTrack() {
        assertGrid(128.0, 0.25)
        assertGrid(123.4, 0.1)
        assertGrid(174.0, 0.37)
    }

    @Test
    fun deckTrackStoresWhatItIsGiven() {
        val l = sine(440.0, 50_000)
        val r = FloatArray(l.size) { -l[it] }
        val t = DeckTrack.fromSamples(l, r, sr)
        assertEquals(l.size.toLong(), t.frames)
        assertTrue(t.complete)
        for (i in l.indices step 97) {
            assertEquals(l[i], t.sample(i.toLong(), 0), 2f / 32768f)
            assertEquals(r[i], t.sample(i.toLong(), 1), 2f / 32768f)
        }
        assertEquals(0f, t.sample(-1, 0), 0f)
        assertEquals(0f, t.sample(l.size.toLong(), 0), 0f)
        assertEquals(l.size / t.binFrames, t.waveBins)
        // A 440 Hz tone is mostly mid band.
        val mid = t.wavePeak(1, 50)
        assertTrue(mid > t.wavePeak(0, 50) && mid > t.wavePeak(2, 50))
    }
}
