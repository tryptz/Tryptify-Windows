package tf.monochrome.desktop.data.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.platform.AppScope
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadStatus {
    IDLE, QUEUED, DOWNLOADING, COMPLETED, FAILED
}

data class TrackDownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f
)

/** A single in-flight download with the metadata needed to render it (pill / monitor). */
data class ActiveDownload(
    val trackId: Long,
    val title: String,
    val artistName: String,
    val artworkUri: String?,
    val status: DownloadStatus,
    val progress: Float,
    val isThxSpatialAudio: Boolean = false,
    /** Why a FAILED download failed, when known. */
    val error: String? = null,
)

/**
 * The app's download entry point: hand it tracks, ask it what's happening.
 *
 * It owns a [DownloadQueue] and exactly one job to drain it. Any number of
 * tracks can be queued — an album, a playlist, fifty at once — and
 * [DownloadQueue.CONCURRENCY] of them transfer at a time. Queueing more work
 * while the job is already running just appends to the list; the running job
 * picks it up on its next pass, so nothing is scheduled twice.
 *
 * This replaces one expedited, self-foregrounding worker per track. That design
 * turned "download this album" into a request for a dozen concurrent foreground
 * services sharing one WorkManager service and a colliding notification id —
 * see [DownloadQueue] and [DownloadQueueRunner] for the details.
 *
 * Desktop: WorkManager is gone; the drain is [DownloadQueueRunner], an
 * in-process job on the app scope with the same KEEP semantics. The network
 * constraint it carried (`NetworkType.CONNECTED`) has no desktop equivalent: a
 * transfer with no network fails as RETRYABLE and the queue counts the attempt.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val queue: DownloadQueue,
    private val preferences: PreferencesManager,
    private val runner: DownloadQueueRunner,
    @AppScope private val scope: CoroutineScope,
    private val qobuzIdRegistry: tf.monochrome.desktop.data.api.QobuzIdRegistry,
) {

    init {
        // The queue persists itself through here rather than knowing about
        // storage: a fifty-track queue has to outlive the process that made it.
        queue.onChanged = { json -> scope.launch { preferences.setDownloadQueueJson(json) } }
        scope.launch {
            queue.restore(preferences.downloadQueueJson.first())
            if (queue.hasWork()) startWorker()
        }
    }

    /** The drain's own summary (running, remaining, current title) for a status surface. */
    val runnerState get() = runner.state

    fun downloadTrack(track: Track) {
        downloadTracks(listOf(track))
    }

    fun downloadTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        queue.enqueue(tracks.map { DownloadItem.from(it, isQobuz = qobuzIdRegistry.isQobuzTrack(it.id)) })
        startWorker()
    }

    /** Drops a track off the queue, cancelling it if it happens to be running. */
    fun cancel(trackId: Long) {
        queue.remove(trackId)
    }

    /** Empties the queue and stops the job draining it. */
    fun cancelAll() {
        queue.clear()
        runner.stop()
    }

    /**
     * Puts a failed track back in line.
     *
     * Unlike the old implementation this works after a restart: the queue entry
     * carries everything the download needs and is persisted, so a failed row
     * is never a retry button that silently does nothing.
     */
    fun retry(trackId: Long) {
        queue.retry(trackId)
        startWorker()
    }

    /**
     * KEEP, not REPLACE: enqueueing more tracks while the job is draining must
     * not restart it — the running job already reads the same queue and will
     * pick them up on its next pass. REPLACE would cancel mid-transfer and lose
     * whatever was in flight. [DownloadQueueRunner.start] is exactly that policy.
     */
    private fun startWorker() {
        runner.start()
    }

    fun observeDownloadState(trackId: Long): Flow<TrackDownloadState> =
        queue.entries.map { entries ->
            val entry = entries.firstOrNull { it.item.trackId == trackId }
                ?: return@map TrackDownloadState(DownloadStatus.IDLE, 0f)
            TrackDownloadState(entry.status, entry.progress)
        }

    fun observeAllActiveDownloads(): Flow<Map<Long, TrackDownloadState>> =
        queue.entries.map { entries ->
            entries.associate { it.item.trackId to TrackDownloadState(it.status, it.progress) }
        }

    /**
     * Active downloads enriched with title/artist/cover, ready to render in the
     * progress pill and the downloads monitor. Running items first, then
     * whatever is furthest along — queue order otherwise, so an album reads in
     * track order rather than jumping about.
     */
    fun observeActiveDownloads(): Flow<List<ActiveDownload>> =
        queue.entries.map { entries ->
            entries
                .map { entry ->
                    ActiveDownload(
                        trackId = entry.item.trackId,
                        title = entry.item.title,
                        artistName = entry.item.artistName,
                        artworkUri = entry.item.albumCover,
                        status = entry.status,
                        progress = entry.progress,
                        isThxSpatialAudio = entry.item.isThxSpatialAudio,
                        error = entry.error,
                    )
                }
                .sortedWith(
                    compareByDescending<ActiveDownload> { it.status == DownloadStatus.DOWNLOADING }
                        .thenByDescending { it.progress }
                )
        }
}
