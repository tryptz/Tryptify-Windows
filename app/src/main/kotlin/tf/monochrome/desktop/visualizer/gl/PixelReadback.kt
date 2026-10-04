package tf.monochrome.desktop.visualizer.gl

import org.lwjgl.opengl.GL33C.GL_BACK
import org.lwjgl.opengl.GL33C.GL_BGRA
import org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL33C.GL_MAP_READ_BIT
import org.lwjgl.opengl.GL33C.GL_PACK_ALIGNMENT
import org.lwjgl.opengl.GL33C.GL_PIXEL_PACK_BUFFER
import org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_STREAM_READ
import org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL33C.glBindBuffer
import org.lwjgl.opengl.GL33C.glBindFramebuffer
import org.lwjgl.opengl.GL33C.glBufferData
import org.lwjgl.opengl.GL33C.glDeleteBuffers
import org.lwjgl.opengl.GL33C.glGenBuffers
import org.lwjgl.opengl.GL33C.glPixelStorei
import org.lwjgl.opengl.GL33C.glReadBuffer
import org.lwjgl.opengl.GL33C.glReadPixels
import org.lwjgl.opengl.GL33C.glUnmapBuffer
import org.lwjgl.opengl.GL33C.nglMapBufferRange

/**
 * Reads finished frames back to the CPU through a pair of pixel buffer
 * objects, a frame behind.
 *
 * `glReadPixels` into client memory stalls the thread until the GPU has
 * finished everything queued before it, every frame. Into a pixel buffer
 * object it only queues a copy and returns; mapping that buffer is what waits,
 * so mapping it a frame later — once the next frame has been queued behind it
 * — finds the copy long finished. Two buffers, so the frame being read back
 * and the frame being mapped never share one.
 *
 * The frame a buffer holds is BGRA, bottom row first, exactly as GL stores it;
 * [FrameSlot.copyFlippedFrom] turns it the right way up on its way into Skia.
 *
 * Every method must be called on the render thread with the context current.
 */
internal class PixelReadback {
    private val buffers = IntArray(2)
    private var size = PixelSize.Zero
    private var next = 0

    /** Buffers holding a frame not yet delivered, oldest first. */
    private val pending = ArrayDeque<Int>()

    val frameSize: PixelSize get() = size

    /** (Re)allocates both buffers for frames of [newSize], dropping any frame in flight. */
    fun resize(newSize: PixelSize) {
        if (newSize == size && buffers[0] != 0) return
        release()
        size = newSize
        if (newSize.isEmpty) return
        val bytes = newSize.width.toLong() * newSize.height * 4
        for (i in buffers.indices) {
            buffers[i] = glGenBuffers()
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[i])
            glBufferData(GL_PIXEL_PACK_BUFFER, bytes, GL_STREAM_READ)
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
    }

    /**
     * Queues a copy of [framebuffer]'s colour (0 being the window's back
     * buffer, where projectM leaves its frame) into the next free buffer.
     */
    fun capture(framebuffer: Int) {
        if (size.isEmpty || buffers[0] == 0) return
        // Both buffers busy means a frame was never collected; it is the
        // oldest, and dropping it is right — the newer one replaces it anyway.
        if (pending.size == buffers.size) pending.removeFirst()
        val index = next
        next = (next + 1) % buffers.size
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
        glReadBuffer(if (framebuffer == 0) GL_BACK else GL_COLOR_ATTACHMENT0)
        glPixelStorei(GL_PACK_ALIGNMENT, 4)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[index])
        glReadPixels(0, 0, size.width, size.height, GL_BGRA, GL_UNSIGNED_BYTE, 0L)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        pending.addLast(index)
    }

    /**
     * Hands every captured frame but the newest [keepInFlight] to [deliver],
     * oldest first, as the address of its pixels. The address is only valid
     * during the call.
     *
     * While frames keep coming, one stays in flight so its mapping never
     * waits. When they stop — playback paused, a single frame asked for —
     * nothing would ever follow to push the last one out, so the caller drains
     * with 0 and takes the short wait instead.
     */
    fun deliver(keepInFlight: Int, deliver: (address: Long, size: PixelSize) -> Unit) {
        val bytes = size.width.toLong() * size.height * 4
        while (pending.size > keepInFlight) {
            val index = pending.removeFirst()
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[index])
            val address = nglMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, bytes, GL_MAP_READ_BIT)
            try {
                if (address != 0L) deliver(address, size)
            } finally {
                if (address != 0L) glUnmapBuffer(GL_PIXEL_PACK_BUFFER)
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
            }
        }
    }

    /** Forgets frames in flight; they were meant for a composable that no longer gets frames. */
    fun discardPending() = pending.clear()

    fun release() {
        pending.clear()
        for (i in buffers.indices) {
            if (buffers[i] != 0) {
                glDeleteBuffers(buffers[i])
                buffers[i] = 0
            }
        }
        size = PixelSize.Zero
        next = 0
    }
}
