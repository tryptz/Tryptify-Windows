// Desktop stand-in for androidx.core.net's Uri extensions (toUri, toFile).
package androidx.core.net

import android.net.Uri
import java.io.File

fun String.toUri(): Uri = Uri.parse(this)

fun File.toUri(): Uri = Uri.fromFile(this)

fun Uri.toFile(): File {
    require(scheme == "file") { "Uri lacks 'file' scheme: $this" }
    return File(requireNotNull(path) { "Uri path is null: $this" })
}
