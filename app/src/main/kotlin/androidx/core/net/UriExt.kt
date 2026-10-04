// Desktop stand-in for androidx.core.net's Uri extensions (toUri, toFile).
package androidx.core.net

import android.net.Uri
import java.io.File

fun String.toUri(): Uri = Uri.parse(this)

fun File.toUri(): Uri = Uri.fromFile(this)

fun Uri.toFile(): File {
    require(scheme == "file") { "Uri lacks 'file' scheme: $this" }
    val p = requireNotNull(path) { "Uri path is null: $this" }
    // Windows: "/C:/Music/a.flac" is how a drive path appears after the host part.
    return if (p.length >= 3 && p[0] == '/' && p[2] == ':' && p[1].isLetter()) File(p.substring(1)) else File(p)
}
