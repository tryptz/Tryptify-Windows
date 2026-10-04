package tf.monochrome.desktop.data.local.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import tf.monochrome.desktop.data.local.tags.FfmpegProbe
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.platform.AppPaths
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AudioFileInfo(
    /** The file in library form: absolute, `/`-separated on every platform (see [MediaStoreSource.toLibraryPath]). */
    val absolutePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Last-modified time, epoch milliseconds. */
    val dateModified: Long,
    /**
     * Desktop: always 0. MediaStore knew every file's length before the scan
     * read it; a folder walk does not, and [MediaScanner] applies the minimum
     * length once it has read the tags.
     */
    val duration: Long,
    val uri: Uri
)

/**
 * Where the library scan finds its files.
 *
 * Desktop: Android asked MediaStore — the system's own index of shared
 * storage — for every audio row with `IS_MUSIC = 1` and a minimum `DURATION`,
 * plus the video rows whose audio track is E-AC-3 (Atmos music videos). There
 * is no system media index on Windows, so this walks the library folders
 * instead and produces the same [AudioFileInfo] rows. The class keeps its
 * Android name so [MediaScanner], the onboarding screen and the unit tests
 * compile unchanged.
 *
 * What maps to what:
 * - **Which folders.** The user's library folders (`userFolderRoots`), as on
 *   Android. Android's "no folders chosen" meant the whole device, which a
 *   folder walk cannot mean: here it is the user's Music folder (the Windows
 *   known folder, which OneDrive may have moved) plus the download folders,
 *   which is where MediaStore's whole-device scan found music on a phone.
 * - **Which files.** Audio by extension, where MediaStore went by MIME type;
 *   MIDI stays out. Folders holding a `.nomedia` file and hidden folders are
 *   skipped, as MediaStore skips them.
 * - **Video containers.** An .mp4/.mkv/.ts with a video track is admitted only
 *   when its audio is E-AC-3 or AC-4, exactly Android's Atmos-video rule. One
 *   with no video track at all is simply an audio file here. Android searched
 *   for those videos device-wide; a walk only sees the library folders.
 * - **Minimum length.** Not applied here — see [AudioFileInfo.duration].
 * - **Paths.** Library form: absolute and `/`-separated, so `C:/Music/a.flac`
 *   on Windows. The folder tree, the folder queries, exclusion and the title
 *   from a file name all split on `/`, as they did on Android;
 *   `java.io.File` and FFmpeg accept the form as it is.
 */
