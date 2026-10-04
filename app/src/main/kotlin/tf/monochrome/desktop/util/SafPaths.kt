package tf.monochrome.desktop.util

import android.net.Uri
import androidx.core.net.toFile
import java.io.File

/**
 * Resolve the folder a picker returned to a filesystem path.
 *
 * Android: the URI was a SAF tree document (`content://…/tree/primary:Music`)
 * and this decoded its volume id into `/storage/emulated/0/Music` as a best
 * guess. Desktop: the folder picker (`OpenDocumentTree`, see FilePickers)
 * already returns the real folder as a `file:` URI, so this is a plain
 * conversion. A bare path is accepted too, Windows (`C:\Music`, `\\nas\music`)
 * or POSIX, since stored settings may hold either; on Windows `Uri.parse`
 * would read a drive letter as a scheme, so that shape is checked first.
 *
 * Returns null for anything that is not a folder on this machine (a
 * `content://` URI carried over from an Android backup, a web URL), which the
 * callers already treat as "can't scan this folder".
 */
fun safTreeUriToFile(uri: Uri): File? = runCatching {
    val raw = uri.toString().trim()
    when {
        raw.isEmpty() -> null
        WINDOWS_PATH.matches(raw) || raw.startsWith("\\\\") -> File(raw)
        uri.scheme.equals("file", ignoreCase = true) ->
            Uri.parse("file:" + uri.schemeSpecificPart).toFile()
        uri.scheme == null -> File(raw)
        else -> null
    }?.absoluteFile?.normalize()
}.getOrNull()

/** [safTreeUriToFile] as the path string the folder settings store. */
fun safTreeUriToPath(uri: Uri): String? = safTreeUriToFile(uri)?.path

private val WINDOWS_PATH = Regex("""^[A-Za-z]:([\\/].*)?$""")
