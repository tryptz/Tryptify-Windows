package tf.monochrome.desktop.audio.sink

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The USB-then-listener's-choice routing: a refused format moves to the next
 * output, the next format the first one takes moves back, and an output that
 * is not carrying audio is released rather than left holding its device.
 */
class FallbackAudioSinkTest {

    private val cd = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
    private val hiRes = AudioFormat(352_800, 2, C.ENCODING_PCM_FLOAT)

    private class FakeSink(val label: String, private val accepts: (AudioFormat) -> Boolean) : AudioSink {
        var configures = 0
        var releases = 0
        var plays = 0
        var played = 0L
        private var format: AudioFormat? = null

        override fun configure(format: AudioFormat): AudioFormat {
            configures++
            if (!accepts(format)) throw SinkException("$label refuses $format")
            this.format = format
            return format
        }

        override fun write(buffer: ByteBuffer, frames: Int): Int = frames
        override fun playedFrames(): Long = played
        override fun pendingFrames(): Long = 0L
        override fun drain() {}
        override fun play() { plays++ }
        override fun pause() {}
        override fun flush() {}
        override fun stop() {}
        override fun release() { releases++; format = null }
        override val isOpen: Boolean get() = format != null
        override val isExclusive: Boolean get() = label == "usb"
        override val latencyFrames: Int get() = 0
        override val outputFormat: AudioFormat? get() = format
        override val deviceName: String? get() = label
    }

    @Test
    fun `the first output that takes the format carries it`() {
        val usb = FakeSink("usb") { it.sampleRate <= 192_000 }
        val wasapi = FakeSink("wasapi") { true }
        val sink = FallbackAudioSink(listOf({ usb }, { wasapi }))

        assertEquals(cd, sink.configure(cd))
        assertSame(usb, sink.active)
        assertFalse(sink.isFallback)
        assertFalse(sink.refusedAll)
        assertEquals(0, wasapi.configures)
        assertEquals("usb", sink.deviceName)
        assertTrue(sink.isExclusive)
    }

    @Test
    fun `a refused format falls back, and the next one the DAC takes goes back to it`() {
        var usbBuilt = 0
        val wasapi = FakeSink("wasapi") { true }
        val usbSinks = ArrayList<FakeSink>()
        val sink = FallbackAudioSink(listOf({ usbBuilt++; FakeSink("usb") { it.sampleRate <= 192_000 }.also { usbSinks += it } }, { wasapi }))

        sink.configure(hiRes)
        assertSame(wasapi, sink.active)
        assertTrue(sink.isFallback)
        assertEquals(1, usbSinks[0].releases)
        assertEquals("wasapi", sink.deviceName)

        sink.configure(cd)
        assertEquals(2, usbBuilt)
        assertSame(usbSinks[1], sink.active)
        // The fallback is let go once it no longer carries the audio.
        assertEquals(1, wasapi.releases)
    }

    @Test
    fun `an output that keeps the stream is configured again, not rebuilt`() {
        var built = 0
        val usb = FakeSink("usb") { true }
        val sink = FallbackAudioSink(listOf({ built++; usb }, { FakeSink("wasapi") { true } }))
        sink.configure(cd)
        sink.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(1, built)
        assertEquals(2, usb.configures)
        assertEquals(0, usb.releases)
    }

    @Test
    fun `everything refusing is an error, with nothing left active`() {
        val a = FakeSink("a") { false }
        val b = FakeSink("b") { false }
        val sink = FallbackAudioSink(listOf({ a }, { b }))
        try {
            sink.configure(cd)
            fail("expected SinkException")
        } catch (e: SinkException) {
            assertTrue(e.cause is SinkException)
        }
        assertNull(sink.active)
        assertEquals(-1, sink.activeIndex)
        assertTrue(sink.refusedAll)
        assertEquals(0L, sink.playedFrames())
        assertNull(sink.outputFormat)
        // The engine releases it next and opens its own line; that is still
        // what the owner has to be able to tell.
        sink.release()
        assertTrue(sink.refusedAll)
    }

    @Test
    fun `transport and the clock go to the active output`() {
        val usb = FakeSink("usb") { false }
        val wasapi = FakeSink("wasapi") { true }
        val sink = FallbackAudioSink(listOf({ usb }, { wasapi }))
        sink.configure(cd)
        wasapi.played = 1234
        sink.play()
        assertEquals(1, wasapi.plays)
        assertEquals(0, usb.plays)
        assertEquals(1234L, sink.playedFrames())
        assertEquals(10, sink.write(ByteBuffer.allocate(40), 10))
        sink.release()
        assertNull(sink.active)
        assertEquals(1, wasapi.releases)
    }
}
