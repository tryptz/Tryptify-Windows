package tf.monochrome.desktop.visualizer.gl

import org.jetbrains.skia.Bitmap
import tf.monochrome.desktop.visualizer.AlbumPixels
import tf.monochrome.desktop.visualizer.AmbientVisualizerSettings
import tf.monochrome.desktop.visualizer.ProjectMEngineRepository

/**
 * One visualizer composable, as the render thread sees it: what it wants
 * drawn, at what size, and where its frames go.
 *
 * Written by the UI thread, read by the render thread; every field is a
 * single volatile reference, so a reader always sees a whole value. The
 * setters are diff-aware for the reason the Android views' `update*` methods
 * were: the player recomposes many times a second while album colours
 * crossfade, and a frame per recomposition would be churn with nothing new to
 * show.
 */
internal class VisualizerClient(
    val repository: ProjectMEngineRepository,
    /** Composited over the blurred cover (ambient), or projectM's frame as it is (hero). */
    val ambient: Boolean,
) {
    val frames = FrameExchange()

    /** The composable's size in pixels; the render size follows it, see [renderSizeFor]. */
    @Volatile var surfaceSize: PixelSize = PixelSize.Zero

    /** Whether frames are wanted continuously, which decides how readback is pipelined. */
    @Volatile var playing: Boolean = false

    @Volatile var ambientSettings: AmbientVisualizerSettings = AmbientVisualizerSettings()
        private set

    @Volatile var scrimTone: FloatArray = floatArrayOf(0f, 0f, 0f)
        private set

    /** The cover's pixels, a new instance per cover; the render thread compares by identity. */
    @Volatile var album: AlbumPixels? = null
        private set

    /** Called on the render thread after each frame published to [frames]. */
    @Volatile var onFramePublished: () -> Unit = {}

    private var lastCover: Bitmap? = null

    /** UI thread. True when anything changed and a frame should be drawn to show it. */
    fun updateAmbient(settings: AmbientVisualizerSettings, red: Float, green: Float, blue: Float): Boolean {
        var changed = false
        if (settings != ambientSettings) {
            ambientSettings = settings
            changed = true
        }
        val tone = scrimTone
        if (tone[0] != red || tone[1] != green || tone[2] != blue) {
            scrimTone = floatArrayOf(red, green, blue)
            changed = true
        }
        return changed
    }

    /**
     * UI thread. Copies the cover's pixels when the cover is a different
     * Bitmap from last time.
     *
     * Identity, not equality: a new track decodes a new Bitmap instance, and a
     * recomposition re-presenting the same instance must not copy it again.
     */
    fun updateCover(cover: Bitmap?): Boolean {
        if (cover === lastCover) return false
        lastCover = cover
        album = cover?.let { AlbumPixels.of(it) }
        return true
    }
}
