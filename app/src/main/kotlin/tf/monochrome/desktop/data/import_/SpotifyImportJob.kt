package tf.monochrome.desktop.data.import_

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import tf.monochrome.desktop.R
import tf.monochrome.desktop.data.downloads.AppNotifier
import tf.monochrome.desktop.platform.AppScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a Spotify playlist import outside any screen's lifetime.
 *
 * Large playlists take minutes (one catalog search per track), and running the
 * import in a ViewModel scope meant leaving the screen killed it halfway. On
 * Android this was a foreground `Service` holding a progress notification; on
 * the desktop the app-wide scope outlives every screen, so the job is a plain
 * singleton: [importUrl], [importPlaylist] and [importLikedSongs] start one,
 * [cancel] stops it, [state] says where it is, and the ongoing/summary
 * notifications go through the app's [tf.monochrome.desktop.data.downloads.Notifier].
 *
 * Progress is read from the shared [PlaylistImportService.progress] flow —
 * the same one Settings renders — so the in-app counters and the
 * notification can never disagree.
 */
@Singleton
class SpotifyImportJob @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val playlistImporter: PlaylistImporter,
    private val importService: PlaylistImportService,
    private val notifier: AppNotifier,
) {

    /** The job's own lifecycle; the track-by-track counts are on [progress]. */
    sealed interface State {
        data object Idle : State

        /** [current]/[total] are 0 while the playlist is still being fetched. */
        data class Running(val text: String, val current: Int, val total: Int) : State

        data class Finished(val success: Boolean, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The shared import progress, re-exported so a caller needs only this class. */
    val progress: StateFlow<ImportProgress> get() = importService.progress

    @Volatile
    private var importJob: Job? = null

    // Set once per import by whichever side reports its end first — the import
    // finishing, or [cancel] — so a cancel racing the last track cannot leave
    // two summaries, or a "complete" over a "cancelled".
    @Volatile
    private var settled = AtomicBoolean(true)

    @Volatile
    private var lastNotifyNanos = 0L

    val isRunning: Boolean get() = importJob?.isActive == true

    /** Import from a pasted link — Spotify playlist URLs and spotify: URIs. */
    fun importUrl(url: String, strictAlbumMatch: Boolean = false): Boolean =
        launchImport { playlistImporter.importFromUrl(url, strictAlbumMatch) }

    /** The same entry point under the name the brief uses. */
    fun start(playlistUrl: String, strictAlbumMatch: Boolean = false): Boolean = importUrl(playlistUrl, strictAlbumMatch)

    fun importPlaylist(playlistId: String, name: String?, strictAlbumMatch: Boolean = false): Boolean =
        launchImport { playlistImporter.importSpotifyPlaylist(playlistId, name, strictAlbumMatch) }

    fun importLikedSongs(strictAlbumMatch: Boolean = false): Boolean =
        launchImport { playlistImporter.importSpotifyLikedSongs(strictAlbumMatch) }

    /** Stops the running import, if any, and reports it as cancelled. */
    @Synchronized
    fun cancel() {
        val job = importJob ?: return
        if (!job.isActive) return
        if (!settled.compareAndSet(false, true)) return
        job.cancel()
        val message = context.getString(R.string.import_cancelled)
        importService.reportFailure(message)
        finish(success = false, message = message)
        // The import can publish one more Matching tick between the cancel and
        // its next suspension point, which would leave Settings counting a
        // stopped import. Once it has unwound, put the cancelled state back —
        // unless another import has started meanwhile and owns the flow now.
        scope.launch {
            job.join()
            val now = importService.progress.value
            if (importJob === job && (now is ImportProgress.Fetching || now is ImportProgress.Matching)) {
                importService.reportFailure(message)
            }
        }
    }

    /**
     * One import at a time — the Settings UI disables its buttons while one
     * runs, but a second start via stale UI must not corrupt the state of the
     * one in flight. Returns false when nothing was started.
     */
    @Synchronized
    private fun launchImport(request: suspend () -> Result<ImportProgress.Done>): Boolean {
        if (importJob?.isActive == true) return false

        val preparing = context.getString(R.string.import_preparing)
        _state.value = State.Running(preparing, 0, 0)
        lastNotifyNanos = 0L
        notifier.post(ONGOING_NOTIFICATION_ID, context.getString(R.string.import_progress_title), preparing, ongoing = true)

        val settledFlag = AtomicBoolean(false)
        settled = settledFlag
        importJob = scope.launch {
            // Mirror the shared progress flow into the notification while the
            // import runs in the sibling job below.
            val progressMirror = launch {
                importService.progress.collect { progress ->
                    when (progress) {
                        is ImportProgress.Fetching ->
                            notifyProgress(context.getString(R.string.import_fetching, progress.source), 0, 0)
                        is ImportProgress.Matching ->
                            notifyProgress(
                                context.getString(R.string.import_matching, progress.current, progress.total, progress.matched),
                                progress.current,
                                progress.total,
                            )
                        else -> Unit // Terminal states are handled by finish().
                    }
                }
            }
            val result = try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // PlaylistImporter returns its failures as a Result; anything
                // that escapes anyway still has to end the import visibly
                // rather than leave the ongoing entry up for good.
                Result.failure(e)
            }
            progressMirror.cancel()
            // cancel() has already published its own terminal state; a request
            // that returned anyway (a Result wrapping the cancellation) must
            // not post a second summary over it.
            if (!isActive || !settledFlag.compareAndSet(false, true)) return@launch
            result
                .onSuccess { done ->
                    finish(
                        success = true,
                        message = context.resources.getQuantityString(
                            R.plurals.spotify_import_done, done.total, done.matched, done.total, done.playlistName,
                        ),
                    )
                }
                .onFailure { finish(success = false, message = it.message ?: context.getString(R.string.import_failed)) }
        }
        return true
    }

    /** Replace the ongoing notification with a dismissible summary. */
    private fun finish(success: Boolean, message: String) {
        _state.value = State.Finished(success, message)
        notifier.cancel(ONGOING_NOTIFICATION_ID)
        notifier.post(
            RESULT_NOTIFICATION_ID,
            context.getString(if (success) R.string.import_complete_title else R.string.import_failed_title),
            message,
            ongoing = false,
        )
        // Desktop: the Android summary carried a PendingIntent that opened the
        // app; bringing the window forward on a tap is the Notifier
        // implementation's job, and the in-notification Cancel action is the
        // Settings screen's Cancel button calling [cancel].
    }

    /**
     * Throttled to ~2 updates/second: Matching emits once per track, and
     * re-posting a notification per track is pointless churn for whatever
     * draws it. The final tick always lands because [finish] posts its own.
     * [state] is updated on every tick, since a StateFlow conflates for free.
     */
    private fun notifyProgress(text: String, current: Int, total: Int) {
        // A tick that arrives after the import was settled (the mirror is a
        // child of a job cancel() has just stopped) must not re-post the
        // ongoing entry finish() took down.
        if (settled.get()) return
        _state.value = State.Running(text, current, total)
        val now = System.nanoTime()
        // 0 means "nothing posted yet": nanoTime's origin is arbitrary and may
        // be negative, so it cannot be compared against a zero start value.
        if (lastNotifyNanos != 0L && now - lastNotifyNanos < 500_000_000L) return
        lastNotifyNanos = now
        notifier.post(
            ONGOING_NOTIFICATION_ID,
            context.getString(R.string.import_progress_title),
            text,
            ongoing = true,
            progress = if (total > 0) current.coerceIn(0, total).toFloat() / total else null,
        )
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 41001
        private const val RESULT_NOTIFICATION_ID = 41002
    }
}
