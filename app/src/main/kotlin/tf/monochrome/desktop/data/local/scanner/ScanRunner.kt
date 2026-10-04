package tf.monochrome.desktop.data.local.scanner

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import tf.monochrome.desktop.platform.AppScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot full library scan, started when the user finishes (or skips)
 * onboarding. Everything it needs — folder roots, thresholds — is read from
 * preferences inside MediaScanner, so there's no input.
 *
 * Desktop: Android's `ScanWorker`, a `CoroutineWorker` that WorkManager ran as
 * unique work (`ExistingWorkPolicy.KEEP`) so the initial scan survived process
 * death right after setup. There is no scheduler between the app and its own
 * coroutines here: [start] launches the scan on the app-wide scope and is a
 * no-op while one is running (KEEP). The survival half is a marker file in
 * the data folder, written when the scan starts and removed when it ends: if
 * the app is closed mid-scan, [resumeIfInterrupted] at the next launch starts
 * it again, as WorkManager would have re-run the worker.
 */
@Singleton
class ScanRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val scanCoordinator: ScanCoordinator,
) {
    private val jobLock = Any()
    private var job: Job? = null

    private val marker: File get() = File(context.filesDir, MARKER_NAME)

    /** True while a scan started here is running. */
    val isRunning: Boolean get() = synchronized(jobLock) { job?.isActive == true }

    /**
     * Starts the initial full scan unless one started here is still running.
     * KEEP: re-finishing onboarding (e.g. restarted from Settings) while the
     * scan is running must not restart it.
     */
    fun start() {
        synchronized(jobLock) {
            if (job?.isActive == true) return
            runCatching { marker.parentFile?.mkdirs(); marker.createNewFile() }
            job = scope.launch {
                val self = currentCoroutineContext()[Job]
                try {
                    scanCoordinator.runFullScan()
                    // Done, or dropped because another scan was already
                    // running — the worker reported success either way.
                    runCatching { marker.delete() }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // The worker's doWork() had no catch either, but WorkManager
                    // contained the throw; here it must not reach the scope.
                    Log.w(TAG, "initial library scan failed", t)
                    runCatching { marker.delete() }
                } finally {
                    synchronized(jobLock) { if (job === self) job = null }
                }
            }
        }
    }

    /** Stops the scan this runner started; it will not resume at the next launch. */
    fun cancel() {
        synchronized(jobLock) {
            job?.cancel()
            job = null
        }
        runCatching { marker.delete() }
    }

    /**
     * Call once at startup: restarts an initial scan that the previous session
     * started and did not finish.
     */
    fun resumeIfInterrupted() {
        if (marker.exists()) {
            Log.i(TAG, "resuming the initial library scan interrupted last session")
            start()
        }
    }

    companion object {
        private const val TAG = "ScanRunner"

        /** WorkManager's unique-work name, kept as the marker's name. */
        const val UNIQUE_NAME = "initial_library_scan"
        private const val MARKER_NAME = "$UNIQUE_NAME.pending"
    }
}
