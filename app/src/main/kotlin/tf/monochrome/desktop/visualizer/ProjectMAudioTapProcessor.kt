package tf.monochrome.desktop.visualizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through tap that feeds the visualizer. Every block that goes through
 * comes out downstream byte for byte, and, while anything is listening, is
 * also converted to float and published on [ProjectMAudioBus].
 *
 * On Android this was the sink half of Media3's `TeeAudioProcessor`, which
 * did the consuming and the handing-on; the desktop engine has no Media3, so
 * the tee's job is done here directly, as an [AudioProcessor] in the chain
 * (playback-architecture §2.4, row 10). The block is copied into a buffer of
 * this processor's own rather than handing the caller's buffer through: a
 * stage's output is a buffer it owns, valid until the next call, and the
 * engine re-presents an input it could not fully write after a partial sink
 * write -- passing the input on would publish the remainder a second time.
 *
 * The bus is non-blocking (a concurrent queue trimmed by age), so unlike the
 * Android bypass chain, which left this tap out because it could stall the
 * render thread, the desktop chain carries it for every sink.
 */
@OptIn(UnstableApi::class)
class ProjectMAudioTapProcessor(
    private val audioBus: ProjectMAudioBus,
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT -> Unit
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // No visualizer engine or waveform overlay listening -> skip the
        // PCM-to-float conversion entirely. This runs on the render thread for
        // every block of every track, so even modest per-block work matters.
        if (audioBus.hasSubscribers()) publish(inputBuffer)
        // The tee: consume the input into our own output buffer, unchanged.
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    private fun publish(input: ByteBuffer) {
        // Absolute reads from the position, so the copy below still sees the
        // whole block. The chain hands native-order buffers; one that is not
        // gets a view, as the other processors do.
        val src = if (input.order() == ByteOrder.nativeOrder()) input else input.duplicate().order(ByteOrder.nativeOrder())
        val start = input.position()
        val samples: FloatArray = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val count = input.remaining() / 2
                FloatArray(count) { i -> (src.getShort(start + i * 2) / 32768f).coerceIn(-1f, 1f) }
            }
            C.ENCODING_PCM_FLOAT -> {
                val count = input.remaining() / 4
                FloatArray(count) { i -> src.getFloat(start + i * 4).coerceIn(-1f, 1f) }
            }
            C.ENCODING_PCM_8BIT -> {
                val count = input.remaining()
                FloatArray(count) { i -> ((src.get(start + i).toInt() and 0xFF) - 128) / 128f }
            }
            else -> return
        }
        // A fresh array per block on purpose: the bus keeps frames by reference
        // until the visualizer drains them.
        audioBus.publish(
            samples = samples,
            channelCount = inputAudioFormat.channelCount.coerceAtLeast(1),
            sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1),
        )
    }
}
