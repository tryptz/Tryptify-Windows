package tf.monochrome.desktop.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.data.db.dao.DownloadDao
import tf.monochrome.desktop.data.db.entity.DownloadedTrackEntity
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.domain.model.AudioCodec
import tf.monochrome.desktop.domain.model.AudioQuality
import tf.monochrome.desktop.domain.model.PlaybackSource
import tf.monochrome.desktop.domain.model.SourceType
import tf.monochrome.desktop.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject
import tf.monochrome.desktop.R

/**
 * Loose grouping of downloaded tracks for the Albums section. Album rows are
 * keyed by (albumTitle, artistName) so a tracks-only download (album = null)
 * collapses into a synthetic "Singles" bucket.
 */
data class DownloadedAlbumGroup(
    val title: String,
    val artistName: String,
    val cover: String?,
    val trackCount: Int,
    val tracks: List<DownloadedTrackEntity>,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val appCtx: Context,
    private val downloadDao: DownloadDao,
    private val preferences: PreferencesManager,
) : ViewModel() {

    /**
     * Combined view of downloads: every track tracked by Room
     * (downloaded by this app) plus every audio file present in the
     * download folder that the app didn't write itself
     * (sideloaded files, prior installs, manual copies). Sideloaded
     * files are surfaced as synthetic DownloadedTrackEntity rows so
     * the rest of the screen — album grouping, tap-to-play, delete —
     * works uniformly. The synthetic id is a stable hash of the URI
     * so re-scans don't shuffle the list.
     */
    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> =
        combine(
            downloadDao.getDownloadedTracks(),
            preferences.downloadFolderUri,
        ) { roomRows, folderUri ->
            val sideloaded = scanFolderForSideloadedTracks(folderUri, roomRows)
            // Newest first overall; synthetic rows bias to file timestamp.
            (roomRows + sideloaded).sortedByDescending { it.downloadedAt }
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumGroups: StateFlow<List<DownloadedAlbumGroup>> =
        downloadedTracks
            .map { entities ->
                entities
                    .groupBy { (it.albumTitle ?: SINGLES_LABEL) to it.artistName }
                    .map { (key, list) ->
                        val cover = list.firstNotNullOfOrNull { it.albumCover }
                        DownloadedAlbumGroup(
                            title = key.first,
                            artistName = key.second,
                            cover = cover,
                            trackCount = list.size,
                            // Within the album sort by trackNumber-equivalent
                            // proxy (downloadedAt) so the play order is stable.
                            tracks = list.sortedBy { it.downloadedAt },
                        )
                    }
                    .sortedBy { it.title.lowercase() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * List the download folder and surface any audio file not already
     * represented in Room. Run from the combine() flow, which is dispatched
     * on IO.
     *
     * Desktop: the folder is a directory (a path or a `file:` URI, as
     * TrackDownloader reads the setting), not a SAF tree, so this lists it
     * with java.io.File. Android had nothing to list until a folder was
     * picked, because its downloads went to app-private storage; here they
     * land in a visible folder by default (AppPaths.downloadsDir), so that is
     * listed when none is set.
     */
    private fun scanFolderForSideloadedTracks(
        folderUriString: String?,
        knownRoomRows: List<DownloadedTrackEntity>,
    ): List<DownloadedTrackEntity> {
        val tree = folderUriString?.takeIf { it.isNotBlank() }
            ?.let { downloadFolderOf(it) }
            ?: appCtx.paths.downloadsDir
        if (!tree.isDirectory || !tree.canRead()) return emptyList()

        // First pass — collect everything once. listFiles() is the expensive
        // call; iterating the local list afterwards is free.
        val children = tree.listFiles().orEmpty().filter { it.isFile && it.canRead() }

        // Folder-level art (cover.jpg / folder.png / albumart.webp). The
        // TrackDownloader drops one of these alongside the audio so the system
        // MediaScanner picks it up. We match by stem so any of the four
        // common names work.
        val folderArtUri = children
            .firstOrNull { f ->
                val n = f.name.lowercase()
                val stem = n.substringBeforeLast('.')
                val ext = n.substringAfterLast('.', "")
                stem in COVER_STEMS && ext in IMAGE_EXTENSIONS
            }
            // Desktop: a file: URI, the form local artwork takes everywhere (LocalMediaRepository).
            ?.let { android.net.Uri.fromFile(it).toString() }

        // Per-track sidecar art (e.g. "Artist - Title.jpg" next to
        // "Artist - Title.flac"). Index by stem so the audio loop is O(n).
        val sidecarArtByStem: Map<String, String> = children.asSequence()
            .mapNotNull { f ->
                val n = f.name
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext !in IMAGE_EXTENSIONS) return@mapNotNull null
                val stem = n.substringBeforeLast('.')
                if (stem.lowercase() in COVER_STEMS) return@mapNotNull null
                stem to android.net.Uri.fromFile(f).toString()
            }
            .toMap()

        val knownPaths = knownRoomRows.mapTo(HashSet()) { it.filePath }
        val out = mutableListOf<DownloadedTrackEntity>()
        for (file in children) {
            val name = file.name
            // Desktop: no provider MIME type; the extension list decides.
            if (!isAudioFile(name, null)) continue
            val pathString = file.absolutePath
            if (pathString in knownPaths) continue
            val stem = name.substringBeforeLast('.')
            val cover = sidecarArtByStem[stem] ?: folderArtUri
            out += syntheticEntityFor(file, pathString, name, cover)
        }
        return out
    }

    private fun isAudioFile(name: String, mime: String?): Boolean {
        if (mime?.startsWith("audio/") == true) return true
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(".$it") }
    }

    private fun syntheticEntityFor(
        file: File,
        path: String,
        name: String,
        coverUri: String?,
    ): DownloadedTrackEntity {
        // Filename convention written by TrackDownloader is
        // "<artist> - <title>.<ext>" — try to recover the split, fall
        // back to the bare name.
        val withoutExt = name.substringBeforeLast('.', name)
        val (artist, title) = withoutExt.split(" - ", limit = 2)
            .let { if (it.size == 2) it[0] to it[1] else "" to withoutExt }
        // Stable id derived from the URI so successive scans don't drift the
        // LazyColumn keying. Always negative so it can't collide with a real
        // catalog track id (those are positive Longs from TIDAL/Qobuz).
        val syntheticId = -((path.hashCode().toLong() and 0x7FFFFFFFL) or 1L)
        return DownloadedTrackEntity(
            id = syntheticId,
            title = title,
            duration = 0,
            artistName = artist,
            albumTitle = null,
            albumCover = coverUri,
            filePath = path,
            quality = AudioQuality.LOSSLESS.name,
            sizeBytes = file.length(),
            downloadedAt = file.lastModified().takeIf { it > 0 } ?: 0L,
        )
    }

    // One-shot user messages (e.g. a delete that couldn't remove the file).
    private val _messages = MutableSharedFlow<tf.monochrome.desktop.ui.components.UiText>(extraBufferCapacity = 4)
    val messages: SharedFlow<tf.monochrome.desktop.ui.components.UiText> = _messages.asSharedFlow()

    fun deleteDownloads(tracks: List<DownloadedTrackEntity>) {
        viewModelScope.launch {
            val failed = tracks.count { !deleteOne(it) }
            if (failed > 0) {
                _messages.tryEmit(tf.monochrome.desktop.ui.components.UiText.Plural(R.plurals.delete_failed_count, failed))
            }
        }
    }

    /**
     * Removes the file and its Room record. Returns whether the file itself was
     * actually removed — a sideloaded file that cannot be deleted (read-only,
     * held open elsewhere) would otherwise silently reappear on the next folder
     * scan, so the caller surfaces a message on false. Runs off the main thread.
     */
    private suspend fun deleteOne(track: DownloadedTrackEntity): Boolean =
        withContext(Dispatchers.IO) {
            // Desktop: every row is a file path (no SAF content:// documents).
            val file = File(track.filePath)
            val fileRemoved = !file.exists() || file.delete()
            // App-written downloads live in Room; sideloaded rows don't, so this
            // is a harmless no-op for them (they leave once the file is gone).
            downloadDao.deleteDownloadedTrack(track.id)
            fileRemoved
        }

    /** The setting as TrackDownloader reads it: a plain path or a `file:` URI. */
    private fun downloadFolderOf(raw: String): File? = when {
        raw.startsWith("content://") -> null
        raw.startsWith("file:") -> runCatching { File(java.net.URI(raw)) }.getOrNull()
        else -> File(raw)
    }

    companion object {
        const val SINGLES_LABEL = "Singles"
        // Lower-case extensions TrackDownloader may produce + the formats
        // users typically sideload. Mime-type sniff still wins; this list
        // catches files SAF reports without a type (common on some
        // providers).
        private val AUDIO_EXTENSIONS = setOf(
            "flac", "alac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "wma",
        )
        // Common folder-level cover filenames (see also Android's MediaScanner
        // and most desktop tag editors). TrackDownloader writes "cover.jpg".
        private val COVER_STEMS = setOf("cover", "folder", "albumart", "album")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

// File-private extension: map a downloaded entity onto the unified track shape
// the player expects. Codec/sample-rate are inferred from the saved quality
// label; ExoPlayer sniffs the actual container so these are advisory.
fun DownloadedTrackEntity.toUnifiedTrack(): UnifiedTrack {
    val quality = runCatching { AudioQuality.valueOf(quality) }.getOrDefault(AudioQuality.LOSSLESS)
    val (codec, sampleRate, bitDepth) = when (quality) {
        AudioQuality.HI_RES -> Triple(AudioCodec.FLAC, 96_000, 24)
        AudioQuality.LOSSLESS -> Triple(AudioCodec.FLAC, 44_100, 16)
        AudioQuality.HIGH, AudioQuality.LOW -> Triple(AudioCodec.MP3, 44_100, null)
    }
    return UnifiedTrack(
        id = "download_$id",
        title = title,
        durationSeconds = duration,
        artistName = artistName.ifBlank { "Unknown Artist" },
        artistNames = listOfNotNull(artistName.takeIf { it.isNotBlank() }),
        albumArtistName = artistName.takeIf { it.isNotBlank() },
        albumTitle = albumTitle,
        albumId = albumTitle?.let { "download_album_${it.hashCode()}" },
        artworkUri = albumCover,
        source = PlaybackSource.LocalFile(
            filePath = filePath,
            codec = codec,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        ),
        sourceType = SourceType.LOCAL,
    )
}