@Singleton
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) {

    /**
     * Video containers already probed this session: path → (stamp, verdict).
     * The verdict is the audio MIME to admit the file under, or "" for "not a
     * library file". Keyed on modification time and size, so a replaced file
     * is probed again.
     */
    private val videoVerdicts = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Every audio file under the library folders, newest first (MediaStore's
     * `DATE_MODIFIED DESC`). [minDurationMs] is accepted for the Android
     * signature and applied by [MediaScanner].
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun queryAllAudio(
        minDurationMs: Long = 30_000,
        excludedPaths: Set<String> = emptySet(),
        folderRoots: Set<String> = emptySet()
    ): List<AudioFileInfo> {
        val job = currentCoroutineContext()[Job]
        val results = walk(resolveRoots(folderRoots), normalizeExcluded(excludedPaths), job) { true }
        currentCoroutineContext().ensureActive()
        return results
    }

    /**
     * Files modified after [sinceTimestamp] (epoch ms), for the incremental
     * scan.
     *
     * Desktop: [knownPaths] — the paths the library already holds — widens this
     * to every file the library does not know yet, whatever its time. Copying
     * or moving a file into a folder keeps its modification time on Windows, so
     * an album dragged in from elsewhere would be "older than the last scan"
     * and never be found; MediaStore dated rows by when it indexed them.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun queryModifiedSince(
        sinceTimestamp: Long,
        minDurationMs: Long = 30_000,
        folderRoots: Set<String> = emptySet(),
        excludedPaths: Set<String> = emptySet(),
        knownPaths: Set<String>? = null,
    ): List<AudioFileInfo> {
        val job = currentCoroutineContext()[Job]
        val results = walk(resolveRoots(folderRoots), normalizeExcluded(excludedPaths), job) { info ->
            info.dateModified > sinceTimestamp || (knownPaths != null && info.absolutePath !in knownPaths)
        }
        currentCoroutineContext().ensureActive()
        return results
    }

    /**
     * Count audio files under [path]. Used by onboarding's folder picker for
     * the "Found N tracks in this folder" preview.
     *
     * Desktop: a walk of the folder, by extension. It does not read tags, so a
     * file shorter than the scan's minimum is still counted, and video
     * containers (admitted only for Atmos audio) are not.
     */
    @Suppress("UNUSED_PARAMETER")
    fun countAudioUnderPath(path: String, minDurationMs: Long = 30_000): Int {
        val root = rootPathOf(path) ?: return 0
        var count = 0
        visitTree(root, emptySet(), null) { file, _ ->
            val name = file.fileName?.toString() ?: return@visitTree
            if (isAudioFileName(name)) count++
        }
        return count
    }

    /**
     * Desktop: configured library folders that cannot be reached right now —
     * an unplugged drive, a share that is offline — in library form.
     * [MediaScanner] keeps the tracks under these instead of pruning them, so
     * starting the app without the external drive does not empty the library
     * and force a full re-read when it comes back. Removing the folder from
     * the library is what forgets its tracks.
     */
    fun unreachableRoots(folderRoots: Set<String>): Set<String> =
        folderRoots.mapNotNullTo(HashSet()) { raw ->
            rootPathOf(raw)?.takeIf { !Files.isDirectory(it) }?.let { toLibraryPath(it).trimEnd('/') }
        }

    // ── Walking ──────────────────────────────────────────────────────

    private fun walk(
        roots: List<Path>,
        excluded: Set<String>,
        job: Job?,
        keep: (AudioFileInfo) -> Boolean,
    ): List<AudioFileInfo> {
        val started = System.currentTimeMillis()
        val found = LinkedHashMap<String, AudioFileInfo>()
        for (root in roots) {
            visitTree(root, excluded, job) { file, attrs ->
                val name = file.fileName?.toString() ?: return@visitTree
                val ext = name.substringAfterLast('.', "").lowercase()
                val isAudio = isAudioFileName(name)
                val isVideo = !isAudio && ext in VIDEO_CONTAINERS
                if (!isAudio && !isVideo) return@visitTree

                val path = toLibraryPath(file)
                if (path in found || isExcluded(path, excluded)) return@visitTree
                val info = AudioFileInfo(
                    absolutePath = path,
                    displayName = name,
                    mimeType = AUDIO_MIME[ext] ?: "audio/$ext",
                    sizeBytes = attrs.size(),
                    dateModified = attrs.lastModifiedTime().toMillis(),
                    duration = 0L,
                    uri = Uri.fromFile(file.toFile()),
                )
                // The cheap filter first: an incremental scan rejects most
                // files here, before any container is opened.
                if (!keep(info)) return@visitTree
                if (isVideo) {
                    val audioMime = videoAudioMime(path, ext, info.dateModified, info.sizeBytes) ?: return@visitTree
                    // Report the audio MIME, not the container's, so
                    // downstream codec detection resolves E-AC-3.
                    found[path] = info.copy(mimeType = audioMime)
                } else {
                    found[path] = info
                }
            }
        }
        Log.d(TAG, "walked ${roots.size} folder(s): ${found.size} file(s) in ${System.currentTimeMillis() - started} ms")
        return found.values.sortedByDescending { it.dateModified }
    }

    /**
     * Visit every regular file under [start], following links (a library on
     * another drive is often a junction or a symlink; the JDK detects loops)
     * and skipping what MediaStore skips. [job] stops the walk when the scan is
     * cancelled — a library walk is long and blocking.
     */
    private fun visitTree(
        start: Path,
        excluded: Set<String>,
        job: Job?,
        onFile: (Path, BasicFileAttributes) -> Unit,
    ) {
        if (!Files.isDirectory(start)) return
        try {
            Files.walkFileTree(
                start,
                EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (job != null && !job.isActive) return FileVisitResult.TERMINATE
                        if (dir != start && isSkippedDirectory(dir)) return FileVisitResult.SKIP_SUBTREE
                        if (excluded.isNotEmpty() && isExcluded(toLibraryPath(dir), excluded)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (job != null && !job.isActive) return FileVisitResult.TERMINATE
                        if (attrs.isRegularFile) onFile(file, attrs)
                        return FileVisitResult.CONTINUE
                    }

                    // Unreadable folders (System Volume Information, a share
                    // that dropped, a link loop) are skipped, never fatal.
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (e: IOException) {
            Log.w(TAG, "walking $start failed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "walking $start not permitted", e)
        }
    }

    /**
     * Hidden folders (".thumbnails", ".git"), Windows' system folders
     * ("$RECYCLE.BIN", "System Volume Information") and any folder holding a
     * `.nomedia` file — the folders MediaStore never indexed.
     */
    private fun isSkippedDirectory(dir: Path): Boolean {
        val name = dir.fileName?.toString() ?: return false
        if (name.startsWith('.') || name.startsWith('$')) return true
        if (name.equals("System Volume Information", ignoreCase = true)) return true
        return Files.exists(dir.resolve(".nomedia"))
    }

    // ── Atmos video containers ───────────────────────────────────────
    // On Android an .mp4 that carries a video stream lands in MediaStore's
    // *video* table, never in the audio table, even when its audio track is
    // Dolby Atmos and the player renders it fine. Atmos music videos are the
    // case that matters, so video files are admitted only when they actually
    // carry an E-AC-3 (or AC-4) track. Everything else (screen recordings,
    // camera clips) stays out of the library. A container with no video track
    // is an audio file, whatever its extension.

    private fun videoAudioMime(path: String, ext: String, mtime: Long, size: Long): String? {
        val stamp = mtime * 31 + size
        videoVerdicts[path]?.let { (seen, verdict) -> if (seen == stamp) return verdict.ifEmpty { null } }
        // The only expensive step, and it runs last so the cheap filters
        // have already thrown most candidates out.
        val probe = FfmpegProbe.probe(path, readStreamInfo = ext in TRANSPORT_STREAMS)
            // An unreadable or damaged file skips quietly, and is not
            // remembered: it may be a download still in progress.
            ?: return null
        val verdict = when {
            !probe.hasAudio -> null
            !probe.hasVideo -> probe.audioMime ?: AUDIO_MIME[ext] ?: "audio/mp4"
            isEac3Mime(probe.audioMime) -> probe.audioMime
            else -> null
        }
        videoVerdicts[path] = stamp to (verdict ?: "")
        return verdict
    }

    // ── Roots ────────────────────────────────────────────────────────

    private suspend fun resolveRoots(folderRoots: Set<String>): List<Path> {
        val configured = folderRoots.mapNotNull { rootPathOf(it) }
        // Empty = no restriction on Android (the whole device); see the class doc.
        val roots = if (folderRoots.isEmpty()) defaultRoots() else configured
        return withoutNested(roots)
    }

    /** Music folder + the configured download folder + the app's default one. */
    private suspend fun defaultRoots(): List<Path> {
        val dirs = ArrayList<File>()
        musicFolder()?.let { dirs += it }
        preferences.downloadFolderUri.first()?.let { rootFileOf(it) }?.let { dirs += it }
        dirs += context.paths.downloadsDir
        return dirs.filter { it.isDirectory }
            .mapNotNull { runCatching { it.toPath().toAbsolutePath().normalize() }.getOrNull() }
            .distinct()
    }

    /** The user's Music folder: the Windows known folder (OneDrive may have moved it), else ~/Music. */
    private fun musicFolder(): File? {
        if (AppPaths.isWindows) {
            runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Music) }.getOrNull()
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
                ?.let { return it }
        }
        return File(System.getProperty("user.home"), "Music").takeIf { it.isDirectory }
    }

    /** One walk per tree: a folder inside another chosen folder is already covered. */
    private fun withoutNested(roots: List<Path>): List<Path> {
        val kept = ArrayList<Path>()
        for (root in roots.distinct().sortedBy { it.nameCount }) {
            // add(), not +=: a Path is itself an Iterable<Path>, so += would append its name parts.
            if (kept.none { root.startsWith(it) }) kept.add(root)
        }
        return kept
    }

    private fun normalizeExcluded(excluded: Set<String>): Set<String> =
        excluded.mapTo(HashSet()) { it.replace('\\', '/') }

    companion object {
        private const val TAG = "LibraryWalker"

        /**
         * Audio files the library takes, by extension: the formats FFmpeg
         * decodes that people keep music in. MIDI is not here (see
         * [isExcludedExtension]); neither are video containers, which are
         * probed ([VIDEO_CONTAINERS]).
         */
        val AUDIO_EXTENSIONS: Set<String> = setOf(
            // Lossy
            "mp3", "mp2", "mp1", "aac", "m4a", "m4b", "m4p", "ogg", "oga", "opus", "spx",
            "wma", "mpc", "amr", "ra",
            // Lossless
            "flac", "alac", "wav", "wave", "w64", "rf64", "aif", "aiff", "aifc",
            "ape", "tak", "wv", "tta", "shn", "caf", "au",
            // High-res / DSD
            "dsf", "dff",
            // Dolby / DTS elementary streams, audio-only Matroska
            "ac3", "ec3", "eac3", "dts", "mka",
        )

        /** Containers that may carry video; admitted by [videoAudioMime]. */
        val VIDEO_CONTAINERS: Set<String> = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "mts")

        private val TRANSPORT_STREAMS = setOf("ts", "m2ts", "mts")

        /** The MIME MediaStore would have filed each extension under; informational. */
        private val AUDIO_MIME: Map<String, String> = mapOf(
            "flac" to "audio/flac",
            "mp3" to "audio/mpeg", "mp2" to "audio/mpeg", "mp1" to "audio/mpeg",
            "aac" to "audio/aac",
            "m4a" to "audio/mp4", "m4b" to "audio/mp4", "m4p" to "audio/mp4", "alac" to "audio/mp4",
            "mp4" to "audio/mp4",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "spx" to "audio/ogg",
            "wma" to "audio/x-ms-wma",
            "mpc" to "audio/x-musepack",
            "amr" to "audio/amr",
            "ra" to "audio/x-pn-realaudio",
            "wav" to "audio/x-wav", "wave" to "audio/x-wav", "rf64" to "audio/x-wav",
            "w64" to "audio/x-w64",
            "aif" to "audio/x-aiff", "aiff" to "audio/x-aiff", "aifc" to "audio/x-aiff",
            "ape" to "audio/x-ape",
            "tak" to "audio/x-tak",
            "wv" to "audio/x-wavpack",
            "tta" to "audio/x-tta",
            "shn" to "audio/x-shorten",
            "caf" to "audio/x-caf",
            "au" to "audio/basic",
            "dsf" to "audio/x-dsf", "dff" to "audio/x-dff",
            "ac3" to "audio/ac3", "ec3" to "audio/eac3", "eac3" to "audio/eac3",
            "dts" to "audio/vnd.dts",
            "mka" to "audio/x-matroska", "webm" to "audio/webm",
        )

        private fun isAudioFileName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in AUDIO_EXTENSIONS && !isExcludedExtension(name)
        }

        // MIDI files (.mid, .midi, .kar, .rmi) aren't recordings — they're
        // synth instructions and the app can't play them. MediaStore indexed
        // them as music, so Android excluded them explicitly; kept for the
        // same reason should one ever be added to the list above.
        private fun isExcludedExtension(path: String): Boolean {
            val ext = path.substringAfterLast('.', "").lowercase()
            return ext == "mid" || ext == "midi" || ext == "kar" || ext == "rmi"
        }

        // AC-4 ("audio/ac4") is the other Dolby codec Atmos music videos ship
        // with; it plays through the decoder rather than the JOC renderer.
        private fun isEac3Mime(mime: String?): Boolean =
            mime == "audio/eac3" || mime == "audio/eac3-joc" || mime == "audio/ac4"

        /**
         * A path in library form: absolute, normalised, with `/` separators on
         * every platform (`C:/Music/Album/01.flac` on Windows). Every path the
         * scan writes to `local_tracks.filePath` is in this form.
         */
        fun toLibraryPath(path: Path): String =
            path.toAbsolutePath().normalize().toString().replace('\\', '/')

        fun toLibraryPath(file: File): String = toLibraryPath(file.toPath())

        /**
         * A configured folder as a directory: a plain path, or a `file:` URI
         * (what a folder picker's `toUri()` gives). A `content://` tree is an
         * Android document-tree grant, which nothing here can open.
         */
        internal fun rootFileOf(raw: String): File? = when {
            raw.isBlank() -> null
            raw.startsWith("content://") -> null
            raw.startsWith("file:") -> runCatching { File(URI(raw)) }.getOrNull()
            else -> File(raw)
        }

        internal fun rootPathOf(raw: String): Path? =
            rootFileOf(raw)?.let { runCatching { it.toPath().toAbsolutePath().normalize() }.getOrNull() }

        /**
         * True when [path] falls under one of [roots] (or roots is empty =
         * unrestricted). A root matches its own path and descendants only —
         * the trailing '/' in the prefix check keeps /Music from also
         * matching /MusicVideos.
         */
        fun isUnderRoots(path: String, roots: Set<String>): Boolean {
            if (roots.isEmpty()) return true
            return roots.any { root ->
                val r = root.trimEnd('/')
                path == r || path.startsWith("$r/")
            }
        }

        /**
         * True when [path] sits under one of [excluded].
         *
         * The same boundary rule as [isUnderRoots], and for the same reason:
         * this used to be a bare `startsWith`, so excluding /Music also
         * excluded /Music2 and every track in it disappeared from the library
         * on the next scan. The delete that runs at exclusion time always had
         * the boundary — the scan filter did not, so the two disagreed about
         * what "this folder" meant.
         *
         * Empty means nothing is excluded, which is the opposite of what an
         * empty root set means to [isUnderRoots]; hence a function of its own
         * rather than a call through to it.
         */
        fun isExcluded(path: String, excluded: Set<String>): Boolean {
            if (excluded.isEmpty()) return false
            return excluded.any { root ->
                val r = root.trimEnd('/')
                r.isNotEmpty() && (path == r || path.startsWith("$r/"))
            }
        }

        /** Escape %, _ and \ for a LIKE pattern using '\' as the escape char. */
        fun escapeLikePattern(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
