package tf.monochrome.desktop.visualizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The tap replaced Media3's tee, so it has to keep the tee's two promises: the
 * bytes downstream are the bytes upstream, and the visualizer gets a float
 * copy only while something is listening.
 */
class ProjectMAudioTapProcessorTest {

    private fun configured(bus: ProjectMAudioBus, encoding: Int, channels: Int = 2): ProjectMAudioTapProcessor {
        val p = ProjectMAudioTapProcessor(bus)
        val out = p.configure(AudioFormat(48_000, channels, encoding))
        assertEquals(AudioFormat(48_000, channels, encoding), out)
        assertTrue(p.isActive())
        p.flush()
        return p
    }

    private fun drain(b: ByteBuffer): ByteArray = ByteArray(b.remaining()).also { b.get(it) }

    @Test
    fun `passes 16-bit audio through unchanged and publishes it as float`() {
        val bus = ProjectMAudioBus()
        bus.acquire()
        val p = configured(bus, C.ENCODING_PCM_16BIT)
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(16384).putShort(-32768).putShort(0).putShort(32767).flip()
        val expected = drain(input.duplicate())

        p.queueInput(input)
        assertEquals("the tee consumes its input", 0, input.remaining())
        assertArrayEquals(expected, drain(p.getOutput()))
        assertTrue(p.getOutput() === AudioProcessor.EMPTY_BUFFER)

        val published = bus.peekSamples()
        assertNotNull(published)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 0f, 32767f / 32768f), published!!, 1e-6f)
        val frame = bus.drainAll().single()
        assertEquals(2, frame.channelCount)
        assertEquals(48_000, frame.sampleRate)
    }

    @Test
    fun `float audio is clamped on the way to the bus but not downstream`() {
        val bus = ProjectMAudioBus()
        bus.acquire()
        val p = configured(bus, C.ENCODING_PCM_FLOAT, channels = 1)
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putFloat(1.5f).putFloat(-0.25f).flip()
        val expected = drain(input.duplicate())

        p.queueInput(input)
        assertArrayEquals("downstream bytes are untouched", expected, drain(p.getOutput()))
        assertArrayEquals(floatArrayOf(1f, -0.25f), bus.peekSamples()!!, 1e-6f)
    }

    @Test
    fun `nothing is published while nobody is listening`() {
        val bus = ProjectMAudioBus()
        val p = configured(bus, C.ENCODING_PCM_16BIT)
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(1).putShort(2).flip()
        p.queueInput(input)
        assertEquals(4, p.getOutput().remaining())
        assertNull(bus.peekSamples())
        assertTrue(bus.drainAll().isEmpty())
    }

    @Test
    fun `end of stream is reported once the last output is taken`() {
        val p = configured(ProjectMAudioBus(), C.ENCODING_PCM_16BIT)
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        input.putShort(1).putShort(2).flip()
        p.queueInput(input)
        p.queueEndOfStream()
        assertEquals(false, p.isEnded())
        p.getOutput()
        assertTrue(p.isEnded())
    }

    @Test
    fun `an integer format wider than 16 bits is refused like every other stage`() {
        val p = ProjectMAudioTapProcessor(ProjectMAudioBus())
        try {
            p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT))
            fail("expected UnhandledAudioFormatException")
        } catch (expected: AudioProcessor.UnhandledAudioFormatException) {
        }
    }
}
