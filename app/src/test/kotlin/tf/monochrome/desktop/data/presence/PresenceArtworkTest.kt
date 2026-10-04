package tf.monochrome.desktop.data.presence

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The spinning-disc card, rendered for real.
 *
 * Desktop only. On Android the artwork needs `android.graphics` and so could
 * never run in a JVM test, which left the one rule `docs/ui-invariants.md`
 * states about it — stay under ~248 KB or Discord shows a still — checked by
 * hand. Here the drawing and the encoder are Skia, which runs in the test JVM,
 * so the budget and the file's shape can be held by a test.
 */
class PresenceArtworkTest {

    /** A smooth two-axis gradient: the kind of sleeve that fits at full quality. */
    private fun smoothCover(size: Int): Bitmap = cover(size, size) { x, y ->
        Color.makeRGB(x * 255 / size, y * 255 / size, 160)
    }

    /**
     * A busy sleeve: hard-edged blocks under fine noise, which is what costs a
     * lossy encoder the most short of pure static.
     */
    private fun busyCover(size: Int): Bitmap {
        val random = Random(42)
        val blocks = IntArray(64) { random.nextInt() or (0xFF shl 24) }
        return cover(size, size) { x, y ->
            val base = blocks[(x * 8 / size) * 8 + (y * 8 / size)]
            val n = random.nextInt(-40, 41)
            Color.makeRGB(
                (Color.getR(base) + n).coerceIn(0, 255),
                (Color.getG(base) + n).coerceIn(0, 255),
                (Color.getB(base) + n).coerceIn(0, 255),
            )
        }
    }

    private fun cover(width: Int, height: Int, argb: (Int, Int) -> Int): Bitmap {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val c = argb(x, y)
            val o = (y * width + x) * 4
            // N32 is BGRA in memory on every platform the app ships for.
            bytes[o] = Color.getB(c).toByte()
            bytes[o + 1] = Color.getG(c).toByte()
            bytes[o + 2] = Color.getR(c).toByte()
            bytes[o + 3] = 0xFF.toByte()
        }
        return Bitmap().apply {
            allocPixels(ImageInfo.makeN32Premul(width, height))
            check(installPixels(bytes))
        }
    }

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Every top-level chunk as (id, offset of its payload, payload size). */
    private fun chunks(file: ByteArray): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var pos = 12
        while (pos + 8 <= file.size) {
            val size = le32(file, pos + 4)
            out += Triple(String(file, pos, 4, Charsets.US_ASCII), pos + 8, size)
            pos += 8 + size + (size and 1)
        }
        return out
    }

    private fun decode(webp: ByteArray): Codec = Codec.makeFromData(Data.makeFromBytes(webp))

    @Test
    fun `a busy cover still fits the budget Discord animates under`() {
        // Over it the media proxy shows the first frame and never animates,
        // which on a disc at rest reads as "the animation is broken".
        val webp = PresenceArtwork.render(busyCover(1200), "four", Color.makeRGB(200, 40, 120))
        assertNotNull(webp)
        assertTrue("${webp!!.size} bytes", webp.size <= PresenceArtwork.MAX_BYTES)
        // Whichever lever got it there, the frame count is a stride of the full
        // set, so one bar still turns the disc exactly once.
        val frames = decode(webp).frameCount
        assertTrue("$frames frames", frames == PresenceArtwork.FRAMES || frames == PresenceArtwork.FRAMES / 2)
    }

    @Test
    fun `a simple cover keeps every frame and turns once a bar, forever`() {
        val bpm = 120
        val webp = PresenceArtwork.render(smoothCover(600), "backbeat", Color.makeRGB(88, 101, 242), bpm)!!
        val codec = decode(webp)
        assertEquals(EncodedImageFormat.WEBP, codec.encodedImageFormat)
        assertEquals(PresenceArtwork.SIZE, codec.width)
        assertEquals(PresenceArtwork.SIZE, codec.height)
        assertEquals(PresenceArtwork.FRAMES, codec.frameCount)
        // -1 is Skia's "repeat forever", which is what loop count 0 means.
        assertEquals(-1, codec.repetitionCount)
        val loopMs = 4 * 60_000 / bpm
        val total = codec.framesInfo.sumOf { it.duration }
        assertTrue("one turn is $total ms, a bar is $loopMs", abs(total - loopMs) < PresenceArtwork.FRAMES)
    }

    @Test
    fun `every frame is a bare lossy bitstream with nothing left behind`() {
        // AnimatedWebP copies only the VP8 chunk into each frame. A frame that
        // came out of the encoder with an alpha chunk would lose it there in
        // silence, so the frames must be opaque — the backdrop guarantees it.
        val webp = PresenceArtwork.render(busyCover(400), "dnb", Color.makeRGB(10, 200, 90))!!
        val anmf = chunks(webp).filter { it.first == "ANMF" }
        assertTrue(anmf.isNotEmpty())
        for ((_, payload, _) in anmf) {
            // 16 bytes of frame header, then the frame's own chunk.
            assertEquals("VP8 ", String(webp, payload + 16, 4, Charsets.US_ASCII))
        }
    }

    @Test
    fun `an unknown groove and a silly tempo fall back rather than fail`() {
        val webp = PresenceArtwork.render(smoothCover(300), "no-such-groove", 0, bpm = 9_999)
        assertNotNull(webp)
        val backbeat = PresenceArtwork.GROOVES.getValue(PresenceBadge.DEFAULT_GROOVE).bpm
        val total = decode(webp!!).framesInfo.sumOf { it.duration }
        assertTrue(abs(total - 4 * 60_000 / backbeat) < PresenceArtwork.FRAMES)
    }

    @Test
    fun `a local cover is posted as one square still`() {
        val still = PresenceArtwork.still(cover(640, 480) { x, _ -> Color.makeRGB(x % 256, 90, 30) })
        assertNotNull(still)
        val codec = decode(still!!)
        assertEquals(EncodedImageFormat.WEBP, codec.encodedImageFormat)
        assertEquals(1, codec.frameCount)
        assertEquals(PresenceArtwork.SIZE, codec.width)
        assertEquals(PresenceArtwork.SIZE, codec.height)
    }
}
