package android.content

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection

/**
 * The part of Android's ContentResolver the ported code uses, over `file:`
 * URIs (what the desktop pickers return) and bare paths. There are no content
 * providers on the desktop, so anything else is reported as not found, the
 * way Android reports a provider that has gone away.
 */
class ContentResolver internal constructor() {

    fun openInputStream(uri: Uri): InputStream? = FileInputStream(fileOf(uri))

    fun openOutputStream(uri: Uri): OutputStream? = openOutputStream(uri, "w")

    /** Modes as Android's: "w"/"wt" truncate, "wa" appends. */
    fun openOutputStream(uri: Uri, mode: String): OutputStream? {
        val file = fileOf(uri)
        file.parentFile?.mkdirs()
        return FileOutputStream(file, mode.contains('a'))
    }

    /** Answers OpenableColumns (display name and size) for a file, which is all the app asks. */
    @Suppress("UNUSED_PARAMETER")
    fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = runCatching { fileOf(uri) }.getOrNull()?.takeIf { it.exists() } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    fun getType(uri: Uri): String? = runCatching { URLConnection.guessContentTypeFromName(fileOf(uri).name) }.getOrNull()

    /** Desktop: file access does not expire, so there is nothing to persist. */
    @Suppress("UNUSED_PARAMETER")
    fun takePersistableUriPermission(uri: Uri, modeFlags: Int) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) = Unit

    private fun fileOf(uri: Uri): File = when (uri.scheme?.lowercase()) {
        "file" -> uri.toFile()
        null, "" -> File(uri.toString())
        else -> if (uri.scheme!!.length == 1) File(uri.toString()) // "C:\..." parsed as scheme C
        else throw FileNotFoundException("no content provider for $uri on the desktop")
    }
}
