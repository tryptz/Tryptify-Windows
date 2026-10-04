package tf.monochrome.desktop.audio.sink

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer

/**
 * Where finished PCM goes. The engine's render thread configures a sink for
 * the chain's output format, writes interleaved frames with back-pressure and
 * reads the device clock back for the position.
 *
 * Three implementations: [WasapiSink] (shared or exclusive), [JavaSoundSink]
 * (fallback, and the Linux smoke test) and the libusb UAC sink ported from the
 * Android bypass path. The engine never changes the processor chain by sink.
 */
interface AudioSink {
    /**
     * Negotiates [format] (sample rate, channel count, C.ENCODING_PCM_16BIT or
     * C.ENCODING_PCM_FLOAT) and returns the format the sink actually takes;
     * for a float chain output an exclusive sink may answer with a 24- or
     * 16-bit integer format, and the caller packs to it. Throws when nothing
     * can be opened.
     */
    fun configure(format: AudioFormat): AudioFormat

    /** Writes from the buffer's position; returns frames accepted (0 = full, re-present later). */
    fun write(buffer: ByteBuffer, frames: Int): Int

    /** Frames the device has rendered since the last configure/flush, from its clock. */
    fun playedFrames(): Long

    /** Frames written but not yet rendered. */
    fun pendingFrames(): Long

    /** Pads and pushes any staged partial buffer; used at end of stream. */
    fun drain()

    fun play()
    fun pause()
    /** Drops everything queued (seek, track change without crossfade). */
    fun flush()
    fun stop()
    fun release()

    val isOpen: Boolean
    val isExclusive: Boolean
    val latencyFrames: Int
    val outputFormat: AudioFormat?
    val deviceName: String?
}

class SinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
