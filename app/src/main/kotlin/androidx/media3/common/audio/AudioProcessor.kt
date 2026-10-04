// Desktop stand-in for Media3's androidx.media3.common.audio.AudioProcessor.
//
// Media3 is an Android library, but the contract its audio processors follow is
// a dozen methods over java.nio.ByteBuffer. Tryptify's whole processor chain
// (EQ, DSP console, upmix, spectrum taps, time-stretch, PCM widening) is
// written against that contract, so the desktop build keeps the interface,
// under the same package, and the processor sources stay byte-identical to the
// Android app. This is an independent re-implementation of the API shape as
// documented by Media3 (Apache 2.0); nothing from Media3 is copied in.
package androidx.media3.common.audio

import androidx.media3.common.C
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioProcessor {
    /** PCM audio format: sample rate, channel count and one of the C.ENCODING_PCM_* encodings. */
    class AudioFormat(
        @JvmField val sampleRate: Int,
        @JvmField val channelCount: Int,
        @JvmField val encoding: Int,
    ) {
        /** Bytes per frame, or C.LENGTH_UNSET when the encoding is not linear PCM. */
        @JvmField
        val bytesPerFrame: Int =
            if (Util.isEncodingLinearPcm(encoding)) Util.getPcmFrameSize(encoding, channelCount) else C.LENGTH_UNSET

        override fun equals(other: Any?): Boolean =
            other is AudioFormat && sampleRate == other.sampleRate && channelCount == other.channelCount && encoding == other.encoding

        override fun hashCode(): Int = (sampleRate * 31 + channelCount) * 31 + encoding

        override fun toString(): String =
            "AudioFormat[sampleRate=$sampleRate, channelCount=$channelCount, encoding=$encoding]"

        companion object {
            @JvmField
            val NOT_SET = AudioFormat(C.RATE_UNSET, C.LENGTH_UNSET, C.ENCODING_INVALID)
        }
    }

    /** Thrown by [configure] when the processor cannot handle the input format. */
    class UnhandledAudioFormatException : Exception {
        @JvmField val inputAudioFormat: AudioFormat

        constructor(inputAudioFormat: AudioFormat) : this("Unhandled input format:", inputAudioFormat)

        constructor(message: String, inputAudioFormat: AudioFormat) : super("$message $inputAudioFormat") {
            this.inputAudioFormat = inputAudioFormat
        }
    }

    /**
     * Configures the processor for the given input format and returns the
     * output format it will produce, or NOT_SET if it will be inactive. After
     * configure, flush() must be called before data is queued.
     */
    @Throws(UnhandledAudioFormatException::class)
    fun configure(inputAudioFormat: AudioFormat): AudioFormat

    /** Whether the processor is configured and active (its output differs from its input). */
    fun isActive(): Boolean

    /** Queues input; the processor consumes from the buffer's position. */
    fun queueInput(inputBuffer: ByteBuffer)

    /** Signals that no more input will be queued until the next flush. */
    fun queueEndOfStream()

    /** Returns processed output, or [EMPTY_BUFFER]; the buffer is valid until the next call. */
    fun getOutput(): ByteBuffer

    /** True once end of stream was queued and every output buffer has been returned. */
    fun isEnded(): Boolean

    /** Clears pending data, keeping the configuration. */
    fun flush()

    /** Resets to the unconfigured state, releasing resources. */
    fun reset()

    companion object {
        /** A shared, read-only, zero-length buffer: compared by identity by some callers. */
        @JvmField
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
