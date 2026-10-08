package tf.monochrome.desktop.debug

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.desktop.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catches uncaught exceptions, dumps the in-memory `DebugLogBuffer` plus the
 * throwable's stack trace to a timestamped file, then chains to the previous
 * default handler.
 *
 * Desktop: the dump goes to the app's logs folder
 * (`%LOCALAPPDATA%\Tryptify\data\logs\tryptify-crash-YYYYMMDD-HHMMSS-mmm.log`,
 * [AppPaths.logsDir][tf.monochrome.desktop.platform.AppPaths.logsDir]) instead
 * of the phone's public Downloads through MediaStore — there is no MediaStore,
 * and a per-user app folder is where Windows apps keep their logs. With no
 * previous handler the JVM's own behaviour is kept (the trace goes to
 * `System.err`, which the debug log captures).
 *
 * Desktop: an uncaught exception does not end the process here. Android's
 * default handler showed the crash dialog and killed the app; the JVM lets the
 * failing thread die and keeps the rest running (Swing restarts its event
 * thread), so the window stays up. Because the same bug can then throw again
 * and again, at most [MAX_DUMPS_PER_RUN] dumps are written per run and only the
 * newest [KEEP_DUMPS] are kept on disk.
 *
 * If the dump itself throws (disk full, folder not writable, …) we swallow the
 * secondary exception and still forward the original throwable to the prior
 * handler — losing the original report because of a logging side effect would
 * be worse than losing the log.
 *
 * Reports can be switched off in Settings › System › Diagnostics
 * ([saveReports]). The switch is a marker file rather than DataStore: the
 * handler reads it while a thread is dying, synchronously, and DataStore can
 * only be read by suspending. (Android keeps it in SharedPreferences for the
 * same reason.)
 *
 * Desktop: no report of the previous process's native crash or ANR. Android
 * asks ActivityManager why the last process ended (ApplicationExitInfo);
 * Windows keeps no such record for an app, and a JVM killed by a native
 * crash leaves an hs_err_pid file in its working folder instead.
 */
@Singleton
class CrashLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugLogBuffer: DebugLogBuffer,
) {
    @Volatile private var installed = false
    private val dumpsThisRun = AtomicInteger()

    // Present means off. Read on first use, off the startup path: the
    // handler or the Settings screen, whichever comes first.
    private val reportsOffMarker: File get() = File(context.filesDir, REPORTS_OFF_MARKER)

    private val _saveReports by lazy { MutableStateFlow(!reportsOffMarker.exists()) }

    /** Whether crash reports are written to the logs folder. On unless switched off. */
    val saveReports: StateFlow<Boolean> get() = _saveReports.asStateFlow()

    fun setSaveReports(enabled: Boolean) {
        _saveReports.value = enabled
        runCatching {
            if (enabled) {
                reportsOffMarker.delete()
            } else {
                reportsOffMarker.parentFile?.mkdirs()
                reportsOffMarker.createNewFile()
            }
        }.onFailure { Log.w(TAG, "Could not store the crash report switch", it) }
    }

    /** Where the dumps go; a settings screen can reveal it in Explorer. */
    val logsDir: File get() = context.paths.logsDir

    fun install() {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (_saveReports.value && dumpsThisRun.incrementAndGet() <= MAX_DUMPS_PER_RUN) {
                    writeCrashDump(thread, throwable)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Crash dump failed", t)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // What ThreadGroup.uncaughtException prints when no default
                // handler is installed, which installing this one replaced.
                System.err.print("Exception in thread \"${thread.name}\" ")
                throwable.printStackTrace(System.err)
            }
        }
    }

    private fun writeCrashDump(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val fileName = "$FILE_PREFIX$timestamp.log"
        val content = formatDump(thread, throwable, timestamp)

        val dir = logsDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "Crash dump could not be written: no folder at $dir")
            return
        }
        val target = File(dir, fileName)
        val wrote = runCatching {
            target.writeBytes(content.toByteArray(Charsets.UTF_8))
            true
        }.getOrDefault(false)
        if (wrote) {
            Log.e(TAG, "Crash dump written to ${target.absolutePath}")
            pruneOldDumps(dir)
        } else {
            Log.w(TAG, "Crash dump could not be written to $dir")
        }
    }

    private fun pruneOldDumps(dir: File) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name } // the timestamp in the name sorts chronologically
                ?.drop(KEEP_DUMPS)
                ?.forEach { it.delete() }
        }
    }

    private fun formatDump(thread: Thread, throwable: Throwable, timestamp: String): String {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()
        val header = buildString {
            appendLine("Tryptify crash dump")
            appendLine("===================")
            appendLine("timestamp: $timestamp")
            appendLine("app:       ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("os:        ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("java:      ${System.getProperty("java.runtime.version") ?: System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
            appendLine("machine:   ${android.os.Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} logical CPUs, " +
                "heap max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
            appendLine("thread:    ${thread.name} (id=${thread.threadId()})")
            appendLine()
        }
        val recentLog = runCatching { debugLogBuffer.dumpAsText() }.getOrDefault("(log buffer unavailable)")
        return buildString {
            append(header)
            appendLine("--- stack trace ---")
            appendLine(stack)
            appendLine("--- recent log ---")
            appendLine(recentLog)
        }
    }

    companion object {
        private const val TAG = "CrashLogger"
        private const val FILE_PREFIX = "tryptify-crash-"
        private const val MAX_DUMPS_PER_RUN = 20
        private const val KEEP_DUMPS = 50
        private const val REPORTS_OFF_MARKER = "crash_reports_off"
    }
}
