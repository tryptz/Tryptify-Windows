package tf.monochrome.desktop.visualizer.gl

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL33C.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_FRAMEBUFFER
import org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL33C.glBindFramebuffer
import org.lwjgl.opengl.GL33C.glClear
import org.lwjgl.opengl.GL33C.glClearColor
import org.lwjgl.opengl.GL33C.glColorMask
import org.lwjgl.opengl.GL33C.glDisable
import org.lwjgl.opengl.GL33C.glViewport
import tf.monochrome.desktop.visualizer.AlbumPixels
import tf.monochrome.desktop.visualizer.AmbientCompositePass
import tf.monochrome.desktop.visualizer.ProjectMEngineRepository

/**
 * The one OpenGL context the visualizer renders in, and the one thread that
 * owns it.
 *
 * On Android each visualizer view brought its own surface and context — the
 * hero a GLSurfaceView, the ambient overlay a TextureView with an EGL thread —
 * and `ProjectMEngineRepository` rebuilt the engine whenever a new one
 * attached, because projectM's GL objects belong to the context that made
 * them. Here there is a single context, on a hidden window, shared by every
 * visualizer composable in the process, and the repository sees a single
 * surface: this host's. It attaches the engine when the first composable
 * arrives and detaches it after the last has gone, so the rule the refcount
 * enforced — one context's GL objects, one renderer — holds by construction.
 *
 * Which composable gets the frames is decided here instead: the first attached
 * that is still attached. The hero and the ambient layer are never meant to be
 * on screen together (docs/ui-invariants.md), but their enter and exit
 * transitions overlap by a frame or two, and in that window the newcomer shows
 * nothing rather than a frame rendered at the other's size and composition.
 *
 * ## What runs where
 *
 * Only the render thread touches GLFW, the context, projectM (through the
 * repository's GL-thread entry points: `onSurfaceAttached`, `onSurfaceResized`,
 * `renderFrame`, `onSurfaceDetached`), the composite pass and the readback.
 * The UI thread only attaches and detaches clients, writes their volatile
 * fields and asks for frames; those calls take [lock] and nothing else, and
 * the render thread never calls into the repository while holding it, so the
 * repository's own lock and this one are never taken in opposite orders.
 *
 * ## Pacing
 *
 * There is no swap and so no vsync: the composables ask for a frame from
 * Compose's frame clock, once per display refresh while playing, and
 * [FramePacer] holds the rate to [MAX_VISUALIZER_FPS] on faster panels. With
 * the "Disable vsync" setting on, the engine's Target FPS (up to the same
 * ceiling) is paced exactly instead, so that the engine's own cap — which only
 * runs with vsync off, and drops any frame that comes sooner than one interval
 * — never sees an early one. Nothing renders while nothing asks: paused, a
 * minimised window (Compose stops its frame clock) or no composable at all.
 */
internal object ProjectMGlHost {
    private const val TAG = "ProjectMGlHost"

    /**
     * How long the context outlives the last composable. Switching between the
     * hero and the ambient layer detaches one and attaches the other a frame
     * later; tearing down in between would rebuild projectM, playlist scan
     * and all, for nothing.
     */
    private const val LINGER_NANOS = 3_000_000_000L

    /** How long a new surface size has to hold before projectM is resized to it, see [ResizeSettler]. */
    private const val RESIZE_SETTLE_NANOS = 150_000_000L

    /** Early-request allowance with vsync on, see [FramePacer]. */
    private const val PACING_TOLERANCE_NANOS = 2_000_000L

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()

    /** Attach order. The first one owns the frames. Guarded by [lock]. */
    private val clients = ArrayList<VisualizerClient>()

    /** Guarded by [lock]. */
    private var frameRequested = false

    /** Guarded by [lock]. */
    private var thread: RenderThread? = null

    /**
     * Why there is no OpenGL 3.3 context, once that has been found out. Not
     * retried in this process: what is missing is a driver, and it does not
     * appear while the app runs.
     */
    @Volatile
    private var unavailableReason: String? = null

    /** UI thread: [client] has entered composition and wants frames. */
    fun attach(client: VisualizerClient) {
        unavailableReason?.let { reason ->
            reportUnavailable(listOf(client), reason)
            return
        }
        lock.withLock {
            if (client !in clients) clients += client
            frameRequested = true
            val current = thread
            if (current == null || current.exiting) {
                thread = RenderThread(previous = current).also { it.start() }
            }
            wake.signalAll()
        }
    }

