package tf.monochrome.desktop.data.import_

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked playlist export off disk and hands it to [PlaylistCsv].
 *
 * Everything that can be decided without the platform lives in [PlaylistCsv]
 * so it can be tested against real exports; this class is the part that reads
 * the file — the bytes, and the name the playlist falls back to when the file
 * carries no title of its own.
 *
 * Desktop: on Android the picker returned a `content://` document and this
 * class needed a `ContentResolver` for both. Here the picker returns a
 * [File]; the bytes come from [File.inputStream] and the name from
 * [File.name]. [parseFromUri] stays for callers that still hold a `file:` URI.
 */
@Singleton
class CsvPlaylistParser @Inject constructor() {

    /**
     * The failure is carried, not flattened. A caller that turns this into
     * "could not parse the file" throws away the one sentence that tells the
     * listener which column was missing or which export to take instead.
     */
    suspend fun parseFile(file: File): Result<CsvPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readBytes(file)
            val name = file.name.substringBeforeLast('.')
            PlaylistCsv.parse(PlaylistCsv.decode(bytes), fallbackTitle = name)
        }
    }

    /**
     * The Android entry point, kept for its callers: a `file:` URI or a bare
     * path is parsed as that file. A `content://` document has no meaning on
     * this machine and is reported as such rather than guessed at.
     */
    suspend fun parseFromUri(uri: Uri): Result<CsvPlaylist> {
        val file = fileFor(uri)
            ?: return Result.failure(IllegalArgumentException("That file could not be opened."))
        return parseFile(file)
    }

    private fun fileFor(uri: Uri): File? {
        val raw = uri.toString()
        if (raw.isBlank() || raw.startsWith("content://")) return null
        // A Windows path such as C:\Users\... reads as a URI with scheme "C";
        // try the raw string as a path before trusting the scheme.
        File(raw).takeIf { it.isFile }?.let { return it }
        if (uri.scheme.equals("file", ignoreCase = true)) {
            runCatching { File(java.net.URI(raw)) }.getOrNull()?.takeIf { it.isFile }?.let { return it }
            uri.path?.let { File(it) }?.takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * The picker has to accept every file type — Apple Music exports a `.txt`,
     * and some exporters send `application/octet-stream` — so the file may be
     * anything at all, including something far too large to hold in memory.
     * Reading stops the moment the cap is passed and says so, rather than
     * truncating into a file that would parse cleanly as half a playlist.
     */
    private fun readBytes(file: File): ByteArray {
        if (!file.isFile || !file.canRead()) throw IllegalArgumentException("That file could not be opened.")
        return file.inputStream().use { input -> readCapped(input) }
    }

    private fun readCapped(input: InputStream): ByteArray {
        // Read in chunks rather than all at once so the cap can stop the read
        // part-way through an oversized file.
        val buffered = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var overCap = false
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            buffered.write(chunk, 0, read)
            if (buffered.size() > PlaylistCsv.MAX_BYTES) {
                overCap = true
                break
            }
        }
        val bytes = buffered.toByteArray()
        if (overCap) {
            throw IllegalArgumentException(
                "That file is larger than ${PlaylistCsv.MAX_BYTES / (1024 * 1024)} MB — " +
                    "it is probably not a playlist export.",
            )
        }
        if (bytes.isEmpty()) throw IllegalArgumentException("That file is empty.")
        return bytes
    }
}
