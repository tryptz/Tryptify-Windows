package tf.monochrome.desktop.data.downloads

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.desktop.R
import tf.monochrome.desktop.platform.AppScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one job that drains [DownloadQueue].
 *
 * There is exactly one of these no matter how many tracks are waiting, and it
 * owns the single "Downloading" notification. That is the whole point: the
 * previous design enqueued one expedited worker per track, each promoting
 * itself to a foreground service with a notification id of
 * `base + (trackId % 1000)`. Two tracks whose ids are a thousand apart shared
 * an id, and a queue of any size had several workers starting and stopping
 * foreground state on one service at once.
 *
 * One job, one notification, [DownloadQueue.CONCURRENCY] transfers at a time,
 * and the queue itself decides what runs next.
 *
 * On Android this was a `CoroutineWorker` that WorkManager ran as unique work
 * (`ExistingWorkPolicy.KEEP`). On the desktop there is no scheduler between the
 * app and its own coroutines: [start] launches the drain on the app-wide scope,
 * is a no-op while one is running (KEEP — the running drain picks up whatever
 * was enqueued on its next pass), and a [Mutex] held for the drain's lifetime
 * keeps two drains from ever overlapping. The notification is a [Notifier]
 * entry, and [state] carries the same summary for any screen that wants it.
 */
