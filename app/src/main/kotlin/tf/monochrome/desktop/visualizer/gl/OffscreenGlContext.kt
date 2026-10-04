package tf.monochrome.desktop.visualizer.gl

import android.util.Log
import org.lwjgl.glfw.GLFW.GLFW_ALPHA_BITS
import org.lwjgl.glfw.GLFW.GLFW_BLUE_BITS
import org.lwjgl.glfw.GLFW.GLFW_CLIENT_API
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_DECORATED
import org.lwjgl.glfw.GLFW.GLFW_DEPTH_BITS
import org.lwjgl.glfw.GLFW.GLFW_DOUBLEBUFFER
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_FOCUSED
import org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW
import org.lwjgl.glfw.GLFW.GLFW_GREEN_BITS
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_API
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM
import org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11
import org.lwjgl.glfw.GLFW.GLFW_RED_BITS
import org.lwjgl.glfw.GLFW.GLFW_RESIZABLE
import org.lwjgl.glfw.GLFW.GLFW_SAMPLES
import org.lwjgl.glfw.GLFW.GLFW_SCALE_TO_MONITOR
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwInitHint
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPlatformSupported
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSetErrorCallback
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_RENDERER
import org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL33C.GL_VERSION
import org.lwjgl.opengl.GL33C.glBindFramebuffer
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glColorMask
import org.lwjgl.opengl.GL33C.glDisable
import org.lwjgl.opengl.GL33C.glGetString
import org.lwjgl.opengl.GL33C.glViewport
import org.lwjgl.system.Platform

/**
 * An OpenGL 3.3 core context on a hidden GLFW window: what projectM renders
 * into on the desktop.
 *
 * ## Why a window, when nothing is ever shown
 *
 * projectM 4.1.6 will not render into a framebuffer of ours. `RenderFrame` ends
 * with a hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` and the C API
 * has no variant that takes a target, so its finished frame always lands in
 * the context's default framebuffer — and only a window (or a pbuffer, which
 * GLFW does not offer) has one. The window is never shown and never swapped;
 * its back buffer is simply a place for projectM to draw that the render
 * thread then reads from.
 *
 * ## Why the window never changes size
 *
 * It is made once, large enough for the biggest frame the visualizer renders
 * ([MAX_RENDER_LONG_SIDE] square, so either orientation fits), and every frame
 * is drawn into its bottom-left corner: projectM sets its viewport to the size
 * it was given, and the readback and the composite's blit take the same
 * rectangle. Resizing the window per frame size was the first design, and it
 * failed on the first driver it met: Mesa kept the hidden window's back buffer
 * at its creation size whatever the window became. Drivers commonly notice a
 * new size when a drawable is swapped or made current, and this one is
 * neither; whether a given Windows driver would is not something to find out
 * on a user's machine. A creation-time size is the one every driver honours.
 * The cost is the unused part of the buffer, a few tens of megabytes of video
 * memory while the visualizer is open.
 *
 * ## Threading
 *
 * GLFW is process-global and not thread-safe, and a GL context is current on
 * one thread at a time. Everything here — init, the window, every GL call
 * against the context, terminate — happens on the visualizer's render thread
 * ([ProjectMGlHost]), and only one such thread exists at a time. GLFW asks for
 * the process's main thread, but that requirement is macOS's: on Windows a
 * window belongs to the thread that made it and its messages are pumped there,
 * which is this thread, and on X11 GLFW keeps its own display connection.
 *
 * Every method must be called on the thread that called [create].
 */