    /** UI thread: [client] has left composition. */
    fun detach(client: VisualizerClient) {
        lock.withLock {
            clients.remove(client)
            // Wakes the thread so the next owner, if any, gets a frame at its
            // own size, and so the linger countdown starts now.
            frameRequested = true
            wake.signalAll()
        }
    }

    /** Any thread: draw a frame for the owner as soon as pacing allows. */
    fun requestFrame() {
        lock.withLock {
            frameRequested = true
            wake.signalAll()
        }
    }

    /**
     * Render thread: blocks until there is a frame to draw and returns whose,
     * or returns null once no composable has wanted one for [LINGER_NANOS],
     * having marked [self] as exiting so the next [attach] starts afresh.
     */
    private fun awaitWork(self: RenderThread, settler: ResizeSettler): VisualizerClient? {
        lock.lock()
        try {
            return awaitWorkLocked(self, settler)
        } finally {
            lock.unlock()
        }
    }

    private fun awaitWorkLocked(self: RenderThread, settler: ResizeSettler): VisualizerClient? {
        var idleSince = 0L
        while (true) {
            val owner = clients.firstOrNull()
            val now = System.nanoTime()
            if (owner == null) {
                if (idleSince == 0L) idleSince = now
                val left = LINGER_NANOS - (now - idleSince)
                if (left <= 0L) {
                    self.exiting = true
                    return null
                }
                wake.awaitNanos(left)
                continue
            }
            idleSince = 0L
            if (frameRequested) {
                frameRequested = false
                return owner
            }
            // A resize waiting to settle needs one more frame once it has,
            // even when nothing else will ask for one (playback paused).
            val settle = settler.pendingNanos(now)
            if (settle > 0L) {
                if (wake.awaitNanos(settle) <= 0L) return owner
                continue
            }
            wake.await()
        }
    }

    /** Render thread: waits out the pacer, waking early only to be told to stop. */
    private fun sleepNanos(nanos: Long) {
        lock.withLock { wake.awaitNanos(nanos) }
    }

    private fun markRequested() = lock.withLock { frameRequested = true }

    private fun clientsSnapshot(): List<VisualizerClient> = lock.withLock { clients.toList() }

    /**
     * Tells the engine there will be no projectM, so the player shows the
     * fallback visualizer and the ordinary blurred background instead of an
     * empty surface. `reportAudioTapFailure` is the repository's one way to
     * put the engine into FALLBACK with a message; the name is Android's.
     */
    private fun reportUnavailable(affected: List<VisualizerClient>, reason: String) {
        affected.map { it.repository }.distinct().forEach { repository ->
            repository.reportAudioTapFailure("OpenGL 3.3 is not available ($reason). Showing fallback visualizer.")
        }
    }

    private class RenderThread(private val previous: RenderThread?) : Thread("ProjectMGl") {
        /** Set, under [lock], once this thread has decided to end; a later attach then starts a new one. */
        @Volatile
        var exiting = false

        init {
            // Never holds the process open; an exit mid-frame only loses a
            // window the system reclaims anyway.
            isDaemon = true
        }

