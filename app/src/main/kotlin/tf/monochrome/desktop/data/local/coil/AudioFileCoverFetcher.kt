package tf.monochrome.desktop.data.local.coil

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.filePath
import coil3.request.Options
import okio.Buffer
import tf.monochrome.desktop.data.local.tags.FfmpegProbe
import tf.monochrome.desktop.data.local.tags.embeddedPictureOf
import tf.monochrome.desktop.data.local.tags.readWithJaudiotagger
import java.io.File
import java.io.IOException

/**
 * Coil 3 fetcher that pulls embedded album art directly from an audio file.
 * Activates when the model is a file path or file:// URI pointing at one of
 * the [AUDIO_EXTENSIONS] formats — anything else (cached JPG/PNG/WebP,
 * network URLs) falls through to Coil's built-in fetchers.
 *
 * Why this exists: the local-media scanner caches embedded art at scan time,
 * but cache files get evicted, scans miss freshly-downloaded tracks until
 * MediaScanner runs, and some files keep their art only in container atoms
 * that the cache layer hasn't been taught to read. Pointing the player's
 * artwork URI at the audio file itself (and letting this fetcher pull the
 * picture on demand) means the cover is shown whenever the file has one,
 * regardless of cache state.
 *
 * Desktop: the picture comes from JAudioTagger (APIC, `covr`, FLAC/Vorbis
 * pictures), or from FFmpeg's attached-picture stream for the formats
 * JAudioTagger cannot open, in place of `MediaMetadataRetriever.embeddedPicture`.
 * The bytes are handed to Coil undecoded, so its Skia decoder does the decode
 * and the downsampling to `options.size` that Android did by hand with
 * `BitmapFactory` and `Bitmap.scale`.
 */
class AudioFileCoverFetcher(
    private val filePath: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = File(filePath)
        // Desktop: a miss is an error, not a null. Null hands the request to
        // the next fetcher, Coil's file fetcher, whose Skia decoder reads the
        // whole file into memory before failing on it: a hundred megabytes for
        // a long WAV, per row, per scroll. Android's BitmapFactory gave up
        // after the header, so null cost it nothing.
        if (!file.exists()) throw NoCover(filePath)
        // A file with no embedded picture costs the same open as one with a
        // cover, returns nothing, and is asked again the next time the row
        // scrolls back — Coil caches images, not the absence of one. A device
        // log shows 22 of these in five seconds from a handful of art-less
        // tracks being recycled through a list.
        val stamp = file.lastModified()
        if (NoEmbeddedArt.known(filePath, stamp)) throw NoCover(filePath)

        val ext = filePath.substringAfterLast('.', "").lowercase()
        val audioFile = readWithJaudiotagger(file, ext)
        val bytes = if (audioFile != null) {
            embeddedPictureOf(audioFile.tag)
        } else {
            // JAudioTagger has no reader for this format (or could not parse
            // the file); libavformat exposes the cover as an attached picture.
            val probe = FfmpegProbe.probe(filePath, wantPicture = true)
                ?: throw NoCover(filePath) // could not open: maybe still being written; not remembered
            probe.picture
        }
        if (bytes == null || bytes.isEmpty()) {
            NoEmbeddedArt.remember(filePath, stamp)
            throw NoCover(filePath)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                fileSystem = options.fileSystem,
            ),
            mimeType = null, // sniffed by the decoder; tags often mislabel PNG as JPEG
            dataSource = DataSource.DISK,
        )
    }

    /**
     * The fetcher's answer for "this audio file shows no cover". No stack
     * trace: it is an expected outcome, raised once per art-less row.
     */
    private class NoCover(path: String) : IOException("no embedded picture: $path") {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Registered on the image loader. The Context parameter is unused, kept so
     * the Android call site (`Factory(this@MonochromeApp)`) and a desktop
     * `Factory()` both compile.
     */
    class Factory(@Suppress("unused") private val context: Context? = null) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = localPathOf(data) ?: return null
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXTENSIONS) return null
            return AudioFileCoverFetcher(path, options)
        }

        /**
         * The file a model names, or null when it is not a local file.
         *
         * Desktop: Android only ever saw `file:///storage/…` here. On Windows
         * three spellings arrive: `file:///C:/…` (Uri.fromFile), `C:\…`
         * (File.absolutePath; Coil reads it as a scheme-less path), and
         * `C:/…` (a library track path), which Coil parses as scheme "C".
         * [filePath] turns the first two into a native path.
         */
        private fun localPathOf(data: coil3.Uri): String? {
            val scheme = data.scheme
            return when {
                scheme == "file" || scheme == null -> data.filePath
                scheme != null && scheme.length == 1 && scheme[0].isLetter() -> data.toString()
                else -> null
            }
        }
    }

    /**
     * Paths whose audio holds no embedded picture, so the decoder is not opened
     * for them twice.
     *
     * Keyed on the file's modification time as well as its path, so retagging a
     * track puts its cover back without anything having to clear this.
     *
     * Deliberately forgetful and small: it is an optimisation for rows going
     * past on screen, and a bounded LRU cannot grow into a leak on a library of
     * any size. Missing an entry costs one wasted open, which is what happened
     * every time before.
     *
     * Only a definite "opened, nothing inside" is remembered. A failure to open
     * might be a file still being written or a transient read error, and
     * writing that off permanently would hide a cover that does exist.
     *
     * `local_tracks` already carries `hasEmbeddedArt` from the scanner, which is
     * the same fact recorded properly; consulting it from here would mean a
     * database hit per fetch and an entry point into Hilt from a Coil
     * component, so this stays in memory.
     */
    private object NoEmbeddedArt {
        private const val MAX_ENTRIES = 512

        private val seen = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) =
                    size > MAX_ENTRIES
            }
        )

        fun known(path: String, stamp: Long): Boolean = seen[path] == stamp

        fun remember(path: String, stamp: Long) {
            seen[path] = stamp
        }
    }

    internal companion object {
        /**
         * Extensions this fetcher claims. Also read by StreamResolver, which
         * has to recognise the same "artwork URI is really an audio file"
         * case in order NOT to hand it to Media3 — see the note there.
         */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus",
            "wav", "wma", "aif", "aiff", "ape", "dsf", "dff",
            // Desktop: the other formats the folder scanner admits. Each is a
            // file Coil's own fetcher would otherwise read whole and fail to
            // decode; here it gets its cover, or a cheap miss.
            "m4b", "m4p", "aifc", "wave", "w64", "wv", "tak", "tta", "mpc", "mka",
            "spx", "alac", "mp2", "ac3", "ec3", "eac3", "dts", "caf",
            // …and the video containers it admits for their Atmos audio, which
            // are larger still.
            "m4v", "mov", "mkv", "webm", "ts", "m2ts", "mts",
        )
    }
}
