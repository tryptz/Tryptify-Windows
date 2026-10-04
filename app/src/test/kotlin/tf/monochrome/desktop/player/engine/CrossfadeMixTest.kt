package tf.monochrome.desktop.player.engine

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.desktop.player.CrossfadeRamp

/**
 * The engine's blend: when it starts, how long it runs and what the two
 * tracks sum to. The curves are CrossfadeRamp's (CrossfadeRampTest holds
 * them); these tests hold what the engine does with them per frame.
 */
class CrossfadeMixTest {

    private val rate = 48_000
    private val durationUs = 200_000_000L   // a 200 s track

    @Test
    fun `before the blend point the engine reads exactly up to it`() {
        val plan = CrossfadeMix.plan(100_000_000L, durationUs, crossfadeMs = 6_000L, speed = 1f, sampleRate = rate, incomingDurationUs = C.TIME_UNSET)
        assertEquals(CrossfadeMix.Plan.Wait(94L * rate), plan)
    }

    @Test
    fun `the blend begins one crossfade of heard time before the end`() {
        val plan = CrossfadeMix.plan(194_000_000L, durationUs, 6_000L, 1f, rate, C.TIME_UNSET)
        assertEquals(CrossfadeMix.Plan.Start(6L * rate), plan)
    }

    @Test
    fun `at 1_5x the blend covers one and a half times the media`() {
        // Six heard seconds at 1.5x are nine seconds of each track.
        assertEquals(CrossfadeMix.Plan.Wait(1L * rate), CrossfadeMix.plan(190_000_000L, durationUs, 6_000L, 1.5f, rate, C.TIME_UNSET))
        assertEquals(CrossfadeMix.Plan.Start(9L * rate), CrossfadeMix.plan(191_000_000L, durationUs, 6_000L, 1.5f, rate, C.TIME_UNSET))
    }

    @Test
    fun `a late start runs over what is left, landing on the last sample`() {
        val plan = CrossfadeMix.plan(197_000_000L, durationUs, 6_000L, 1f, rate, C.TIME_UNSET)
        assertEquals(CrossfadeMix.Plan.Start(3L * rate), plan)
    }

    @Test
    fun `too little left to blend is skipped`() {
        val left = CrossfadeRamp.MIN_BLEND_MS - 100
        val plan = CrossfadeMix.plan(durationUs - left * 1000, durationUs, 6_000L, 1f, rate, C.TIME_UNSET)
        assertEquals(CrossfadeMix.Plan.Skip, plan)
    }

    @Test
    fun `a stream with no end never blends`() {
        assertEquals(CrossfadeMix.Plan.Skip, CrossfadeMix.plan(5_000_000L, C.TIME_UNSET, 6_000L, 1f, rate, C.TIME_UNSET))
        assertEquals(CrossfadeMix.Plan.Skip, CrossfadeMix.plan(5_000_000L, 0L, 6_000L, 1f, rate, C.TIME_UNSET))
        assertEquals(CrossfadeMix.Plan.Skip, CrossfadeMix.plan(5_000_000L, durationUs, 0L, 1f, rate, C.TIME_UNSET))
    }

    @Test
    fun `a track that is all blend at its speed is not blended`() {
        // Android's rule (CrossfadeRamp.shouldPrepare): five seconds cannot hold a six-second blend.
        assertEquals(CrossfadeMix.Plan.Skip, CrossfadeMix.plan(0L, 5_000_000L, 6_000L, 1f, rate, C.TIME_UNSET))
    }

    @Test
    fun `the blend never outlasts a shorter incoming track`() {
        val plan = CrossfadeMix.plan(194_000_000L, durationUs, 6_000L, 1f, rate, incomingDurationUs = 2_000_000L)
        assertEquals(CrossfadeMix.Plan.Start(2L * rate), plan)
    }

    @Test
    fun `waiting frames reach the blend point even off the sample grid`() {
        // 44.1 kHz frames do not land on whole microseconds; rounding up keeps
        // the read from stopping a frame short and planning a one-frame wait.
        val startUs = durationUs - 6_000_000L
        for (pos in listOf(startUs - 1, startUs - 7, startUs - 22_675, 123_456_789L)) {
            val frames = (CrossfadeMix.plan(pos, durationUs, 6_000L, 1f, 44_100, C.TIME_UNSET) as CrossfadeMix.Plan.Wait).frames
            val reached = pos + frames * C.MICROS_PER_SECOND / 44_100
            assertTrue("stopped short at $pos", reached + 1 >= startUs)
            assertTrue("overshot at $pos", reached - startUs < C.MICROS_PER_SECOND / 44_100 + 1)
        }
        assertEquals(1L, CrossfadeMix.framesCeil(1L, rate))
        assertEquals(48L, CrossfadeMix.framesCeil(1_000L, rate))
    }

