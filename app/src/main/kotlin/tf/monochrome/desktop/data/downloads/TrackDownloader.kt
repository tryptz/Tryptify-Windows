package tf.monochrome.desktop.data.downloads

import android.content.Context
import java.util.Locale
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import tf.monochrome.desktop.data.api.HiFiApiClient
import tf.monochrome.desktop.data.db.dao.DownloadDao
import tf.monochrome.desktop.data.db.entity.DownloadedTrackEntity
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.domain.model.AudioQuality
import tf.monochrome.desktop.domain.model.buildCoverUrl
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads one track: resolves a stream URL, streams it to disk, tags it,
 * saves lyrics and artwork, and records it in the database.
 *
 * This was the body of a `CoroutineWorker`, one instance per track. It is a
 * plain injectable now so [DownloadQueueRunner] can run several at a time under
 * one job and one notification — see [DownloadQueue] for why one job per track
 * was the wrong shape.
 *
 * Desktop: files land in a real folder — the one chosen in Settings
 * ([PreferencesManager.downloadFolderUri], a filesystem path here rather than a
 * SAF tree URI) or [tf.monochrome.desktop.platform.AppPaths.downloadsDir] —
 * through plain `java.io.File` writes. The naming, the FLAC tagging, the
 * embedded cover, the `.lrc` sidecar and the folder `cover.jpg` are the
 * Android app's.
 */
