package tf.monochrome.desktop.visualizer

import android.util.Log
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL33C.GL_ARRAY_BUFFER
import org.lwjgl.opengl.GL33C.GL_BACK
import org.lwjgl.opengl.GL33C.GL_BLEND
import org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE
import org.lwjgl.opengl.GL33C.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_CULL_FACE
import org.lwjgl.opengl.GL33C.GL_DEPTH_TEST
import org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_FLOAT
import org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL33C.GL_LINEAR
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_NEAREST
import org.lwjgl.opengl.GL33C.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_RGBA
import org.lwjgl.opengl.GL33C.GL_RGBA8
import org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL33C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL33C.GL_TEXTURE0
import org.lwjgl.opengl.GL33C.GL_TEXTURE1
import org.lwjgl.opengl.GL33C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S
import org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T
import org.lwjgl.opengl.GL33C.GL_TRIANGLE_STRIP
import org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT
import org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL33C.glActiveTexture
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glBindBuffer
import org.lwjgl.opengl.GL33C.glBindFramebuffer
import org.lwjgl.opengl.GL33C.glBindTexture
import org.lwjgl.opengl.GL33C.glBindVertexArray
import org.lwjgl.opengl.GL33C.glBlitFramebuffer
import org.lwjgl.opengl.GL33C.glBufferData
import org.lwjgl.opengl.GL33C.glCheckFramebufferStatus
import org.lwjgl.opengl.GL33C.glColorMask
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glDeleteBuffers
import org.lwjgl.opengl.GL33C.glDeleteFramebuffers
import org.lwjgl.opengl.GL33C.glDeleteProgram
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glDeleteTextures
import org.lwjgl.opengl.GL33C.glDeleteVertexArrays
import org.lwjgl.opengl.GL33C.glDisable
import org.lwjgl.opengl.GL33C.glDrawArrays
import org.lwjgl.opengl.GL33C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL33C.glFramebufferTexture2D
import org.lwjgl.opengl.GL33C.glGenBuffers
import org.lwjgl.opengl.GL33C.glGenFramebuffers
import org.lwjgl.opengl.GL33C.glGenTextures
import org.lwjgl.opengl.GL33C.glGenVertexArrays
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glGetUniformLocation
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glPixelStorei
import org.lwjgl.opengl.GL33C.glReadBuffer
import org.lwjgl.opengl.GL33C.glShaderSource
import org.lwjgl.opengl.GL33C.glTexImage2D
import org.lwjgl.opengl.GL33C.glTexParameteri
import org.lwjgl.opengl.GL33C.glUniform1f
import org.lwjgl.opengl.GL33C.glUniform1i
import org.lwjgl.opengl.GL33C.glUniform3f
import org.lwjgl.opengl.GL33C.glUseProgram
import org.lwjgl.opengl.GL33C.glVertexAttribPointer
import org.lwjgl.opengl.GL33C.glViewport
import org.lwjgl.system.MemoryUtil
import tf.monochrome.desktop.visualizer.gl.PixelSize
import tf.monochrome.desktop.visualizer.gl.desktopGlsl

/**
 * Turns what projectM drew into a translucent layer over the album artwork,
 * entirely on the GPU.
 *
 * ## Why the visualizer is copied rather than rendered into a target
 *
 * The obvious design is to point projectM at our own framebuffer and composite
 * from its texture. projectM 4.1.6 will not do it: `ProjectM::RenderFrame`
 * ends with a hardcoded `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)` — there is
 * a `ToDo: Allow external apps to provide a custom target framebuffer` sitting
 * on the very line — and the C API exposes only `projectm_opengl_render_frame`,
 * with no FBO-taking variant to call instead.
 *
 * So projectM draws where it insists on drawing, the default framebuffer, and
 * [composite] blits that straight into a texture with `glBlitFramebuffer`. That
 * is a GPU-side copy, and preset feedback is untouched either way: trails,
 * warps and glow accumulate in projectM's own internal FBOs; the default
 * framebuffer only ever receives the finished frame.
 *
 * ## Desktop: the composite lands in a framebuffer of its own
 *
 * On Android the pass then drew over the default framebuffer and the frame was
 * swapped onto the screen. Here the default framebuffer belongs to a hidden
 * window that is never swapped, and keeping projectM's frame there is what
 * makes a frame the engine skips — the paused freeze, the Target FPS cap —
 * read back as the last one. Compositing over it would composite the next
 * frame over a composite. So the result goes to [outputFramebuffer], which the
 * render thread reads back into Compose. The shaders are the Android app's,
 * restated for GLSL 3.30 by [desktopGlsl] with the arithmetic untouched.
 *
 * Every method except [setAlbum] must be called on the thread holding the GL
 * context.
 */