        override fun run() {
            // GLFW is process-global: the next session's init must not overlap
            // the last one's terminate.
            previous?.let { runCatching { it.join() } }

            val context = OffscreenGlContext.create().getOrElse { error ->
                val reason = error.message ?: error.toString()
                Log.e(TAG, "no OpenGL context for the visualizer: $reason")
                unavailableReason = reason
                lock.withLock {
                    exiting = true
                    if (thread === this) thread = null
                }
                reportUnavailable(clientsSnapshot(), reason)
                return
            }

            val pass = AmbientCompositePass()
            val readback = PixelReadback()
            val settler = ResizeSettler(RESIZE_SETTLE_NANOS)
            var engine: ProjectMEngineRepository? = null
            var renderSize = PixelSize.Zero
            var targetSize = PixelSize.Zero
            var lastOwner: VisualizerClient? = null
            var albumOwner: VisualizerClient? = null
            var albumShown: AlbumPixels? = null
            var pacer = FramePacer(MAX_VISUALIZER_FPS, PACING_TOLERANCE_NANOS)
            var pacing = Pair(MAX_VISUALIZER_FPS, PACING_TOLERANCE_NANOS)

            try {
                if (!pass.ensureCreated()) {
                    Log.e(TAG, "ambient composite unavailable; the ambient layer will show projectM's raw frame")
                }
                while (true) {
                    val owner = awaitWork(this, settler) ?: break
                    context.pollEvents()

                    // A new owner: a frame in flight was rendered for the last
                    // one, at its size and in its composition, and the new
                    // owner's size is taken at once rather than settled, as it
                    // has nothing at the old size worth keeping.
                    if (owner !== lastOwner) {
                        readback.discardPending()
                        settler.reset()
                        lastOwner = owner
                    }
                    if (engine != null && engine !== owner.repository) {
                        detachEngine(engine)
                        engine = null
                    }

                    val surface = owner.surfaceSize
                    val wanted = settler.offer(renderSizeFor(surface.width, surface.height), System.nanoTime())
                    if (wanted.isEmpty) continue // not laid out yet; its first size asks for a frame
                    if (wanted != targetSize) {
                        renderSize = context.prepare(wanted)
                        if (renderSize.isEmpty) {
                            targetSize = PixelSize.Zero
                            continue
                        }
                        targetSize = wanted
                        pass.resize(renderSize)
                        readback.resize(renderSize)
                        engine?.onSurfaceResized(renderSize.width, renderSize.height)
                    }
                    val repository = engine ?: owner.repository.also {
                        it.onSurfaceAttached(renderSize.width, renderSize.height)
                        it.setRenderTrigger(this, ProjectMGlHost::requestFrame)
                        engine = it
                    }

                    val wantedPacing = if (repository.vsyncEnabled) {
                        Pair(MAX_VISUALIZER_FPS, PACING_TOLERANCE_NANOS)
                    } else {
                        Pair(min(MAX_VISUALIZER_FPS, repository.targetFrameRate.coerceAtLeast(1)), 0L)
                    }
                    if (wantedPacing != pacing) {
                        pacing = wantedPacing
                        pacer = FramePacer(wantedPacing.first, wantedPacing.second)
                    }
                    // One timestamp for the pacer and the engine, so that the
                    // engine's cap measures exactly the spacing the pacer kept.
                    val frameTime = System.nanoTime()
                    val wait = pacer.delayNanos(frameTime)
                    if (wait > 0L) {
                        // The request stands; take it again once its slot opens.
                        markRequested()
                        sleepNanos(wait)
                        continue
                    }

                    if (owner.ambient && (owner !== albumOwner || owner.album !== albumShown)) {
                        albumOwner = owner
                        albumShown = owner.album
                        pass.setAlbum(albumShown)
                    }
                    renderFrame(owner, repository, frameTime, pass, readback, renderSize)
                    pacer.onFrameRendered(frameTime)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "visualizer render thread stopped", t)
            } finally {
                lock.withLock { exiting = true }
                engine?.let { runCatching { detachEngine(it) } }
                runCatching { readback.release() }
                runCatching { pass.release() }
                runCatching { context.destroy() }
                lock.withLock { if (thread === this) thread = null }
            }
        }

        private fun detachEngine(repository: ProjectMEngineRepository) {
            repository.clearRenderTrigger(this)
            repository.onSurfaceDetached()
        }

        private fun renderFrame(
            owner: VisualizerClient,
            repository: ProjectMEngineRepository,
            frameTime: Long,
            pass: AmbientCompositePass,
            readback: PixelReadback,
            size: PixelSize,
        ) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            glViewport(0, 0, size.width, size.height)
            // Draws into the window's back buffer, or — capped, frozen while
            // paused, or not initialised — leaves the last frame there.
            repository.renderFrame(frameTime)

            var source = 0
            if (owner.ambient) {
                val settings = owner.ambientSettings
                if (pass.composite(settings.opacity, settings.blackPoint, settings.blend, owner.scrimTone)) {
                    source = pass.outputFramebuffer
                }
            }
            // No composite (the hero, or no cover yet): projectM's own frame,
            // whose alpha is whatever its last shader wrote. The Android
            // surfaces were opaque and ignored it; here Skia would not.
            if (source == 0) forceOpaqueAlpha()

            readback.capture(source)
            readback.deliver(keepInFlight = if (owner.playing) 1 else 0) { address, frameSize ->
                val slot = owner.frames.obtain(frameSize.width, frameSize.height) ?: return@deliver
                slot.copyFlippedFrom(address)
                if (owner.frames.publish(slot)) owner.onFramePublished()
            }
        }

        private fun forceOpaqueAlpha() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
            glDisable(GL_SCISSOR_TEST)
            glColorMask(false, false, false, true)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            glColorMask(true, true, true, true)
        }
    }
}
