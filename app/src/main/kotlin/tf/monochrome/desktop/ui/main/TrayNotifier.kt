package tf.monochrome.desktop.ui.main

import android.util.Log
import java.awt.AWTException
import java.awt.EventQueue
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import kotlin.math.roundToInt
import tf.monochrome.desktop.data.downloads.LogNotifier
import tf.monochrome.desktop.data.downloads.Notifier

/**
 * The app's notifications, in the Windows notification area: where the
 * download queue and the playlist import report when no screen is watching,
 * as their Android notifications did.
 *
 * Windows has no ongoing, updatable notification for an app to hold, so the
 * two kinds part ways. An ongoing entry (a download running, an import in
 * progress) becomes a line of the tray icon's tooltip, replaced in place as it
 * moves and gone on [cancel]. A finished one, Android's dismissible summary,
 * pops a balloon, which Windows 10 and 11 show as a toast and keep in the
 * notification centre. Popping a balloon for every progress step would bury
 * the person in toasts, which is why ongoing entries never do.
 *
 * The icon is added with the first notification and stays until [dispose]:
 * taking it away as soon as nothing is running would take the balloon it just
 * showed with it. Clicking the icon or a balloon calls [onActivate], which
 * brings the window back.
 *
 * Where there is no notification area (a desktop without a tray, a headless
 * run) every call goes to [LogNotifier] instead, so nothing a job reports is
 * lost; it lands in the in-app debug log.
 *
 * Every tray call is made on the AWT event thread; [post] and [cancel] are
 * called from the jobs' coroutines and hand over to it.
 */
class TrayNotifier(
    private val appName: String,
    private val image: Image,
    private val onActivate: () -> Unit,
) : Notifier {
    private val supported = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    // Both touched on the event thread only.
    private var icon: TrayIcon? = null
    private val running = LinkedHashMap<Int, String>()

    override fun post(id: Int, title: String, text: String, ongoing: Boolean, progress: Float?) {
        if (!supported) return LogNotifier.post(id, title, text, ongoing, progress)
        // The jobs post from their coroutines; the tray is driven from the event thread.
        onEventThread {
            val tray = ensureIcon() ?: return@onEventThread LogNotifier.post(id, title, text, ongoing, progress)
            if (ongoing) {
                val bar = progress?.let { " (${(it.coerceIn(0f, 1f) * 100).roundToInt()}%)" }.orEmpty()
                running[id] = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ") + bar
            } else {
                running.remove(id)
                tray.displayMessage(title, text, TrayIcon.MessageType.INFO)
            }
            tray.toolTip = tooltip()
        }
    }

    override fun cancel(id: Int) {
        if (!supported) return LogNotifier.cancel(id)
        onEventThread {
            if (running.remove(id) != null) icon?.toolTip = tooltip()
        }
    }

    /**
     * Takes the icon out of the notification area; call when the app exits.
     * On the event thread it does so at once rather than queued behind the
     * exit, because an icon a process leaves behind lingers in the
     * notification area until the pointer passes over it.
     */
    fun dispose() {
        if (!supported) return
        onEventThread {
            icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
            icon = null
            running.clear()
        }
    }

    private fun onEventThread(block: () -> Unit) {
        if (EventQueue.isDispatchThread()) block() else EventQueue.invokeLater { block() }
    }

    private fun ensureIcon(): TrayIcon? {
        icon?.let { return it }
        val created = TrayIcon(image, appName).apply {
            // Windows asks for 16px at 100% and more on a high-DPI screen; the
            // image is larger than either and is scaled down to whatever it is.
            isImageAutoSize = true
            // A double-click on the icon, or a click on its balloon.
            addActionListener { onActivate() }
        }
        return try {
            SystemTray.getSystemTray().add(created)
            created.also { icon = it }
        } catch (e: AWTException) {
            Log.w(TAG, "the notification area refused the tray icon", e)
            null
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "no notification area on this desktop", e)
            null
        }
    }

    /** The app's name over one line per running job; Windows cuts a tray tooltip at 127 characters. */
    private fun tooltip(): String =
        (listOf(appName) + running.values).joinToString("\n").take(MAX_TOOLTIP)

    private companion object {
        const val TAG = "TrayNotifier"
        const val MAX_TOOLTIP = 127
    }
}