internal class OffscreenGlContext private constructor(
    private val window: Long,
    private val errorCallback: GLFWErrorCallback,
    /** What the window's framebuffer holds, fixed for its lifetime. */
    val framebufferSize: PixelSize,
) {
    /**
     * The size to render a frame of [wanted] pixels at: [wanted] itself, or
     * less when the window came out smaller than asked (see [fitWithin]).
     * Clears the framebuffer, so a frame read back before projectM has drawn
     * at the new size is black rather than the old size's leftovers.
     */
    fun prepare(wanted: PixelSize): PixelSize {
        val actual = fitWithin(wanted, framebufferSize.width, framebufferSize.height)
        if (actual != wanted) {
            Log.w(TAG, "window framebuffer is $framebufferSize, rendering at $actual instead of $wanted")
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, framebufferSize.width, framebufferSize.height)
        glDisable(GL_SCISSOR_TEST)
        glColorMask(true, true, true, true)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        return actual
    }

    /** Lets the window process its messages; cheap, and called once per frame. */
    fun pollEvents() = glfwPollEvents()

    /** Destroys the context and the window, and shuts GLFW down. */
    fun destroy() {
        GL.setCapabilities(null)
        glfwMakeContextCurrent(0L)
        glfwDestroyWindow(window)
        glfwTerminate()
        glfwSetErrorCallback(null)
        errorCallback.free()
    }

    companion object {
        private const val TAG = "OffscreenGlContext"

        /**
         * Initialises GLFW and makes a hidden window with an OpenGL 3.3 core
         * context current on the calling thread. Returns the reason instead
         * when the machine cannot provide one — no GPU driver (Windows' own
         * GDI renderer stops at OpenGL 1.1), a remote session without GPU
         * access, a driver older than 3.3.
         */
        fun create(): Result<OffscreenGlContext> {
            var lastError: String? = null
            val errorCallback = GLFWErrorCallback.create { code, description ->
                val message = "GLFW error 0x${code.toString(16)}: ${GLFWErrorCallback.getDescription(description)}"
                lastError = message
                Log.w(TAG, message)
            }
            glfwSetErrorCallback(errorCallback)
            // Undoes everything below in reverse, whichever step failed.
            fun fail(window: Long, reason: String?): Result<OffscreenGlContext> {
                if (window != 0L) {
                    GL.setCapabilities(null)
                    glfwMakeContextCurrent(0L)
                    glfwDestroyWindow(window)
                }
                glfwTerminate()
                glfwSetErrorCallback(null)
                errorCallback.free()
                return Result.failure(IllegalStateException(reason ?: lastError ?: "no OpenGL 3.3 core context available"))
            }
            // Compose for Desktop runs on X11 (or XWayland) through AWT; asking
            // GLFW for the same keeps a Wayland session from handing us a
            // surface-less wl_surface for a window that is never mapped.
            if (Platform.get() == Platform.LINUX && glfwPlatformSupported(GLFW_PLATFORM_X11)) {
                glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
            }
            if (!glfwInit()) return fail(0L, lastError ?: "GLFW failed to initialise")

            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE)
            glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE)
            glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)
            // One framebuffer pixel per window unit, whatever the monitor's scale.
            glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_FALSE)
            glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API)
            // projectM's desktop renderer is written against 3.3 core, the
            // same profile its own SDL front end asks for.
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
            glfwWindowHint(GLFW_RED_BITS, 8)
            glfwWindowHint(GLFW_GREEN_BITS, 8)
            glfwWindowHint(GLFW_BLUE_BITS, 8)
            glfwWindowHint(GLFW_ALPHA_BITS, 8)
            // Presets draw geometry that expects a depth buffer, as the
            // Android surfaces were configured with one.
            glfwWindowHint(GLFW_DEPTH_BITS, 16)
            glfwWindowHint(GLFW_SAMPLES, 0)
            // Double-buffered and never swapped: the back buffer keeps what
            // projectM last drew, so a frame the engine skips (the paused
            // freeze, the Target FPS cap) still reads back as the last one.
            glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_TRUE)

            val window = glfwCreateWindow(WINDOW_SIZE, WINDOW_SIZE, "Tryptify visualizer", 0L, 0L)
            if (window == 0L) return fail(0L, null)
            glfwMakeContextCurrent(window)
            val capabilities = runCatching { GL.createCapabilities() }.getOrElse { error ->
                return fail(window, error.message ?: error.toString())
            }
            if (!capabilities.OpenGL33) {
                return fail(window, "OpenGL 3.3 is not supported: ${glGetStringOrNull(GL_VERSION)}")
            }
            // What the system actually gave: Windows clamps a window to what
            // its monitor can show, and frames are fitted to whatever this is.
            val w = IntArray(1)
            val h = IntArray(1)
            glfwGetFramebufferSize(window, w, h)
            val size = PixelSize(w[0], h[0])
            if (size.isEmpty) return fail(window, "the visualizer window has no framebuffer")
            Log.i(TAG, "OpenGL ${glGetStringOrNull(GL_VERSION)} on ${glGetStringOrNull(GL_RENDERER)}, framebuffer $size")
            return Result.success(OffscreenGlContext(window, errorCallback, size))
        }

        private fun glGetStringOrNull(name: Int): String? = runCatching { glGetString(name) }.getOrNull()

        /** Square, so a frame at the render cap fits in either orientation. */
        private const val WINDOW_SIZE = MAX_RENDER_LONG_SIDE
    }
}
