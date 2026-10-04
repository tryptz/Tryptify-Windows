package tf.monochrome.desktop.visualizer.gl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.system.MemoryUtil

/**
 * One frame's pixels in a Skia bitmap that Compose can draw.
 *
 * BGRA, because that is Skia's native order on Windows and Linux and the order
 * desktop drivers read back fastest: no swizzle on either side of the copy.
 * Opaque, because both compositions are — the hero is projectM's frame and the
 * ambient pass writes alpha 1 itself.
 *
 * Mutable on purpose. Skia copies a mutable bitmap's pixels into the image it
 * draws, at the moment it draws it, which is what lets the render thread reuse
 * this memory once the UI has moved on to a newer frame. An immutable bitmap
 * would be shared instead and keep being read after it had been rewritten.
 */
internal class FrameSlot(val width: Int, val height: Int) {
    private val bitmap = Bitmap().also { bitmap ->
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        check(bitmap.allocPixels(info) && bitmap.width == width && bitmap.height == height) {
            "could not allocate a ${width}x$height frame"
        }
    }

    /** What the composable draws. Wraps [bitmap]; holds no copy of its own. */
    val image: ImageBitmap = bitmap.asComposeImageBitmap()

    private val rowBytes: Int = bitmap.rowBytes.also {
        check(it >= width * 4) { "frame rows of $it bytes cannot hold $width pixels" }
    }
    private val address: Long = bitmap.peekPixels()?.use { it.addr }
        ?: error("frame bitmap has no addressable pixels")

    fun matches(width: Int, height: Int): Boolean = this.width == width && this.height == height

    /**
     * Copies a frame read back from OpenGL at [source] — tightly packed BGRA
     * rows, bottom row first, as GL's origin is the bottom-left corner — the
     * right way up into this slot.
     */
    fun copyFlippedFrom(source: Long) {
        forEachFlippedRow(height, width * 4, rowBytes) { sourceOffset, destinationOffset, length ->
            MemoryUtil.memCopy(source + sourceOffset, address + destinationOffset, length.toLong())
        }
        bitmap.notifyPixelsChanged()
    }

    fun close() = bitmap.close()
}

/**
 * The row copies that turn a bottom-up image of [height] rows of
 * [rowLength] bytes, packed back to back, into a top-down one whose rows start
 * [destinationStride] bytes apart: destination row y comes from source row
 * height − 1 − y.
 */
internal inline fun forEachFlippedRow(
    height: Int,
    rowLength: Int,
    destinationStride: Int,
    copy: (sourceOffset: Long, destinationOffset: Long, length: Int) -> Unit,
) {
    for (y in 0 until height) {
        copy((height - 1 - y).toLong() * rowLength, y.toLong() * destinationStride, rowLength)
    }
}

/**
 * The frames on their way to one composable: [TripleBuffer] over [FrameSlot]s,
 * plus the slot lifecycle — a slot the wrong size for the frame being written
 * is disposed of and a new one made.
 */
internal class FrameExchange {
    private val buffer = TripleBuffer<FrameSlot>()

    /**
     * Render thread: a slot of [width]×[height] to write into, or null when the
     * composable has gone and nothing should be written at all.
     */
    fun obtain(width: Int, height: Int): FrameSlot? {
        while (true) {
            val spare = buffer.takeSpare() ?: break
            if (spare.matches(width, height)) return spare
            // A spare is held by neither side, so it is safe to free here.
            spare.close()
        }
        if (buffer.isClosed) return null
        return FrameSlot(width, height)
    }

    /** Render thread: offers a written slot. False, with the slot freed, when the composable has gone. */
    fun publish(slot: FrameSlot): Boolean {
        if (buffer.publish(slot)) return true
        slot.close()
        return false
    }

    /** UI thread: the newest frame, or null before the first one. */
    fun acquire(): FrameSlot? = buffer.acquire()

    /** UI thread, once the composable has left: frees every slot not in the render thread's hands. */
    fun close() {
        buffer.close().forEach { it.close() }
    }
}
