package tf.monochrome.desktop.audio.resample

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.desktop.audio.stretch.StretchAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The two halves of a speed change go to different stages, and a stage that
 * joins because a control moved has to actually be in the chain the engine
 * runs. Both were bugs on Android once; this pins the desktop owner of the
 * chain to the same answers.
 */
class TryptifyAudioProcessorChainTest {

    /** A stage that is always in and does nothing, to stand for the app's processors. */
    private class Marker : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioFormat) = inputAudioFormat
        override fun queueInput(inputBuffer: ByteBuffer) {
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
        }
    }

    private val fs = 48_000
    private val stereoFloat = AudioFormat(fs, 2, C.ENCODING_PCM_FLOAT)

    private fun chain(resampler: VariRateAudioProcessor = VariRateAudioProcessor(),
                      timeStretch: FloatSonicAudioProcessor = FloatSonicAudioProcessor(),
                      vararg app: AudioProcessor): TryptifyAudioProcessorChain =
        TryptifyAudioProcessorChain(app.toList(), resampler, StretchAudioProcessor(), timeStretch) {}

    @Test
    fun `the app's processors come first and the transport stages last, in order`() {
        val a = Marker(); val b = Marker()
        val resampler = VariRateAudioProcessor()
        val sonic = FloatSonicAudioProcessor()
        val c = chain(resampler, sonic, a, b)
        val list = c.processors
        assertEquals(5, list.size)
        assertTrue(list[0] === a && list[1] === b)
        assertTrue(list[2] === resampler)
        assertTrue(list[3] is StretchAudioProcessor)
        assertTrue(list[4] === sonic)
        assertTrue(c.getAudioProcessors().toList() == list)
    }

    @Test
    fun `pitch riding the tempo goes to the resampler and leaves Sonic at unity`() {
        val resampler = VariRateAudioProcessor()
        val sonic = FloatSonicAudioProcessor()
        val c = chain(resampler, sonic)
        c.configure(stereoFloat)
        c.applyPlaybackParameters(PlaybackParameters(1.25f, 1.25f))
        assertEquals(1.25f, resampler.getRatio(), 1e-6f)
        assertFalse("Sonic must not engage for a varispeed change", sonic.isActive())
        assertEquals(1_250_000L, c.getMediaDuration(1_000_000L))
    }

    @Test
    fun `speed with pitch preserved goes to Sonic and leaves the resampler at unity`() {
        val resampler = VariRateAudioProcessor()
        val sonic = FloatSonicAudioProcessor()
        val c = chain(resampler, sonic)
        c.configure(stereoFloat)
        c.applyPlaybackParameters(PlaybackParameters(1.5f, 1f))
        assertEquals(1f, resampler.getRatio(), 1e-6f)
        assertFalse(resampler.isActive())
        assertTrue(sonic.isActive())
        assertEquals(1_500_000L, c.getMediaDuration(1_000_000L))
        assertEquals(1_000_000L, chain().getMediaDuration(1_000_000L))
    }

    @Test
    fun `a resampler that joins after configure is run by the next process call`() {
        val c = chain()
        c.configure(stereoFloat)
        val frames = 4800
        val block = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) { block.putFloat(0.1f); block.putFloat(-0.1f) }
        block.flip()
        // Configured at 1.00x the resampler is out of the chain: one block in, one out.
        assertEquals(frames, c.process(block).remaining() / 8)

        c.applyPlaybackParameters(PlaybackParameters(1.25f, 1.25f))
        block.rewind()
        val out = c.process(block).remaining() / 8
        val expected = frames / 1.25
        // Minus the kernel's lookahead, which comes out at end of stream.
        assertTrue("expected about $expected frames, got $out", abs(out - expected) < 64)
        assertEquals("the stage consumed its input", 0, block.remaining())
    }
}