@Singleton
class TrackDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: HiFiApiClient,
    private val lrcLibClient: tf.monochrome.desktop.data.api.LrcLibClient,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val downloadDao: DownloadDao,
    private val qobuzIdRegistry: tf.monochrome.desktop.data.api.QobuzIdRegistry,
    private val localLibraryRevision: tf.monochrome.desktop.data.local.LocalLibraryRevision,
) {

    /** What became of one attempt. */
    enum class Outcome {
        SUCCESS,
        /** Failed in a way another attempt might survive — a dropped connection. */
        RETRYABLE,
        /** Failed for a reason retrying cannot fix — no instance, 404, wrong catalogue. */
        PERMANENT,
    }

    private companion object {
        const val TAG = "TrackDownloader"
    }

    /**
     * Runs one download to completion, reporting 0..1 through [onProgress].
     *
     * Cancellation propagates as it would anywhere else — the caller's scope
     * cancelling mid-transfer throws out of the read loop and the temp file is
     * cleaned up in the finally below.
     */
    suspend fun download(item: DownloadItem, onProgress: (Float) -> Unit): Outcome {
        val trackId = item.trackId
        val trackTitle = item.title
        val artistName = item.artistName
        val albumTitle = item.albumTitle
        val albumCover = item.albumCover
        val duration = item.duration
        val version = item.version
        val isThxSpatialAudio = item.isThxSpatialAudio
        // Apple identity travels separately from the track id. The id captured
        // at enqueue is authoritative; the registry lookup remains only as a
        // fallback for queue entries written before the field existed.
        val explicitAppleId = item.appleId.takeIf { it > 0L }
        val isApple = explicitAppleId != null || qobuzIdRegistry.isAppleTrack(trackId)
        val appleId = explicitAppleId ?: trackId
        val deezerId = item.deezerId.takeIf { it > 0L }
            ?: trackId.takeIf { qobuzIdRegistry.isDeezerTrack(it) && !qobuzIdRegistry.isQobuzTrack(it) }

        return try {
            // Get download quality preference
            val quality = preferences.downloadQuality.first()

            // Resolve the download URL. Apple tracks go through getAppleStreamUrl,
            // which streams straight from the home wrapper/agent over Tailscale when
            // an Apple Wrapper URL is configured, else falls back to the cloud
            // /api/apple/download-music. Everything else uses the Qobuz instance.
            // Apple first when the track carries an Apple identity. Otherwise
            // try the native (Qobuz/TIDAL) path, and if that yields nothing,
            // bridge to Apple by metadata — a track whose catalog id is a
            // synthetic hash has no usable native id, but the same recording is
            // almost always in the Apple catalog and the wrapper can decrypt it.
            var usedApple = isApple
            val streamUrl = if (deezerId != null) {
                // A Deezer pick downloads from Deezer, the same way a Qobuz
                // pick downloads from Qobuz: /api/deezer/download in the
                // chosen quality. No other catalogue stands in — the bare
                // Deezer id handed to Qobuz or TIDAL would fetch whatever
                // other song has that number, and the 30-second preview is
                // not a download.
                Log.i(TAG, "\"$trackTitle\" is Deezer (deezerId=$deezerId) - downloading from Deezer")
                apiClient.getDeezerDownloadUrl(deezerId, quality) ?: run {
                    Log.w(TAG, "Deezer could not serve \"$trackTitle\" (deezerId=$deezerId, q=$quality) - not falling back to another catalog")
                    return Outcome.PERMANENT
                }
            } else if (isApple) {
                // An Apple pick is wrapper-only. No Qobuz/TIDAL fallback and no
                // metadata bridge — those would hand back a different recording
                // than the one chosen in search. If the wrapper can't serve it,
                // the download fails and says so.
                Log.i(TAG, "\"$trackTitle\" is Apple (adamId=$appleId) - routing to the wrapper only")
                apiClient.getAppleStreamUrl(appleId, quality, atmos = isThxSpatialAudio) ?: run {
                    Log.w(TAG, "wrapper could not serve Apple adamId=$appleId (\"$trackTitle\", q=$quality, atmos=$isThxSpatialAudio) - not falling back to another catalog")
                    return Outcome.PERMANENT
                }
            } else {
                val native = runCatching {
                    apiClient.getTrackStream(trackId, quality, forDownload = true).streamUrl
                }.getOrNull()
                native ?: run {
                    // A Qobuz pick is Qobuz-only, on the same principle as the
                    // Apple branch above: the metadata bridge matches by title
                    // and artist, so it can hand back a different master or
                    // version than the one chosen in search. If Qobuz can't
                    // serve it, the download fails and says so.
                    if (qobuzIdRegistry.isQobuzTrack(trackId)) {
                        Log.w(TAG, "Qobuz could not serve \"$trackTitle\" (id=$trackId, q=$quality) - not falling back to another catalog")
                        return Outcome.PERMANENT
                    }
                    val bridged = apiClient.findAppleIdFor(
                        trackId = trackId,
                        title = trackTitle,
                        artist = artistName,
                        durationSeconds = duration,
                    )
                    if (bridged == null) {
                        Log.w(TAG, "no stream url for \"$trackTitle\" (id=$trackId, q=$quality) and no Apple match")
                        return Outcome.PERMANENT
                    }
                    Log.i(TAG, "bridged \"$trackTitle\" (id=$trackId) to Apple adamId=$bridged")
                    usedApple = true
                    apiClient.getAppleStreamUrl(bridged, quality, atmos = isThxSpatialAudio) ?: run {
                        Log.w(TAG, "Apple bridge found adamId=$bridged but no stream url for \"$trackTitle\"")
                        return Outcome.PERMANENT
                    }
                }
            }

            // Stream the audio into a temp FILE with progress. Never hold the
            // whole payload in memory: a plain httpClient.get() in Ktor 3
            // saves the entire body into a byte array (SavedCall) before the
            // channel is even read — 30-60 MB per FLAC — and the previous
            // ByteArrayOutputStream added a second full copy. Together they
            // OOM-crashed devices near the 256 MB art heap limit.
            onProgress(0.05f)
            val tempAudio = File.createTempFile("dl_audio", ".dl", context.cacheDir)
            try {
            val fetched = httpClient.prepareGet(streamUrl).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                // A Deezer file comes off Deezer's CDN striped with Blowfish;
                // decrypt it on the way to disk (same length, so the
                // truncation check below still holds).
                val decryptor = deezerId?.let { tf.monochrome.desktop.data.cache.DeezerStripeDecryptor(it) }
                tempAudio.outputStream().use { out ->
                    val sink: (ByteArray, Int, Int) -> Unit = { b, o, l -> out.write(b, o, l) }
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        if (decryptor != null) decryptor.feed(buffer, 0, read, sink) else out.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceIn(0.05f, 0.95f))
                        }
                    }
                    decryptor?.finish(sink)
                }
                // A short read means a truncated file, and for M4A that is
                // silently fatal: the container still opens but the audio is cut
                // mid-mdat, so it lands on disk looking fine and won't play.
                // Reject it here rather than saving a corrupt track.
                if (contentLength > 0 && totalRead != contentLength) {
                    Log.w(
                        TAG,
                        "truncated download for \"$trackTitle\": got $totalRead of $contentLength bytes",
                    )
                    return@execute false
                }
                true
            }
            if (!fetched) return Outcome.RETRYABLE

            // Detect what the backend actually delivered (not just what was
            // requested): the download instance can downgrade HI_RES→LOSSLESS,
            // and LOW/HIGH come back as MP3. Basing the extension/mime and the
            // stored record on the real bytes keeps lossy files from being
            // mislabelled .flac (breaks MediaStore + other players) and makes the
            // saved quality accurate. Only the 22-byte header is read.
            val targetDir = downloadDir()
            // Apple delivers an MP4/M4A container (ALAC/AAC/EC-3 Atmos), never
            // FLAC/MP3 — skip header sniffing + FLAC tagging for it.
            val actualQuality: AudioQuality
            val isFlac: Boolean
            if (usedApple) {
                actualQuality = quality
                isFlac = false
            } else {
                val header = ByteArray(22)
                val headerRead = tempAudio.inputStream().use { it.read(header) }
                actualQuality =
                    detectActualQuality(if (headerRead > 0) header.copyOf(headerRead) else ByteArray(0), quality)
                isFlac = actualQuality == AudioQuality.LOSSLESS || actualQuality == AudioQuality.HI_RES
            }

            // Fetched once and used twice: embedded in the FLAC below and saved
            // as the folder's cover.jpg afterwards. Losing the cover never fails
            // the download.
            val artBytes = albumCover?.takeIf { it.isNotBlank() }
                ?.let { runCatching { fetchAlbumArt(it) }.getOrNull() }

            // The Qobuz CDN FLACs arrive with no embedded metadata, so without
            // this every download lands on disk anonymous, and strict offline
            // players (Auxio, Symfonium, MediaStore) sort and group purely on
            // embedded tags. So every FLAC gets Vorbis comments and the cover,
            // not only THX/versioned ones. Tagging happens in place on the temp
            // file (JAudioTagger is file-based). Best-effort: a tagging failure
            // never fails the download (the bytes are good).
            if (isFlac) {
                tagFlacFile(
                    file = tempAudio,
                    item = item,
                    title = EmbeddedTags.baseTitle(trackTitle, version),
                    artwork = artBytes,
                )
            }
            val audioSizeBytes = tempAudio.length()

            val fileExt = if (usedApple) "m4a" else if (isFlac) "flac" else "mp3"
            val sanitizedTitle = sanitizeFileName("${artistName} - ${trackTitle}", trackId)
            val fileName = "$sanitizedTitle.$fileExt"
            // Desktop: the destination is always a real folder, so the two
            // Android paths — a SAF tree written through DocumentFile, and an
            // app-private fallback named by track id — are one streamed copy
            // under the readable "Artist - Title.ext" name. The MIME type that
            // DocumentFile.createFile needed is carried by the extension.
            val filePath: String = writeAudio(tempAudio, File(targetDir, fileName))

            // Save lyrics if enabled. TIDAL is preferred (best quality
            // synced LRC); LRCLib fills in for anything TIDAL 404s on,
            // which is most older / niche / non-Western catalog.
            val downloadLyricsEnabled = preferences.downloadLyrics.first()
            if (downloadLyricsEnabled) {
                try {
                    val lyrics = apiClient.getLyrics(trackId)
                        ?: lrcLibClient.lookup(
                            title = trackTitle,
                            artist = artistName,
                            album = albumTitle,
                            durationSeconds = duration.takeIf { it > 0 },
                        )
                    if (lyrics != null && lyrics.isSynced) {
                        val lrcContent = StringBuilder()
                        lyrics.lines.forEach { line ->
                            val minutes = line.timeMs / 1000 / 60
                            val seconds = (line.timeMs / 1000.0) % 60
                            val timeStr = String.format(Locale.US, "[%02d:%05.2f]", minutes, seconds)
                            lrcContent.append("$timeStr${line.text}\n")
                        }

                        // Sidecar next to the audio, same base name, so any
                        // player that reads .lrc files finds it.
                        File(targetDir, "$sanitizedTitle.lrc").writeText(lrcContent.toString())
                    }
                } catch (_: Exception) {
                }
            }

            // Save the album art alongside the track. Two reasons:
            //   1. A folder-level `cover.jpg` is what library scanners (this
            //      app's own folder scan included) attach as the album image
            //      for the MP3/M4A downloads that carry no embedded picture.
            //   2. Other players (and our own DownloadsScreen) can load the
            //      cover off-line.
            // Errors here are non-fatal — losing the cover shouldn't fail
            // the whole download.
            if (artBytes != null) {
                runCatching { saveAlbumArt(artBytes, sanitizedTitle, targetDir) }
            }

            // Desktop: there is no MediaStore to notify. localLibraryRevision
            // .bump() below is what tells the Local tab and the player that a
            // new file is on disk.

            // Insert into database
            downloadDao.insertDownloadedTrack(
                DownloadedTrackEntity(
                    id = trackId,
                    title = trackTitle,
                    duration = duration,
                    artistName = artistName,
                    albumTitle = albumTitle,
                    albumCover = albumCover,
                    filePath = filePath,
                    quality = actualQuality.name,
                    sizeBytes = audioSizeBytes,
                    downloadedAt = System.currentTimeMillis(),
                    version = version,
                    isThxSpatialAudio = isThxSpatialAudio
                )
            )
            // A new file is on disk — let the player stop streaming this song.
            localLibraryRevision.bump()

            onProgress(1f)
            Outcome.SUCCESS
            } finally {
                tempAudio.delete()
            }
        } catch (e: Exception) {
            // Never swallow this silently. A throw here puts the request back to
            // ENQUEUED for the backoff window, which the download list renders as
            // "Queued" — so a repeatedly-failing download is indistinguishable
            // from one the scheduler hasn't started, and there was nothing in
            // logcat to tell them apart.
            // A missing endpoint is a setup problem retries can't fix — fail
            // now so the row flips to "Failed" instead of faking three more
            // minutes of "Queued".
            // A missing endpoint is a setup problem no retry can fix; anything
            // else gets another go from the queue, which counts the attempts.
            val permanent = e is tf.monochrome.desktop.data.api.NoInstancesConfiguredException
            Log.w(
                TAG,
                "download failed for \"$trackTitle\" (id=$trackId, apple=$isApple) — " +
                    if (permanent) "not retryable" else "will retry",
                e,
            )
            if (permanent) Outcome.PERMANENT else Outcome.RETRYABLE
        }
    }

    /**
     * Downloads the album cover. [cover] is either a full URL (Qobuz, Apple)
     * or a bare TIDAL cover id, which isn't fetchable as-is — it is expanded
     * with [buildCoverUrl], largest size first, since the same bytes end up
     * embedded in the file and shown full-screen by offline players.
     */
    private suspend fun fetchAlbumArt(cover: String): ByteArray? {
        val urls = if (cover.contains("://")) listOf(cover)
            else listOf(1280, 640).map { buildCoverUrl(cover, it) }
        for (url in urls) {
            val bytes = runCatching {
                val response = httpClient.get(url)
                if (response.status.isSuccess()) response.readBytes() else null
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    /**
     * Saves the album cover both as `<sanitizedTitle>.jpg` (per-track
     * sidecar, matched by some MP3-style players) and as `cover.jpg` in the
     * same folder (the convention every library scanner reads). The caller
     * treats a failure as non-fatal.
     */
    private fun saveAlbumArt(
        bytes: ByteArray,
        sanitizedTitle: String,
        dir: File,
    ) {
        // Per-track sidecar.
        File(dir, "$sanitizedTitle.jpg").writeBytes(bytes)
        // Folder-level cover.jpg — the album thumbnail for the MP3/M4A
        // downloads that don't get an embedded METADATA_BLOCK_PICTURE. The
        // first track of an album writes it; later ones leave it alone.
        val coverFile = File(dir, "cover.jpg")
        if (!coverFile.exists()) coverFile.writeBytes(bytes)
    }

    /**
     * Infer the format actually delivered from the file bytes, so the saved record
     * reflects reality rather than the requested tier:
     *  - "fLaC" magic → FLAC; read bits-per-sample from STREAMINFO to tell
     *    HI_RES (≥24-bit) from LOSSLESS (16-bit), catching a HI_RES→LOSSLESS downgrade.
     *  - anything else → lossy (MP3) → report as HIGH.
     * Falls back to [requested] if the bytes are too short to classify.
     */
    private fun detectActualQuality(data: ByteArray, requested: AudioQuality): AudioQuality {
        if (data.size < 4) return requested
        val isFlac = data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()
        if (!isFlac) return AudioQuality.HIGH
        // STREAMINFO begins at byte 8 (4 magic + 4 block header). Bits-per-sample
        // is the 5-bit field straddling bytes 20 (low bit) and 21 (high 4 bits),
        // stored as value-1.
        if (data.size < 22) return AudioQuality.LOSSLESS
        val b20 = data[20].toInt() and 0xFF
        val b21 = data[21].toInt() and 0xFF
        val bitsPerSample = (((b20 and 0x01) shl 4) or (b21 shr 4)) + 1
        return if (bitsPerSample >= 24) AudioQuality.HI_RES else AudioQuality.LOSSLESS
    }

    /**
     * Streamed copy from the download temp file — never a whole-file byte
     * array. An earlier download of the same track is replaced, as the SAF
     * path did with `findFile(...)?.delete()`.
     */
    private fun writeAudio(source: File, target: File): String {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    /**
     * The folder downloads go to: the one chosen in Settings when it is a
     * usable directory, else the default under the user's Music folder.
     *
     * Settings may store either a plain path or a `file:` URI (the picker
     * hands back a File; `toUri().toString()` of one is a `file:` URI). A
     * `content://` tree URI carried over from an Android backup names a
     * folder this machine cannot see, so it falls through to the default
     * rather than failing every download.
     */
    private suspend fun downloadDir(): File {
        val configured = preferences.downloadFolderUri.first()?.takeIf { it.isNotBlank() }
        val chosen = configured?.let { resolveFolder(it) }
        val dir = chosen ?: context.paths.downloadsDir
        dir.mkdirs()
        if (dir.isDirectory && dir.canWrite()) return dir
        if (chosen != null) {
            Log.w(TAG, "download folder \"$configured\" is not writable - using ${context.paths.downloadsDir}")
            val fallback = context.paths.downloadsDir
            fallback.mkdirs()
            return fallback
        }
        return dir
    }

    private fun resolveFolder(raw: String): File? = when {
        raw.startsWith("content://") -> {
            Log.w(TAG, "download folder is an Android document tree ($raw) - not usable here")
            null
        }
        raw.startsWith("file:") -> runCatching { File(java.net.URI(raw)) }.getOrNull()
        else -> File(raw)
    }

    /**
     * The Android app's `"Artist - Title"` with the characters no file system
     * accepts replaced by `_`. Windows additionally refuses a name that ends
     * in a dot or a space (it silently strips them, so the file the database
     * records would not be the file on disk), hence the trailing trim; a name
     * that trims away to nothing falls back to the track id.
     */
    private fun sanitizeFileName(raw: String, trackId: Long): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.', ' ')
        return cleaned.ifEmpty { trackId.toString() }
    }

    /**
     * Embed Vorbis comments and the front cover into a FLAC file in place
     * (JAudioTagger is file-based, so no byte-array round trip). The download
     * temp carries a ".dl" extension and JAudioTagger picks its reader by
     * extension, so the file is renamed to a ".flac" alias for the tagging and
     * renamed back. Best-effort: on any failure the file is left playable and
     * the download still succeeds.
     */
    private fun tagFlacFile(
        file: File,
        item: DownloadItem,
        title: String,
        artwork: ByteArray?,
    ) {
        val alias = File(file.parentFile, "${file.nameWithoutExtension}_tag.flac")
        if (!file.renameTo(alias)) {
            Log.w(TAG, "tag: rename for tagging failed for \"$title\"")
            return
        }
        try {
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(alias)
            val tag = audioFile.tagOrCreateAndSetDefault as? org.jaudiotagger.tag.flac.FlacTag
                ?: return
            fun write(key: org.jaudiotagger.tag.FieldKey, value: String?) {
                value?.takeIf { it.isNotBlank() }?.let { tag.setField(key, it) }
            }
            write(org.jaudiotagger.tag.FieldKey.TITLE, title)
            write(org.jaudiotagger.tag.FieldKey.ARTIST, item.artistName)
            write(org.jaudiotagger.tag.FieldKey.ALBUM, item.albumTitle)
            // ALBUMARTIST keeps a record with featured guests together as one
            // album instead of splitting it across every ARTIST credit.
            write(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, item.albumArtist)
            // Without these, players fall back to sorting an album by title.
            write(org.jaudiotagger.tag.FieldKey.TRACK, item.trackNumber?.takeIf { it > 0 }?.toString())
            write(org.jaudiotagger.tag.FieldKey.DISC_NO, item.discNumber?.takeIf { it > 0 }?.toString())
            // FieldKey.YEAR is the Vorbis DATE comment.
            write(org.jaudiotagger.tag.FieldKey.YEAR, EmbeddedTags.releaseDate(item.releaseDate))
            write(org.jaudiotagger.tag.FieldKey.GENRE, item.genre)
            // Raw VERSION comment — the field Qobuz itself uses for the release.
            item.version?.takeIf { it.isNotBlank() }?.let { tag.setField("VERSION", it) }
            // COMMENT marker as belt-and-braces for players that ignore VERSION.
            if (item.isThxSpatialAudio) tag.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "THX Spatial Audio")
            // A picture the source already embedded is the label's own — keep it.
            if (artwork != null && tag.images.isEmpty()) embedCover(tag, artwork, title)
            audioFile.commit()
        } catch (e: Exception) {
            Log.w(TAG, "tag: FLAC tagging failed for \"$title\": ${e.message}")
        } finally {
            alias.renameTo(file)
        }
    }

    /**
     * Adds [bytes] as the front-cover METADATA_BLOCK_PICTURE. Built straight
     * from the bytes with [org.jaudiotagger.tag.flac.FlacTag.createArtworkField]
     * rather than JAudioTagger's `Artwork` type, which decodes the whole image
     * (through `javax.imageio` here) only to read its size. The picture block
     * records the image's dimensions, read here from its header alone.
     */
    private fun embedCover(tag: org.jaudiotagger.tag.flac.FlacTag, bytes: ByteArray, title: String) {
        val mime = EmbeddedTags.imageMime(bytes)
        if (mime == null || bytes.size > EmbeddedTags.MAX_EMBEDDED_ART_BYTES) {
            Log.i(TAG, "tag: not embedding cover for \"$title\" (type=$mime, ${bytes.size} bytes)")
            return
        }
        val (width, height) = EmbeddedTags.imageDimensions(bytes) ?: (0 to 0)
        tag.setField(
            tag.createArtworkField(
                bytes,
                org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID,
                mime,
                "",
                width.coerceAtLeast(0),
                height.coerceAtLeast(0),
                24,
                0,
            )
        )
    }
}
