package tf.monochrome.desktop.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The FLAC picture block records the cover's pixel size next to its bytes.
 * Android read it with BitmapFactory's bounds-only decode; the desktop reads
 * the header itself, so the two formats it embeds are pinned here.
 */
class ImageHeaderTest {

    @Test
    fun `png dimensions come from IHDR`() {
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 0x05, 0x00, // width 1280
            0, 0, 0x05, 0x00, // height 1280
            8, 2, 0, 0, 0,
        )
        assertEquals(1280 to 1280, EmbeddedTags.imageDimensions(png))
    }

    @Test
    fun `jpeg dimensions come from the first start-of-frame`() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                               // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x4A, 0x46,       // APP0, length 4
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08,             // SOF0, length 17, precision 8
            0x02, 0x80.toByte(),                                        // height 640
            0x03, 0x20,                                                 // width 800
            0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        )
        assertEquals(800 to 640, EmbeddedTags.imageDimensions(jpeg))
    }

    @Test
    fun `unknown or truncated headers give no dimensions`() {
        assertNull(EmbeddedTags.imageDimensions("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertNull(EmbeddedTags.imageDimensions(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertNull(EmbeddedTags.imageDimensions(ByteArray(0)))
    }
}