    @Test
    fun `the first frame of a blend is the outgoing track untouched`() {
        val incoming = floatArrayOf(0.9f, -0.4f)
        val outgoing = floatArrayOf(0.25f, -0.75f)
        CrossfadeMix.mix(incoming, outgoing, frames = 1, outgoingFrames = 1, channels = 2, startFrame = 0, totalFrames = 1000)
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), incoming, 0f)
    }

    @Test
    fun `the gains follow the equal-power curves frame by frame`() {
        val total = 480L
        val frames = total.toInt()
        // Incoming alone at full scale: what comes out is the fade-in gain.
        val inOnly = FloatArray(frames) { 1f }
        CrossfadeMix.mix(inOnly, FloatArray(frames), frames, frames, 1, 0, total)
        // Outgoing alone: the fade-out gain.
        val outOnly = FloatArray(frames)
        CrossfadeMix.mix(outOnly, FloatArray(frames) { 1f }, frames, frames, 1, 0, total)
        for (f in 0 until frames) {
            val p = CrossfadeRamp.progress(f.toLong(), total)
            assertEquals(CrossfadeRamp.fadeIn(p), inOnly[f], 1e-6f)
            assertEquals(CrossfadeRamp.fadeOut(p), outOnly[f], 1e-6f)
            // Uncorrelated tracks: their powers add, and the sum stays flat.
            assertTrue(abs(inOnly[f] * inOnly[f] + outOnly[f] * outOnly[f] - 1f) < 1e-5f)
        }
        // By the last frame the incoming track is all but at full level.
        assertTrue(inOnly[frames - 1] > 0.9999f)
    }

    @Test
    fun `a blend split across calls is the same blend`() {
        val total = 300L
        val channels = 2
        val a = FloatArray(300 * channels) { ((it * 37) % 101) / 101f - 0.5f }
        val b = FloatArray(300 * channels) { ((it * 53) % 97) / 97f - 0.5f }
        val whole = a.copyOf()
        CrossfadeMix.mix(whole, b, 300, 300, channels, 0, total)

        val split = a.copyOf()
        val first = split.copyOfRange(0, 120 * channels)
        val second = split.copyOfRange(120 * channels, 300 * channels)
        CrossfadeMix.mix(first, b.copyOfRange(0, 120 * channels), 120, 120, channels, 0, total)
        CrossfadeMix.mix(second, b.copyOfRange(120 * channels, 300 * channels), 180, 180, channels, 120, total)
        assertArrayEquals(whole, first + second, 0f)
    }

    @Test
    fun `an outgoing track that ends inside the blend is silence after its last frame`() {
        val incoming = FloatArray(10)
        val outgoing = FloatArray(10) { 1f }
        CrossfadeMix.mix(incoming, outgoing, frames = 10, outgoingFrames = 4, channels = 1, startFrame = 0, totalFrames = 100)
        for (f in 0 until 4) assertTrue(incoming[f] > 0f)
        for (f in 4 until 10) assertEquals(0f, incoming[f], 0f)
    }

    @Test
    fun `16-bit PCM survives the float round trip bit for bit`() {
        val count = 65_536
        val src = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        for (v in Short.MIN_VALUE..Short.MAX_VALUE) src.putShort(v.toShort())
        src.flip()
        val floats = FloatArray(count)
        CrossfadeMix.toFloat(src, floats, count, C.ENCODING_PCM_16BIT)
        val back = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        CrossfadeMix.fromFloat(floats, count, back, C.ENCODING_PCM_16BIT)
        back.flip()
        for (v in Short.MIN_VALUE..Short.MAX_VALUE) assertEquals(v.toShort(), back.getShort())
    }

    @Test
    fun `float PCM passes through unchanged`() {
        val values = floatArrayOf(0f, 1f, -1f, 0.123456789f, 1.75f, -3e-9f)
        val src = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { src.putFloat(it) }
        src.flip()
        val floats = FloatArray(values.size)
        CrossfadeMix.toFloat(src, floats, values.size, C.ENCODING_PCM_FLOAT)
        assertArrayEquals(values, floats, 0f)
        val back = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        CrossfadeMix.fromFloat(floats, values.size, back, C.ENCODING_PCM_FLOAT)
        back.flip()
        for (v in values) assertEquals(v, back.getFloat(), 0f)
    }

    @Test
    fun `a 16-bit sum past full scale clamps instead of wrapping`() {
        assertEquals(Short.MAX_VALUE, CrossfadeMix.to16(1.4f))
        assertEquals(Short.MIN_VALUE, CrossfadeMix.to16(-1.4f))
        assertEquals(0.toShort(), CrossfadeMix.to16(Float.NaN))
        assertEquals(16_384.toShort(), CrossfadeMix.to16(0.5f))
    }
}