internal class AmbientCompositePass {

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var captureTexture = 0
    private var captureFbo = 0
    private var outputTexture = 0
    private var outputFbo = 0
    private var albumTexture = 0
    private var width = 0
    private var height = 0
    private var ready = false

    private var uFx = -1
    private var uAlbum = -1
    private var uScrimTone = -1
    private var uOpacity = -1
    private var uBlackPoint = -1
    private var uKnee = -1
    private var uBlend = -1

    /** Set from the UI thread, consumed on the GL thread at the next frame. */
    @Volatile
    private var pendingAlbum: AlbumPixels? = null

    @Volatile
    private var albumUploaded = false

    /** Where [composite] leaves its result, for the readback. 0 until [resize]. */
    val outputFramebuffer: Int get() = outputFbo

    fun setAlbum(pixels: AlbumPixels?) {
        pendingAlbum = pixels
        albumUploaded = false
    }

    fun ensureCreated(): Boolean {
        if (ready) return true
        val vertex = compile(GL_VERTEX_SHADER, desktopGlsl(AMBIENT_VERTEX_SHADER))
        val fragment = compile(GL_FRAGMENT_SHADER, desktopGlsl(AMBIENT_FRAGMENT_SHADER))
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) glDeleteShader(vertex)
            if (fragment != 0) glDeleteShader(fragment)
            return false
        }

        program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        // The shaders are attached and can go either way; the program keeps
        // its own reference until it is deleted.
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
            Log.e(TAG, "composite program link failed: ${glGetProgramInfoLog(program)}")
            glDeleteProgram(program)
            program = 0
            return false
        }

        uFx = glGetUniformLocation(program, "uFx")
        uAlbum = glGetUniformLocation(program, "uAlbum")
        uScrimTone = glGetUniformLocation(program, "uScrimTone")
        uOpacity = glGetUniformLocation(program, "uOpacity")
        uBlackPoint = glGetUniformLocation(program, "uBlackPoint")
        uKnee = glGetUniformLocation(program, "uKnee")
        uBlend = glGetUniformLocation(program, "uBlend")

        // A full-screen triangle strip in clip space. The vertex stage derives
        // both texture coordinates from it, so there is nothing else to upload.
        // A core profile draws nothing without a vertex array object bound.
        vao = glGenVertexArrays()
        vbo = glGenBuffers()
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L)
        glBindVertexArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)

        albumTexture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, albumTexture)
        // Linear on a deliberately tiny cover: upscaling a 128px thumbnail with
        // bilinear filtering *is* the 64dp blur the Compose backdrop applies,
        // for one small texture instead of a full-screen gaussian per frame.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)

        ready = true
        return true
    }

    fun resize(size: PixelSize) {
        if (size.isEmpty) return
        if (size.width == width && size.height == height && captureFbo != 0) return
        width = size.width
        height = size.height
        releaseTargets()

        captureTexture = colorTexture(width, height)
        captureFbo = framebufferFor(captureTexture, "capture")
        outputTexture = colorTexture(width, height)
        outputFbo = framebufferFor(outputTexture, "output")
        if (captureFbo == 0 || outputFbo == 0) releaseTargets()
    }

    /**
     * Copy the frame projectM just drew, then draw the composite into
     * [outputFramebuffer].
     *
     * Returns false when it could not run, so the caller can read back
     * whatever projectM produced rather than presenting a blank frame.
     */
    fun composite(
        opacity: Float,
        blackPoint: Float,
        blend: VisualizerBlendMode,
        scrim: FloatArray,
    ): Boolean {
        if (!ready || captureFbo == 0 || outputFbo == 0 || width <= 0 || height <= 0) return false
        uploadPendingAlbum()
        if (!albumUploaded) return false

        // projectM leaves whatever state its last preset wanted, so everything
        // this pass depends on is set rather than assumed. A scissor left on
        // would clip the blit as well as the draw.
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        glReadBuffer(GL_BACK)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, captureFbo)
        glBlitFramebuffer(
            0, 0, width, height,
            0, 0, width, height,
            GL_COLOR_BUFFER_BIT, GL_NEAREST,
        )
        glBindFramebuffer(GL_FRAMEBUFFER, outputFbo)

        glViewport(0, 0, width, height)
        glDisable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glColorMask(true, true, true, true)

        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, captureTexture)
        glUniform1i(uFx, 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, albumTexture)
        glUniform1i(uAlbum, 1)
        glUniform3f(uScrimTone, scrim[0], scrim[1], scrim[2])
        glUniform1f(uOpacity, opacity)
        glUniform1f(uBlackPoint, blackPoint)
        glUniform1f(uKnee, AMBIENT_KNEE)
        glUniform1i(uBlend, blend.ordinal)

        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindVertexArray(0)
        glUseProgram(0)
        // Left on unit 0 so projectM's next frame does not inherit unit 1, and
        // on the default framebuffer, which is where projectM expects to be.
        glActiveTexture(GL_TEXTURE0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return true
    }

    private fun uploadPendingAlbum() {
        val album = pendingAlbum ?: return
        if (albumUploaded) return
        // Rows top first, as the Android pass uploaded a Bitmap; the vertex
        // stage's flipped vAlbum is written for exactly that.
        val pixels = MemoryUtil.memAlloc(album.rgba.size)
        try {
            pixels.put(album.rgba).flip()
            glBindTexture(GL_TEXTURE_2D, albumTexture)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, album.width, album.height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels,
            )
            glBindTexture(GL_TEXTURE_2D, 0)
        } finally {
            MemoryUtil.memFree(pixels)
        }
        albumUploaded = true
    }

    private fun colorTexture(width: Int, height: Int): Int {
        val texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, null as java.nio.ByteBuffer?,
        )
        // Sampled one-to-one, so nearest costs nothing and filters nothing away.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindTexture(GL_TEXTURE_2D, 0)
        return texture
    }

    private fun framebufferFor(texture: Int, name: String): Int {
        val framebuffer = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "$name framebuffer incomplete: 0x${status.toString(16)}")
            glDeleteFramebuffers(framebuffer)
            return 0
        }
        return framebuffer
    }

    private fun releaseTargets() {
        if (captureFbo != 0) glDeleteFramebuffers(captureFbo)
        if (captureTexture != 0) glDeleteTextures(captureTexture)
        if (outputFbo != 0) glDeleteFramebuffers(outputFbo)
        if (outputTexture != 0) glDeleteTextures(outputTexture)
        captureFbo = 0
        captureTexture = 0
        outputFbo = 0
        outputTexture = 0
    }

    fun release() {
        releaseTargets()
        if (albumTexture != 0) glDeleteTextures(albumTexture)
        if (vbo != 0) glDeleteBuffers(vbo)
        if (vao != 0) glDeleteVertexArrays(vao)
        if (program != 0) glDeleteProgram(program)
        albumTexture = 0
        vbo = 0
        vao = 0
        program = 0
        albumUploaded = false
        ready = false
        width = 0
        height = 0
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            Log.e(TAG, "shader compile failed: ${glGetShaderInfoLog(shader)}")
            glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "AmbientComposite"
    }
}

/**
 * The cover, as the pixels the composite samples: [width]×[height] RGBA rows,
 * top row first, premultiplied as Android's `GLUtils.texImage2D` uploaded a
 * Bitmap.
 *
 * A copy rather than the Bitmap itself, because the render thread uploads it
 * whenever it next draws and the Bitmap is the UI's: by then it may have been
 * replaced, or closed.
 */
internal class AlbumPixels(val width: Int, val height: Int, val rgba: ByteArray) {
    companion object {
        /** Copies [bitmap]'s pixels on the calling thread; null when it cannot be read. */
        fun of(bitmap: Bitmap): AlbumPixels? {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
            val rgba = runCatching { bitmap.readPixels(info, width * 4, 0, 0) }.getOrNull() ?: return null
            return AlbumPixels(width, height, rgba)
        }
    }
}
