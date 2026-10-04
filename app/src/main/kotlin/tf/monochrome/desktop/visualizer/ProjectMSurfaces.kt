package tf.monochrome.desktop.visualizer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.visualizer.gl.PixelSize
import tf.monochrome.desktop.visualizer.gl.ProjectMGlHost
import tf.monochrome.desktop.visualizer.gl.VisualizerClient

/*
 * The two framings of the projectM engine, as composables.
 *
 * On Android these were views: the hero a GLSurfaceView that replaced the
 * artwork, the ambient layer a TextureView compositing projectM over the
 * blurred cover. Neither exists on the desktop, and neither is needed: the
 * engine renders offscreen on its own thread (visualizer/gl/), and each frame
 * arrives here as a Skia bitmap drawn like any other image. That also keeps
 * the glass layering exactly as the player declares it — a frame is an
 * ordinary node in the Compose tree, under the controls and the glass, with
 * none of the SurfaceView z-order rules docs/ui-invariants.md works around.
 *
 * Only one of the two may be on screen at a time, as on Android. The engine
 * lives in a single GL context either way, and if both are composed for a
 * moment (one's exit overlapping the other's entry) the one that arrived
 * first keeps the frames until it leaves; see ProjectMGlHost.
 */

/**
 * The hero visualizer: projectM's frame as it is, in place of the artwork.
 * Replaces Android's `AndroidView { ProjectMRendererView }`.
 */
@Composable
fun ProjectMHeroSurface(
    repository: ProjectMEngineRepository,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val client = rememberVisualizerClient(repository, ambient = false)
    PlaybackFrames(client, repository, isPlaying)
    VisualizerFrame(client, modifier)
}

/**
 * The ambient layer: projectM composited over the blurred [cover] and the
 * mid-screen scrim ([scrimRed], [scrimGreen], [scrimBlue]), as the player's
 * background. Replaces Android's `AndroidView { ProjectMOverlayView }`.
 *
 * Opaque, like the view it replaces: it draws the backdrop itself, so it
 * stands in for `PlayerBlurredArtBackground` rather than layering over it.
 * Until a cover has arrived it shows projectM's raw frame.
 */
@Composable
fun ProjectMAmbientSurface(
    repository: ProjectMEngineRepository,
    settings: AmbientVisualizerSettings,
    cover: android.graphics.Bitmap?,
    scrimRed: Float,
    scrimGreen: Float,
    scrimBlue: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val client = rememberVisualizerClient(repository, ambient = true)
    // After every recomposition, like the AndroidView update block, and as
    // cheap: both setters compare against what they last took and only a real
    // change costs a frame.
    SideEffect {
        val ambientChanged = client.updateAmbient(settings, scrimRed, scrimGreen, scrimBlue)
        val coverChanged = client.updateCover(cover)
        if (ambientChanged || coverChanged) ProjectMGlHost.requestFrame()
    }
    PlaybackFrames(client, repository, isPlaying)
    VisualizerFrame(client, modifier)
}

/**
 * Engine calls that can wait on its lock, which the render thread holds while
 * it draws and, on first use, while the preset pack installs. Kept off the UI
 * thread, and on one thread so they land in the order they were made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val engineCalls = Dispatchers.Default.limitedParallelism(1)

@Composable
private fun rememberVisualizerClient(repository: ProjectMEngineRepository, ambient: Boolean): VisualizerClient {
    val client = remember(repository, ambient) { VisualizerClient(repository, ambient) }
    DisposableEffect(client) {
        ProjectMGlHost.attach(client)
        onDispose {
            ProjectMGlHost.detach(client)
            // After the detach, so no new frame is aimed at it; a frame the
            // render thread is already writing is freed by the render thread
            // when its publish is refused.
            client.frames.close()
        }
    }
    return client
}

/**
 * Frames on demand: one per display refresh while playing, one when playback
 * stops — a paused visualizer still has to land the frame it stopped on, and
 * the ambient layer has to composite over it — and none otherwise.
 */
@Composable
private fun PlaybackFrames(client: VisualizerClient, repository: ProjectMEngineRepository, isPlaying: Boolean) {
    LaunchedEffect(client, isPlaying) {
        client.playing = isPlaying
        withContext(engineCalls) { repository.setPlaybackPaused(!isPlaying) }
        ProjectMGlHost.requestFrame()
        if (!isPlaying) return@LaunchedEffect
        // Compose's frame clock is the display's, and it stops while the window
        // is minimised, which is what pauses the visualizer when it cannot be seen.
        while (true) {
            withFrameNanos { ProjectMGlHost.requestFrame() }
        }
    }
}

/**
 * Draws the newest frame, scaled to the composable. Nothing before the first
 * one: what is behind shows through, as it did around an Android surface that
 * had not drawn yet.
 */
@Composable
private fun VisualizerFrame(client: VisualizerClient, modifier: Modifier) {
    // Bumped by the render thread for every frame it publishes. Read only in
    // the draw block, so a new frame redraws this node and recomposes nothing.
    val frameTick = remember(client) { mutableIntStateOf(0) }
    DisposableEffect(client) {
        client.onFramePublished = { frameTick.intValue += 1 }
        onDispose { client.onFramePublished = {} }
    }
    Spacer(
        modifier
            .onSizeChanged { size ->
                client.surfaceSize = PixelSize(size.width, size.height)
                ProjectMGlHost.requestFrame()
            }
            .drawBehind {
                frameTick.intValue
                val frame = client.frames.acquire() ?: return@drawBehind
                drawImage(
                    image = frame.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    // Bilinear: frames are rendered at up to 1080p and drawn at
                    // whatever the panel is.
                    filterQuality = FilterQuality.Low,
                )
            },
    )
}
