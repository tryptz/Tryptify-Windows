package tf.monochrome.desktop.platform.windows

import android.util.Log
import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.platform.AppScope
import tf.monochrome.desktop.player.QueueManager
import tf.monochrome.desktop.player.engine.EngineController

/**
 * Windows' media controls for the player: the keyboard's media keys, the
 * "now playing" card in the volume flyout and on the lock screen, and the
 * buttons of a Bluetooth headset. Android got all of this from its
 * MediaSession; on Windows it is the System Media Transport Controls,
 * reached through native/smtc.
 *
 * Every native call runs on one thread of its own, which the native side
 * puts in the multithreaded COM apartment. Button presses arrive on a WinRT
 * pool thread and are handed to the engine controller.
 */
@Singleton
class MediaTransportControls @Inject constructor(
    private val engineController: EngineController,
    private val queueManager: QueueManager,
    @AppScope private val scope: CoroutineScope,
) {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "smtc").apply { isDaemon = true } }
    private val dispatcher = thread.asCoroutineDispatcher()
    @Volatile private var handle = 0L

    private val callback = object : SmtcNative.Callback {
        override fun onButton(button: Int) {
            when (button) {
                // Play and pause are distinct buttons on a headset; a toggle
                // would turn "pause" into "play" if the two got out of step.
                SmtcNative.BUTTON_PLAY -> scope.launch { onUi { if (!engineController.engine.isPlaying) engineController.togglePlayPause() } }
                SmtcNative.BUTTON_PAUSE, SmtcNative.BUTTON_STOP ->
                    scope.launch { onUi { if (engineController.engine.isPlaying) engineController.togglePlayPause() } }
                SmtcNative.BUTTON_NEXT -> scope.launch { onUi { engineController.skipToNext() } }
                SmtcNative.BUTTON_PREVIOUS -> scope.launch { onUi { engineController.skipToPrevious() } }
            }
        }

        override fun onSeek(positionMs: Long) {
            scope.launch { onUi { engineController.seekTo(positionMs) } }
        }
    }

    /** Attaches to the main window; [hwnd] is ComposeWindow.windowHandle. A no-op where SMTC is unavailable. */
    fun attach(hwnd: Long) {
        if (handle != 0L || hwnd == 0L || !SmtcNative.isAvailable) return
        scope.launch {
            val h = withContext(dispatcher) { SmtcNative.nativeAttach(hwnd, callback) }
            if (h == 0L) {
                Log.w(TAG, "System Media Transport Controls unavailable for this window")
                return@launch
            }
            handle = h
            observe()
        }
    }

    private fun observe() {
        scope.launch {
            queueManager.currentTrack.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { track ->
                withContext(dispatcher) { publishMetadata(track) }
            }
        }
        scope.launch {
            combine(engineController.isPlaying, queueManager.currentTrack) { playing, track -> playing to (track != null) }
                .distinctUntilChanged()
                .collect { (playing, hasTrack) ->
                    val status = when {
                        !hasTrack -> SmtcNative.STATUS_STOPPED
                        playing -> SmtcNative.STATUS_PLAYING
                        else -> SmtcNative.STATUS_PAUSED
                    }
                    withContext(dispatcher) { SmtcNative.nativeSetPlaybackStatus(handle, status) }
                }
        }
        scope.launch {
            combine(queueManager.queue, queueManager.currentTrack) { queue, track ->
                val index = queue.indexOfFirst { it.id == track?.id }
                index >= 0 && index < queue.lastIndex
            }.distinctUntilChanged().collect { canNext ->
                // Previous also restarts the current track, so it is usable even on the first one.
                withContext(dispatcher) { SmtcNative.nativeSetNavigation(handle, true, canNext) }
            }
        }
        // Windows reads the timeline when the card opens; a few seconds of drift is invisible there.
        scope.launch {
            while (isActive) {
                // The engine's state is volatile and readable from any thread.
                val engine = engineController.engine
                val position = engine.currentPosition
                val duration = engine.duration
                if (duration > 0) withContext(dispatcher) { SmtcNative.nativeSetTimeline(handle, position, duration, true) }
                delay(TIMELINE_INTERVAL_MS)
            }
        }
    }

    private fun publishMetadata(track: Track?) {
        if (track == null) {
            SmtcNative.nativeSetMetadata(handle, null, null, null, null, 0, null)
            return
        }
        SmtcNative.nativeSetMetadata(
            handle,
            track.title,
            track.artists.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: track.artist?.name,
            track.album?.title,
            track.album?.artist?.name,
            track.trackNumber ?: 0,
            thumbnailFor(track.coverUrl),
        )
    }

    /** SMTC fetches http(s) itself; a local cover has to be a real path. */
    private fun thumbnailFor(cover: String?): String? = when {
        cover.isNullOrBlank() -> null
        cover.startsWith("http://") || cover.startsWith("https://") -> cover
        cover.startsWith("file:") -> runCatching { File(URI(cover)).absolutePath }.getOrNull()
        File(cover).isAbsolute -> cover
        else -> null
    }

    private suspend fun <T> onUi(block: () -> T): T = withContext(Dispatchers.Swing) { block() }

    private companion object {
        const val TAG = "MediaTransportControls"
        const val TIMELINE_INTERVAL_MS = 5_000L
    }
}
