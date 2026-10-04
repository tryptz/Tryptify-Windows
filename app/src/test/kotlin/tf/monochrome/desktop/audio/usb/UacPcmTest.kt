package tf.monochrome.desktop.audio.usb

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The libusb sink's packing into DAC subslots.
 *
 * Bit-perfect is the claim the libusb path makes, and every way to break it
 * still plays: a scale of 2^23 - 1 instead of 2^23 is one LSB on every sample,
 * a lost shift is 48 dB down, a byte-order slip is noise. So the tests read
 * the bytes back and compare sample values exactly.
 */
class UacPcmTest {

    private val edge24 = intArrayOf(0, 1, -1, 2, 1000, -1000, 4_194_304, -4_194_304, 8_388_607, -8_388_608, 1_234_567, -7_654_321)
    private val edge16 = intArrayOf(0, 1, -1, 255, -256, 16_384, -16_384, 32_767, -32_768, 12_345, -23_456)

    @Test
    fun `a 24-bit sample carried as float reaches a 24-bit DAC unchanged`() {
        // x / 2^23 is what the decoder and ToFloatPcm produce for a 24-bit sample.
        val src = floats(edge24.map { it / 8_388_608f })
        val out = pack(src, C.ENCODING_PCM_FLOAT, edge24.size, validBits = 24, subslot = 3)
        assertArrayEquals(edge24, readSigned(out, 3))
    }

    @Test
    fun `a 24-bit sample in a 4-byte subslot is left-justified with the low byte zero`() {
        val src = floats(edge24.map { it / 8_388_608f })
        val out = pack(src, C.ENCODING_PCM_FLOAT, edge24.size, validBits = 24, subslot = 4)
        val read = readSigned(out, 4)
        assertArrayEquals(edge24.map { it shl 8 }.toIntArray(), read)
        read.forEach { assertEquals(0, it and 0xFF) }
        // A 32-bit alternate setting carries the same 24 bits.
        assertArrayEquals(read, readSigned(pack(src, C.ENCODING_PCM_FLOAT, edge24.size, validBits = 32, subslot = 4), 4))
    }

    @Test
    fun `16-bit integers pass through and widen losslessly`() {
        val src = shorts(edge16)
        assertArrayEquals(edge16, readSigned(pack(src, C.ENCODING_PCM_16BIT, edge16.size, 16, 2), 2))
        assertArrayEquals(edge16.map { it shl 8 }.toIntArray(), readSigned(pack(src, C.ENCODING_PCM_16BIT, edge16.size, 24, 3), 3))
        assertArrayEquals(edge16.map { it shl 16 }.toIntArray(), readSigned(pack(src, C.ENCODING_PCM_16BIT, edge16.size, 24, 4), 4))
        assertArrayEquals(edge16.map { it shl 16 }.toIntArray(), readSigned(pack(src, C.ENCODING_PCM_16BIT, edge16.size, 16, 4), 4))
    }