@Singleton
class DownloadQueueRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val queue: DownloadQueue,
    private val downloader: TrackDownloader,
    private val notifier: AppNotifier,
) {

    /** What the drain is doing, for the UI: the notification's text as data. */
    data class Summary(
        val running: Boolean = false,
        /** Entries still waiting or transferring. */
        val remaining: Int = 0,
        /** Title of a track currently transferring, if any. */
        val currentTitle: String? = null,
    )

    private val _state = MutableStateFlow(Summary())
    val state: StateFlow<Summary> = _state.asStateFlow()

    // Held for the whole of a drain, so two can never run at once: a drain
    // launched while an earlier one is still unwinding waits its turn.
    private val drainLock = Mutex()
    private val jobLock = Any()
    private var drainJob: Job? = null

    // Live transfers, so cancelling an entry stops the bytes rather than just
    // hiding the row. Keyed by track id; entries are removed as they settle.
    private val running = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    val isRunning: Boolean get() = drainLock.isLocked

    /**
     * Starts draining unless a drain is already running — KEEP, not REPLACE:
     * enqueueing more tracks while the job is draining must not restart it.
     * The running job already reads the same queue and will pick them up on
     * its next pass. Restarting would cancel mid-transfer and lose whatever
     * was in flight.
     *
     * A drain that [stop] has cancelled still holds [drainLock] while its
     * transfers unwind; it no longer counts as running here, so the new drain
     * is launched and queues behind it on the mutex. Work enqueued right after
     * `cancelAll()` therefore starts on its own instead of sitting as QUEUED
     * until the next enqueue.
     */
    fun start() {
        synchronized(jobLock) {
            val current = drainJob
            if (current != null && current.isActive && !current.isCancelled) return
            drainJob = scope.launch { drainLock.withLock { drainUntilIdle() } }
        }
    }

    /** Stops the drain and every transfer under it. The queue is left as it is. */
    fun stop() {
        synchronized(jobLock) { drainJob?.cancel() }
    }

    /**
     * Drains, and drains again if work arrived while the last pass was
     * winding down.
     *
     * A [start] that lands between the drain's last look at the queue and the
     * job completing sees the job still active and returns — KEEP — trusting
     * the drain to pick the work up, which it no longer will. WorkManager had
     * the same window. Here the decision to stop is taken under [jobLock], the
     * lock [start] takes: either that [start] already ran, and its entries are
     * in the queue for the check below to see, or it runs after, finds no
     * active job and launches one.
     */
    private suspend fun drainUntilIdle() {
        val self = currentCoroutineContext()[Job]
        while (true) {
            val finished = drain()
            synchronized(jobLock) {
                val waiting = queue.entries.value.any { it.status == DownloadStatus.QUEUED }
                // A drain that stopped on an unexpected error is not rerun
                // here: the next enqueue restarts it, as on Android, rather
                // than this spinning on the same failure.
                if (!finished || !waiting) {
                    if (drainJob === self) drainJob = null
                    return
                }
            }
        }
    }

    /** One pass over the queue; true when it ran until nothing was left to start. */
    private suspend fun drain(): Boolean {
        queue.onCancel = { trackId -> running.remove(trackId)?.cancel() }
        // Anything still marked as running belongs to a process that died
        // mid-transfer; nothing is going to finish it.
        queue.requeueOrphans()
        notify(initial = true)

        try {
            while (queue.hasWork()) {
                // Checked before takeNext marks anything as running, so a
                // cancelled drain does not leave a batch flagged DOWNLOADING.
                currentCoroutineContext().ensureActive()
                val room = DownloadQueue.CONCURRENCY - queue.runningCount()
                val batch = queue.takeNext(room)
                if (batch.isEmpty()) {
                    // Nothing runnable but the queue isn't empty — everything
                    // left is FAILED and waiting on the user. Stop; a retry or
                    // a new download re-enqueues this runner.
                    if (queue.runningCount() == 0) break
                    delay(IDLE_POLL_MS)
                    continue
                }
                notify(initial = false)
                // Joined, not awaited. The Android worker used async/awaitAll,
                // and awaitAll throws as soon as any one deferred is cancelled
                // — which is exactly what cancelling one download does — so
                // removing one track tore down the whole batch and the worker
                // with it, leaving the rest "Queued" until the next enqueue.
                // run() settles every outcome itself, so there is nothing to
                // await; join waits for the batch without inheriting a
                // sibling's cancellation. Started lazily so each transfer is
                // in [running] before it can be cancelled.
                coroutineScope {
                    batch.map { item ->
                        launch(start = CoroutineStart.LAZY) { run(item) }
                            .also { running[item.trackId] = it }
                    }.onEach { it.start() }.joinAll()
                }
                notify(initial = false)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The loop itself failing must not lose the queue. Leave it as it
            // stands and let the next enqueue restart the runner.
            Log.w(TAG, "download queue stopped unexpectedly", e)
            return false
        } finally {
            queue.onCancel = null
            running.clear()
            notifier.cancel(NOTIFICATION_ID)
            _state.value = Summary()
        }
    }

    private suspend fun run(item: DownloadItem) {
        val outcome = try {
            downloader.download(item) { progress ->
                queue.setProgress(item.trackId, progress)
                // Refreshing the notification per progress callback would post
                // hundreds of updates a second; the batch boundary is enough.
                // Per-track progress reaches the UI through DownloadQueue.entries.
            }
        } catch (e: CancellationException) {
            // Cancelled by the user: the entry is already off the queue, so
            // there is nothing left to mark.
            running.remove(item.trackId)
            if (!currentCoroutineContext().isActive) return
            // Still active, so this was not our cancellation but a client's
            // own timeout surfacing as one: an ordinary, retryable failure.
            Log.w(TAG, "download cancelled itself for \"${item.title}\"", e)
            TrackDownloader.Outcome.RETRYABLE
        } catch (e: Exception) {
            Log.w(TAG, "download threw for \"${item.title}\"", e)
            TrackDownloader.Outcome.RETRYABLE
        }
        running.remove(item.trackId)
        // TrackDownloader's catch-all also sees a CancellationException and
        // answers RETRYABLE. A transfer that was cancelled has no verdict to
        // record — the user dropped it, stop() emptied the queue, or the app
        // is closing — and charging it an attempt would be wrong.
        if (!currentCoroutineContext().isActive) return
        when (outcome) {
            TrackDownloader.Outcome.SUCCESS -> queue.complete(item.trackId)
            TrackDownloader.Outcome.RETRYABLE -> queue.fail(item.trackId, retryable = true)
            TrackDownloader.Outcome.PERMANENT -> queue.fail(item.trackId, retryable = false)
        }
    }

    /**
     * Re-posts the one notification as the queue drains and mirrors it into
     * [state]. Posted up front so a queue that is still resolving its first
     * stream URL already shows "Preparing".
     */
    private fun notify(initial: Boolean) {
        if (!initial && !queue.hasWork()) return
        val entries = queue.entries.value
        val remaining = entries.count {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
        val current = entries.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        _state.value = Summary(running = true, remaining = remaining, currentTitle = current?.item?.title)
        // Desktop: Strings resolves in the app's own language already, so there
        // is no AppLanguage.wrap(context) step as there was for a worker's
        // application context. Notification channels do not exist here; the
        // Notifier implementation owns its presentation.
        val title =
            if (remaining > 1) context.resources.getQuantityString(R.plurals.notif_downloading_n, remaining, remaining)
            else context.getString(R.string.notif_downloading)
        val text = current?.item?.title ?: context.getString(R.string.notif_preparing)
        runCatching { notifier.post(NOTIFICATION_ID, title, text, ongoing = true, progress = null) }
    }

    companion object {
        /** The unique-work name on Android; kept as the job's identity in logs. */
        const val WORK_NAME = "download_queue"
        private const val TAG = "DownloadQueueRunner"
        // A single fixed id, because there is a single notification. The old
        // per-track id was derived from the track id and collided.
        private const val NOTIFICATION_ID = 8100
        private const val IDLE_POLL_MS = 250L
    }
}
