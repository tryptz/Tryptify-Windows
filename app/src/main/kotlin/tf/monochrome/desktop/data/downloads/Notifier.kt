package tf.monochrome.desktop.data.downloads

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the long-running jobs report to the person when no screen is watching.
 *
 * On Android the download queue and the playlist import each owned a system
 * notification: a channel, an ongoing progress entry and a dismissible summary.
 * The desktop has no notification manager the app is handed; what it has is a
 * tray icon (`java.awt.SystemTray`) or a toast window, and which of those the
 * app ships is a UI decision. So the jobs talk to this interface and nothing
 * else: [post] with the same id replaces the earlier entry, [cancel] takes it
 * down, and [ongoing] marks an entry that must stay until cancelled (the
 * Android "ongoing" flag). [progress] is 0..1 for a determinate bar, null for
 * an indeterminate one; an implementation that cannot draw a bar ignores it.
 */
interface Notifier {
    fun post(id: Int, title: String, text: String, ongoing: Boolean = false, progress: Float? = null)
    fun cancel(id: Int)
}

/**
 * The default: every notification becomes a log line. It is what runs until a
 * tray or toast implementation is installed, so nothing a job reports is lost
 * in the meantime — it lands in the in-app debug log like every other `Log` call.
 */
object LogNotifier : Notifier {
    private const val TAG = "Notifier"

    override fun post(id: Int, title: String, text: String, ongoing: Boolean, progress: Float?) {
        val bar = progress?.let { " (${(it * 100).toInt()}%)" } ?: ""
        Log.i(TAG, "[$id${if (ongoing) ", ongoing" else ""}] $title — $text$bar")
    }

    override fun cancel(id: Int) {
        Log.i(TAG, "[$id] cancelled")
    }
}

/**
 * The injectable [Notifier]: a singleton that forwards to whatever
 * implementation is installed, [LogNotifier] until something better is.
 *
 * It exists so the jobs can take a Notifier by constructor injection without a
 * Dagger module binding the interface — the tray implementation lives in the UI
 * layer and is installed at startup with [install], after the component is built.
 */
@Singleton
class AppNotifier @Inject constructor() : Notifier {
    @Volatile
    var delegate: Notifier = LogNotifier
        private set

    /** Replaces the active implementation; the next post goes through it. */
    fun install(notifier: Notifier) {
        delegate = notifier
    }

    override fun post(id: Int, title: String, text: String, ongoing: Boolean, progress: Float?) =
        delegate.post(id, title, text, ongoing, progress)

    override fun cancel(id: Int) = delegate.cancel(id)
}
