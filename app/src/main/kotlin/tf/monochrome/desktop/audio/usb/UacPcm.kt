package tf.monochrome.desktop.audio.usb

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The engine's PCM as a USB Audio Class Type I stream carries it: the format
 * ladder the libusb sink offers a DAC, and the packing into the DAC's
 * subslots.
 *
 * Bit-perfect is the point of the libusb path, so the arithmetic is exact:
 *
 *  - **Integers** are only ever widened, by shifting into the high bits of the
 *    subslot with the low bits zero -- the same values, which is what a DAC
 *    with no 16-bit alternate setting needs to play a 16-bit file untouched.
 *  - **Float** is quantised at 2^(bits-1), not 2^(bits-1) - 1. The decoder and
 *    `ToFloatPcmAudioProcessor` turn an n-bit sample x into x / 2^(n-1), so
 *    this scale gives x back exactly, and a 24-bit file with the DSP off
 *    reaches the DAC as the file's own samples. Android's
 *    `floatToSubslotSample` scaled by 2^23 - 1 and truncated, which moved every
 *    non-zero sample one LSB toward zero; a 24-bit file was never bit-perfect
 *    there, and audibly nothing told anyone.
 *  - Values outside [-1, 1) clamp to the end of the range rather than wrap, and
 *    NaN becomes silence.
 *
 * No dither anywhere: the only narrowing is float to 16 bits, the last rung of
 * the ladder, taken when a DAC has nothing wider at the track's rate.
 *
 * USB PCM is little-endian whatever the host, so the bytes are written in that
 * order explicitly. Pure arithmetic over buffers, so the unit tests pin it.
 */
internal object UacPcm {

    /** Bytes one sample of [encoding] takes in the engine's buffers; 0 for an encoding the sink cannot take. */
    fun sourceBytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * Bit depths to ask the DAC for, best first.
     *
     * An integer source asks for its own width, then wider ones (lossless:
     * see the class KDoc). Float asks for 24 bits -- what a float mantissa
     * holds -- then a 32-bit alternate setting carrying the same 24, and only
     * then 16, as Android's `[24, 16]` ladder did: audio on the DAC at 16 bits
     * beats audio on another device.
     */
    fun bitDepthLadder(encoding: Int): IntArray = when (encoding) {
        C.ENCODING_PCM_16BIT -> intArrayOf(16, 24, 32)
        C.ENCODING_PCM_24BIT -> intArrayOf(24, 32)
        C.ENCODING_PCM_32BIT -> intArrayOf(32)
        C.ENCODING_PCM_FLOAT -> intArrayOf(24, 32, 16)
        else -> IntArray(0)
    }

    /** Whether [encoding] carried at [bits] keeps every sample value. */
    fun isLossless(encoding: Int, bits: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_16BIT -> bits >= 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> bits >= 24
        C.ENCODING_PCM_32BIT -> bits >= 32
        else -> false
    }

    /**
     * [value] quantised to [validBits] (at most 24: a float carries no more)
     * and left-justified in a 32-bit container.
     */
    fun floatToContainer(value: Float, validBits: Int): Int {
        val bits = validBits.coerceIn(8, 24)
        val scale = (1 shl (bits - 1)).toFloat()
        val max = (1 shl (bits - 1)) - 1
        // Comparisons are false for NaN, so it falls through unclamped and
        // Math.round turns it into 0.
        val clamped = if (value > 1f) 1f else if (value < -1f) -1f else value
        // x / 2^(bits-1) * 2^(bits-1) is exact in float, so a sample that was
        // an integer of this width rounds to itself.
        val q = Math.round(clamped * scale).coerceIn(-max - 1, max)
        return q shl (32 - bits)
    }

    /**
     * Packs [samples] samples of [encoding] from [src] (starting at its
     * position, which is left where it was) into [dst] from index 0, each as
     * [subslotBytes] little-endian bytes holding [validBits] bits
     * left-justified. [dst] must hold `samples * subslotBytes` bytes.
     */
    fun pack(src: ByteBuffer, encoding: Int, samples: Int, validBits: Int, subslotBytes: Int, dst: ByteBuffer) {
        require(subslotBytes in 1..4 && subslotBytes * 8 >= validBits) {
            "a $subslotBytes-byte subslot cannot carry $validBits bits"
        }
        // The engine's buffers are native order throughout; PcmPacker sets the
        // same on what it reads.
        src.order(ByteOrder.nativeOrder())
        val base = src.position()
        // Integers wider than the DAC's resolution never reach here (the
        // ladder only widens), but the low bits are cleared anyway so the
        // subslot never carries more than the alternate setting declared.
        val keep = if (validBits >= 32) -1 else -1 shl (32 - validBits)
        val shift = 32 - 8 * subslotBytes
        var o = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> for (i in 0 until samples) {
                val container = (src.getShort(base + 2 * i).toInt() shl 16) and keep
                o = putSubslot(dst, o, container, shift, subslotBytes)
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until samples) {
                val p = base + 3 * i
                // Assembled little-endian, as the decoder writes packed 24-bit.
                val raw = (src.get(p).toInt() and 0xFF) or
                    ((src.get(p + 1).toInt() and 0xFF) shl 8) or
                    (src.get(p + 2).toInt() shl 16)
                o = putSubslot(dst, o, (raw shl 8) and keep, shift, subslotBytes)
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until samples) {
                o = putSubslot(dst, o, src.getInt(base + 4 * i) and keep, shift, subslotBytes)
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until samples) {
                val container = floatToContainer(src.getFloat(base + 4 * i), validBits)
                o = putSubslot(dst, o, container, shift, subslotBytes)
            }
            else -> throw IllegalArgumentException("unsupported encoding $encoding")
        }
    }

    /** Writes the top [bytes] bytes of [container] at [at], little-endian; returns the next index. */
    private fun putSubslot(dst: ByteBuffer, at: Int, container: Int, shift: Int, bytes: Int): Int {
        val v = container shr shift
        dst.put(at, v.toByte())
        if (bytes > 1) dst.put(at + 1, (v shr 8).toByte())
        if (bytes > 2) dst.put(at + 2, (v shr 16).toByte())
        if (bytes > 3) dst.put(at + 3, (v shr 24).toByte())
        return at + bytes
    }
}
