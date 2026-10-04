package tf.monochrome.desktop.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts the chain's output (16-bit or float) to what the sink negotiated,
 * applying the volume in the same pass. Integer-to-integer at unity gain is a
 * straight copy, so a 16-bit source with the DSP bypassed reaches the device
 * untouched; float is scaled to the sink's width with rounding (no dither:
 * the Android bypass path did not dither either, and the console's output is
 * already shaped by its own limiter).
 */
class PcmPacker(private val input: AudioFormat, private val output: AudioFormat) {
    private val inFloat = input.encoding == C.ENCODING_PCM_FLOAT
    private val channels = input.channelCount
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    val identity: Boolean get() = input.encoding == output.encoding

    /**
     * Returns a buffer holding [frames] frames of [src] (from its position) in
     * the output format with [gain] applied, and advances [src]. When the
     * formats match and gain is unity, returns [src] itself.
     */
    fun pack(src: ByteBuffer, frames: Int, gain: Float): ByteBuffer {
        if (identity && gain >= 0.9999f) return src
        val outBytes = frames * output.bytesPerFrame
        if (scratch.capacity() < outBytes) scratch = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        scratch.clear()
        val samples = frames * channels
        val s = src.order(ByteOrder.nativeOrder())
        when (output.encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) {
                val v = readSample(s) * gain
                scratch.putShort((v * 32767f).coerceIn(-32768f, 32767f).let { Math.round(it) }.toShort())
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until samples) {
                val v = (readSample(s) * gain * 8388607f).coerceIn(-8388608f, 8388607f).let { Math.round(it) }
                scratch.put((v and 0xFF).toByte()).put(((v shr 8) and 0xFF).toByte()).put(((v shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until samples) {
                // 24 valid bits left-justified in a 32-bit container, the common DAC subslot.
                val v = (readSample(s) * gain * 8388607f).coerceIn(-8388608f, 8388607f).let { Math.round(it) }
                scratch.putInt(v shl 8)
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until samples) scratch.putFloat(readSample(s) * gain)
            else -> throw IllegalArgumentException("unsupported sink encoding ${output.encoding}")
        }
        scratch.flip()
        return scratch
    }

    private fun readSample(s: ByteBuffer): Float =
        if (inFloat) s.getFloat() else s.getShort() / 32768f
}
