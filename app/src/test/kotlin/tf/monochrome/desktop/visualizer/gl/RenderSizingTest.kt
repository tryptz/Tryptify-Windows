package tf.monochrome.desktop.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The size projectM renders at, and the size it is allowed to change to.
 *
 * Every pixel here is read back to the CPU and uploaded again by Skia, every
 * frame, so an error that renders too large is a frame-rate bug; one that
 * renders at a different aspect ratio from the surface is a stretched picture;
 * one that resizes on every mouse move restarts the preset's trails while a
 * window is being dragged.
 */
class RenderSizingTest {

    @Test
    fun `a surface within the cap renders at its own size`() {
        assertEquals(PixelSize(800, 450), renderSizeFor(800, 450))
        assertEquals(PixelSize(1920, 1080), renderSizeFor(1920, 1080))
        assertEquals(PixelSize(1080, 1920), renderSizeFor(1080, 1920))
    }

    @Test
    fun `a 4K surface renders at 1080p`() {
        assertEquals(PixelSize(1920, 1080), renderSizeFor(3840, 2160))
        assertEquals(PixelSize(1080, 1920), renderSizeFor(2160, 3840))
    }

    @Test
    fun `the cap keeps the surface's aspect ratio`() {
        // An ultrawide: the long side is the limit.
        val ultrawide = renderSizeFor(5120, 1440)
        assertEquals(1920, ultrawide.width)
        assertEquals(540, ultrawide.height)
        // Nearly square: the short side is the limit.
        val square = renderSizeFor(2400, 2000)
        assertEquals(1080, square.height)
        assertEquals(1296, square.width)
    }

    @Test
    fun `no side ever exceeds the cap whatever the rounding`() {
        for (w in listOf(1921, 2000, 2561, 3001, 3840, 7680)) {
            for (h in listOf(1081, 1199, 1440, 1600, 2160, 4320)) {
                val size = renderSizeFor(w, h)
                assertTrue("$w x $h -> $size", maxOf(size.width, size.height) <= MAX_RENDER_LONG_SIDE)
                assertTrue("$w x $h -> $size", minOf(size.width, size.height) <= MAX_RENDER_SHORT_SIDE)
                assertTrue("$w x $h -> $size", size.width >= 1 && size.height >= 1)
            }
        }
    }

    @Test
    fun `an empty surface renders nothing`() {
        assertTrue(renderSizeFor(0, 600).isEmpty)
        assertTrue(renderSizeFor(800, 0).isEmpty)
        assertTrue(renderSizeFor(-1, -1).isEmpty)
    }

    @Test
    fun `a sliver keeps at least one pixel`() {
        assertEquals(PixelSize(1920, 1), renderSizeFor(10_000, 2))
    }

    @Test
    fun `a frame that fits the framebuffer is left alone`() {
        assertEquals(PixelSize(800, 450), fitWithin(PixelSize(800, 450), 1920, 1920))
        assertEquals(PixelSize(1920, 1920), fitWithin(PixelSize(1920, 1920), 1920, 1920))
    }

    @Test
    fun `a frame larger than the framebuffer shrinks as a whole`() {
        // A 1920x1920 window clamped to a 1366x768 laptop screen.
        val fitted = fitWithin(PixelSize(1920, 1080), 1390, 790)
        assertTrue(fitted.width <= 1390 && fitted.height <= 790)
        assertEquals(1920.0 / 1080.0, fitted.width.toDouble() / fitted.height, 0.01)
    }

    @Test
    fun `fitting to nothing renders nothing`() {
        assertTrue(fitWithin(PixelSize(800, 450), 0, 0).isEmpty)
        assertTrue(fitWithin(PixelSize.Zero, 1920, 1920).isEmpty)
    }

    // ── ResizeSettler ───────────────────────────────────────────────────

    private val settle = 150_000_000L

    @Test
    fun `the first size is taken at once`() {
        val settler = ResizeSettler(settle)
        assertEquals(PixelSize(800, 450), settler.offer(PixelSize(800, 450), 0L))
        assertEquals(0L, settler.pendingNanos(0L))
    }

    @Test
    fun `a new size waits until it has held`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        assertEquals(PixelSize(800, 450), settler.offer(PixelSize(900, 500), 10_000_000L))
        assertEquals(PixelSize(800, 450), settler.offer(PixelSize(900, 500), 100_000_000L))
        assertEquals(PixelSize(900, 500), settler.offer(PixelSize(900, 500), 160_000_000L))
    }

    @Test
    fun `a drag keeps the old size until the mouse stops`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        var now = 0L
        // A size per 16 ms frame, never the same twice.
        for (step in 1..60) {
            now += 16_000_000L
            assertEquals(PixelSize(800, 450), settler.offer(PixelSize(800 + step, 450 + step), now))
        }
        // Released: the last size holds and is taken once it has settled.
        val last = PixelSize(860, 510)
        assertEquals(PixelSize(800, 450), settler.offer(last, now + 100_000_000L))
        assertEquals(last, settler.offer(last, now + settle))
    }

    @Test
    fun `pending time counts down to the settle and then stops`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        settler.offer(PixelSize(900, 500), 0L)
        assertEquals(settle - 50_000_000L, settler.pendingNanos(50_000_000L))
        assertTrue(settler.pendingNanos(settle + 1) >= 1L)
        settler.offer(PixelSize(900, 500), settle)
        assertEquals(0L, settler.pendingNanos(settle))
    }

    @Test
    fun `going back to the current size cancels a pending change`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        settler.offer(PixelSize(900, 500), 0L)
        assertEquals(PixelSize(800, 450), settler.offer(PixelSize(800, 450), 10_000_000L))
        assertEquals(0L, settler.pendingNanos(10_000_000L))
    }

    @Test
    fun `reset takes the next size at once`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        settler.reset()
        assertEquals(PixelSize(600, 1000), settler.offer(PixelSize(600, 1000), 1L))
    }

    @Test
    fun `collapsing to nothing and back is immediate`() {
        val settler = ResizeSettler(settle)
        settler.offer(PixelSize(800, 450), 0L)
        assertTrue(settler.offer(PixelSize.Zero, 1L).isEmpty)
        assertEquals(PixelSize(640, 360), settler.offer(PixelSize(640, 360), 2L))
    }

    // ── Row flip ───────────────────────────────────────────────────────

    @Test
    fun `rows come out top first into a padded destination`() {
        val height = 3
        val rowLength = 8
        val stride = 12
        val source = ByteArray(height * rowLength) { (it / rowLength).toByte() } // row r filled with r
        val destination = ByteArray(height * stride) { -1 }
        forEachFlippedRow(height, rowLength, stride) { from, to, length ->
            System.arraycopy(source, from.toInt(), destination, to.toInt(), length)
        }
        for (y in 0 until height) {
            for (x in 0 until rowLength) {
                assertEquals("row $y", (height - 1 - y).toByte(), destination[y * stride + x])
            }
            for (x in rowLength until stride) {
                assertEquals("padding of row $y untouched", (-1).toByte(), destination[y * stride + x])
            }
        }
    }
}