    @Test
    fun `packed 24-bit integers pass through and widen losslessly`() {
        val src = ByteBuffer.allocate(edge24.size * 3).order(ByteOrder.nativeOrder())
        edge24.forEach { v -> src.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte()) }
        src.flip()
        assertArrayEquals(edge24, readSigned(pack(src, C.ENCODING_PCM_24BIT, edge24.size, 24, 3), 3))
        assertArrayEquals(edge24.map { it shl 8 }.toIntArray(), readSigned(pack(src, C.ENCODING_PCM_24BIT, edge24.size, 32, 4), 4))
    }

    @Test
    fun `a 16-bit sample carried as float reaches a 16-bit DAC unchanged`() {
        val src = floats(edge16.map { it / 32_768f })
        assertArrayEquals(edge16, readSigned(pack(src, C.ENCODING_PCM_FLOAT, edge16.size, 16, 2), 2))
    }

    @Test
    fun `float overs clamp to the ends of the range and NaN is silence`() {
        assertEquals(8_388_607 shl 8, UacPcm.floatToContainer(1f, 24))
        assertEquals(8_388_607 shl 8, UacPcm.floatToContainer(1.5f, 24))
        assertEquals(8_388_607 shl 8, UacPcm.floatToContainer(Float.MAX_VALUE, 24))
        assertEquals(Int.MIN_VALUE, UacPcm.floatToContainer(-1f, 24))
        assertEquals(Int.MIN_VALUE, UacPcm.floatToContainer(-Float.MAX_VALUE, 24))
        assertEquals(0, UacPcm.floatToContainer(Float.NaN, 24))
        assertEquals(32_767 shl 16, UacPcm.floatToContainer(1f, 16))
        assertEquals(-32_768 shl 16, UacPcm.floatToContainer(-1f, 16))
    }

    @Test
    fun `float rounds to the nearest step rather than truncating toward zero`() {
        // Android's truncating 2^23 - 1 scale turned these into 999 and -999.
        assertEquals(1000 shl 8, UacPcm.floatToContainer(1000.4f / 8_388_608f, 24))
        assertEquals(-1000 shl 8, UacPcm.floatToContainer(-1000.4f / 8_388_608f, 24))
        assertEquals(1001 shl 8, UacPcm.floatToContainer(1000.6f / 8_388_608f, 24))
    }

    @Test
    fun `subslot bytes are little-endian whatever the host order`() {
        val src = shorts(intArrayOf(0x1234))
        val out = pack(src, C.ENCODING_PCM_16BIT, 1, 16, 2)
        assertEquals(0x34.toByte(), out.get(0))
        assertEquals(0x12.toByte(), out.get(1))
        val wide = pack(src, C.ENCODING_PCM_16BIT, 1, 24, 3)
        assertEquals(0x00.toByte(), wide.get(0))
        assertEquals(0x34.toByte(), wide.get(1))
        assertEquals(0x12.toByte(), wide.get(2))
    }

    @Test
    fun `packing leaves the source position alone`() {
        val src = shorts(edge16)
        src.position(4)
        pack(src, C.ENCODING_PCM_16BIT, 3, 16, 2)
        assertEquals(4, src.position())
    }

    @Test
    fun `the ladder widens integers, never narrows them, and narrows float last`() {
        assertArrayEquals(intArrayOf(16, 24, 32), UacPcm.bitDepthLadder(C.ENCODING_PCM_16BIT))
        assertArrayEquals(intArrayOf(24, 32), UacPcm.bitDepthLadder(C.ENCODING_PCM_24BIT))
        assertArrayEquals(intArrayOf(32), UacPcm.bitDepthLadder(C.ENCODING_PCM_32BIT))
        assertArrayEquals(intArrayOf(24, 32, 16), UacPcm.bitDepthLadder(C.ENCODING_PCM_FLOAT))
        assertEquals(0, UacPcm.bitDepthLadder(C.ENCODING_PCM_8BIT).size)
        for (encoding in intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) {
            UacPcm.bitDepthLadder(encoding).forEach { assertTrue(UacPcm.isLossless(encoding, it)) }
        }
        assertTrue(UacPcm.isLossless(C.ENCODING_PCM_FLOAT, 24))
        assertFalse(UacPcm.isLossless(C.ENCODING_PCM_FLOAT, 16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a subslot too narrow for the resolution is refused`() {
        pack(shorts(intArrayOf(1)), C.ENCODING_PCM_16BIT, 1, 24, 2)
    }

    private fun pack(src: ByteBuffer, encoding: Int, samples: Int, validBits: Int, subslot: Int): ByteBuffer {
        val dst = ByteBuffer.allocateDirect(samples * subslot)
        UacPcm.pack(src, encoding, samples, validBits, subslot, dst)
        return dst
    }

    private fun floats(values: List<Float>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        values.forEach { b.putFloat(it) }
        b.flip()
        return b
    }

    private fun shorts(values: IntArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { b.putShort(it.toShort()) }
        b.flip()
        return b
    }

    /** Reads little-endian signed samples of [bytes] bytes each. */
    private fun readSigned(buf: ByteBuffer, bytes: Int): IntArray {
        val n = buf.capacity() / bytes
        return IntArray(n) { i ->
            var v = 0
            for (k in 0 until bytes) v = v or ((buf.get(i * bytes + k).toInt() and 0xFF) shl (8 * k))
            val shift = 32 - 8 * bytes
            (v shl shift) shr shift
        }
    }
}
