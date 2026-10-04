package tf.monochrome.desktop.visualizer

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Notices a preset that crashed this device, so it is never loaded again.
 *
 * The host scan ([KnownCrashPresets]) catches what crashes projectM itself. It
 * cannot catch a preset whose shaders kill one vendor's GPU driver, and that
 * crash takes the whole process with it — there is no exception to catch and
 * no later moment in which to react. So the bridge writes the preset it is
 * about to load to [sentinel] before every load, and deletes the file when it
 * is released cleanly. If the next process finds the file, the last one died
 * with that preset on screen.
 *
 * Died is not the same as crashed. On Android 11 and later the exit record for
 * the dead process said why, and only a native crash or an ANR (a preset that
 * hangs the render thread) flagged anything; a swipe from recents, a low-memory
 * kill or an app update did not.
 *
 * Desktop: there is no exit record to ask (Android's
 * `ActivityManager.getHistoricalProcessExitReasons`), so that half is dropped
 * and the sentinel alone decides, as it did on Android 10 and older. What keeps
 * an ordinary quit from reading as a crash is a JVM shutdown hook that clears
 * the sentinel: the JVM runs its hooks on every orderly exit (window closed,
 * `System.exit`, Ctrl+C, Windows log-off) and runs none when a native fault
 * aborts it or the process is ended from Task Manager — which are the two cases
 * Android flagged, a native crash and a hang. The hook is installed only once
 * the previous process's sentinel has been judged, so a short session cannot
 * erase the evidence of the crash before it.
 */
internal class PresetCrashGuard(
    // Desktop: kept so the constructor matches Android's call site; the exit
    // record it was used to read does not exist here.
    @Suppress("unused") private val context: Context,
    val sentinel: File,
) {
    private val exitHookInstalled = AtomicBoolean(false)

    /**
     * The preset the previous process died on — as the absolute path the bridge
     * wrote. Consumes the sentinel either way, so the same death is only ever
     * judged once.
     */
    fun takeCrashedPreset(): String? {
        try {
            if (!sentinel.exists()) return null
            val text = runCatching { sentinel.readText() }.getOrNull()
            sentinel.delete()
            val record = parseSentinel(text) ?: return null
            Log.w(
                TAG,
                "Process ${record.pid} ended on ${record.presetPath} without an orderly exit: flagging it",
            )
            return record.presetPath
        } finally {
            clearSentinelOnOrderlyExit()
        }
    }

    /** From here on, an orderly JVM exit deletes whatever the bridge last wrote. */
    private fun clearSentinelOnOrderlyExit() {
        if (!exitHookInstalled.compareAndSet(false, true)) return
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread({ runCatching { sentinel.delete() } }, "PresetCrashGuard-exit"),
            )
        }.onFailure { Log.w(TAG, "Could not install the exit hook: ${it.message}") }
    }

    data class SentinelRecord(val pid: Int, val presetPath: String)

    companion object {
        private const val TAG = "PresetCrashGuard"

        /** What the bridge writes: the process id, then the preset path, a line each. */
        fun parseSentinel(text: String?): SentinelRecord? {
            val lines = text?.lines() ?: return null
            val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val path = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return SentinelRecord(pid, path)
        }
    }
}
