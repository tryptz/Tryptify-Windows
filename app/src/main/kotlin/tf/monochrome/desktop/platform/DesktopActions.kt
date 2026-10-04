package tf.monochrome.desktop.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.platform.UriHandler
import androidx.core.net.toFile
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import tf.monochrome.desktop.R
import tf.monochrome.desktop.res.Strings

/**
 * What Android's intents become on Windows.
 *
 * VIEW opens a web link in the default browser and a file in its default
 * program. SEND has no share sheet to go to: shared text lands on the
 * clipboard (with a toast saying so) and a shared file is revealed in
 * Explorer, which is how people share files on the desktop. Anything else
 * throws ActivityNotFoundException, exactly what Android throws when no app
 * can take an intent, so the callers' existing fallbacks run. So does a launch
 * the OS refuses (see [launching]): those callers catch nothing else.
 */
object DesktopActions {
    fun start(intent: Intent) {
        val target = if (intent.action == Intent.ACTION_CHOOSER) intent.getExtra<Intent>(Intent.EXTRA_INTENT) ?: intent else intent
        when (target.action) {
            Intent.ACTION_VIEW -> view(target.data ?: throw ActivityNotFoundException("VIEW without data"))
            Intent.ACTION_SEND -> send(target)
            else -> throw ActivityNotFoundException("no desktop action for ${target.action}")
        }
    }

    fun openLink(url: String) = view(Uri.parse(url))

    /** Returns false, never throws, when the clipboard stays busy; see [putOnClipboard]. */
    fun copyToClipboard(text: String, announce: Boolean = true): Boolean {
        val copied = putOnClipboard(StringSelection(text))
        if (announce) {
            Toasts.post(Strings.get(if (copied) R.string.desktop_copied_to_clipboard else R.string.desktop_clipboard_busy))
        }
        return copied
    }

    /** Opens Explorer on [file]'s folder with the file selected where the OS allows. */
    fun reveal(file: File) = launching("reveal $file") {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (windows && file.exists()) {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            return@launching
        }
        val desktop = desktopOrThrow()
        val dir = if (file.isDirectory) file else file.parentFile ?: throw ActivityNotFoundException("no folder for $file")
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) && file.exists() && !file.isDirectory) {
            desktop.browseFileDirectory(file)
        } else {
            desktop.open(dir)
        }
    }

    private fun view(uri: Uri) {
        val desktop = desktopOrThrow()
        when (uri.scheme?.lowercase()) {
            "http", "https", "mailto" -> {
                if (!desktop.isSupported(Desktop.Action.BROWSE)) throw ActivityNotFoundException("no browser")
                launching("open $uri") { desktop.browse(URI(uri.toString())) }
            }
            "file" -> launching("open $uri") { desktop.open(uri.toFile()) }
            else -> throw ActivityNotFoundException("no desktop handler for ${uri.scheme}")
        }
    }

    private fun send(intent: Intent) {
        val stream = intent.getExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null && stream.scheme == "file") {
            reveal(stream.toFile())
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getExtra<CharSequence>(Intent.EXTRA_TEXT)?.toString()
            ?: throw ActivityNotFoundException("SEND without text or file")
        copyToClipboard(text)
    }

    /**
     * What the OS launch throws, rethrown as ActivityNotFoundException with the
     * real failure as its cause. Desktop.browse throws IOException when Windows
     * has no program for the link (no default browser, a broken association, a
     * locked-down machine), and java.net.URI rejects links Android's Uri.parse
     * took. Anything but ActivityNotFoundException goes past the callers'
     * fallbacks, and out of a click handler it closes the window.
     */
    internal fun <T> launching(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not $what", e)
            throw ActivityNotFoundException("could not $what: ${e.message}").apply { initCause(e) }
        }

    /**
     * Another program (a clipboard manager, RDP's clipboard sync, Office in the
     * middle of a copy) can hold the Windows clipboard open, and AWT then throws
     * IllegalStateException. That rarely lasts more than a few milliseconds, so
     * try again briefly before giving up.
     */
    private fun putOnClipboard(contents: Transferable): Boolean {
        repeat(CLIPBOARD_ATTEMPTS) { attempt ->
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(contents, null)
                return true
            } catch (e: IllegalStateException) {
                if (attempt == CLIPBOARD_ATTEMPTS - 1) Log.w(TAG, "clipboard busy", e) else Thread.sleep(CLIPBOARD_RETRY_MS)
            }
        }
        return false
    }

    private fun desktopOrThrow(): Desktop =
        if (Desktop.isDesktopSupported()) Desktop.getDesktop() else throw ActivityNotFoundException("java.awt.Desktop unavailable")

    private const val TAG = "DesktopActions"
    private const val CLIPBOARD_ATTEMPTS = 3
    private const val CLIPBOARD_RETRY_MS = 20L
}

/**
 * LocalUriHandler for the window. Compose's own calls Desktop.browse directly,
 * so a link the OS cannot open (see [DesktopActions.launching]) escapes the
 * click handler and closes the window. Here it goes through [DesktopActions]
 * and becomes the same toast Android's callers show.
 */
object DesktopUriHandler : UriHandler {
    override fun openUri(uri: String) {
        try {
            DesktopActions.openLink(uri)
        } catch (e: ActivityNotFoundException) {
            Toasts.post(Strings.get(R.string.settings_no_app_for_link))
        }
    }
}
