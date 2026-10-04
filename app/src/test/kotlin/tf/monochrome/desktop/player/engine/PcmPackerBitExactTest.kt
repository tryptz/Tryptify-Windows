package tf.monochrome.desktop.player.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The exclusive path is bit-perfect only if decode-to-float and pack-to-int are exact inverses. */
class PcmPackerBitExactTest {
    private fun floats(vararg v: Float): ByteBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).apply { v.forEach { putFloat(it) }; flip() }

    @Test
    fun `24-bit samples survive float and pack into 24-in-32 unchanged`() {
        val codes = intArrayOf(0, 1, -1, 4_194_304, 8_388_607, -8_388_608, 6_000_001, -5_123_457)
        val packer = PcmPacker(AudioFormat(96_000, 2, C.ENCODING_PCM_FLOAT), AudioFormat(96_000, 2, C.ENCODING_PCM_32BIT))
        val out = packer.pack(floats(*FloatArray(codes.size) { codes[it] / 8_388_608f }), codes.size / 2, 1f)
        for (code in codes) assertEquals(code, out.getInt() shr 8)
    }

    @Test
    fun `16-bit samples survive float and pack into 16-bit unchanged`() {
        val codes = intArrayOf(0, 1, -1, 16_384, 32_767, -32_768, 20_001, -12_345)
        val packer = PcmPacker(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT), AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        val out = packer.pack(floats(*FloatArray(codes.size) { codes[it] / 32_768f }), codes.size / 2, 1f)
        for (code in codes) assertEquals(code, out.getShort().toInt())
    }

    @Test
    fun `full scale clamps to the largest code instead of wrapping`() {
        val packer = PcmPacker(AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT), AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        val out = packer.pack(floats(1f, -1f, 1.5f), 3, 1f)
        assertEquals(32_767, out.getShort().toInt())
        assertEquals(-32_768, out.getShort().toInt())
        assertEquals(32_767, out.getShort().toInt())
    }
}
