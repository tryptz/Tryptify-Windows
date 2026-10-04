package tf.monochrome.desktop.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toFile
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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
 * can take an intent, so the callers' existing fallbacks run.
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

    fun copyToClipboard(text: String, announce: Boolean = true) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        if (announce) Toasts.post(Strings.get(R.string.desktop_copied_to_clipboard))
    }

    /** Opens Explorer on [file]'s folder with the file selected where the OS allows. */
    fun reveal(file: File) {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (windows && file.exists()) {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            return
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
                desktop.browse(URI(uri.toString()))
            }
            "file" -> desktop.open(uri.toFile())
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

    private fun desktopOrThrow(): Desktop =
        if (Desktop.isDesktopSupported()) Desktop.getDesktop() else throw ActivityNotFoundException("java.awt.Desktop unavailable")
}
