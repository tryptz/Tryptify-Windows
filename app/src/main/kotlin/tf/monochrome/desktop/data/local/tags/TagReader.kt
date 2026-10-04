package tf.monochrome.desktop.data.local.tags

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.reference.GenreTypes
import org.jaudiotagger.tag.reference.PictureTypes
import tf.monochrome.desktop.domain.model.AudioCodec
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

data class AudioTags(
    // Core identity
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,

    // Extended metadata
    val composer: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,

    // Audio properties
    val codec: AudioCodec = AudioCodec.UNKNOWN,
    val sampleRate: Int = 44100,
    val bitDepth: Int? = null,
    val bitRate: Int = 0,
    val channels: Int = 2,
    val durationSeconds: Int = 0,

    // Replay Gain (parsed from custom tags when available)
    val replayGainTrack: Float? = null,
    val replayGainAlbum: Float? = null,

    // Artwork
    val hasEmbeddedArt: Boolean = false,
    val artworkCacheKey: String? = null,

    // THX Spatial Audio — detected from title/album text or, for FLAC, the
    // embedded VERSION/COMMENT Vorbis fields a download wrote.
    val isThxSpatialAudio: Boolean = false,

    // Dolby Atmos — detected from the MIME type, file extension, or a "Dolby
    // Atmos" phrase in title/album. Authoritative JOC detection arrives with
    // the native demux (see cpp/atmos).
    val isDolbyAtmos: Boolean = false,

    // File info
    val filePath: String = "",
    val fileSizeBytes: Long = 0,
    val lastModified: Long = 0
)

/**
 * Reads a library file's tags, format and cover.
 *
 * Desktop: Android asked `MediaMetadataRetriever` (tags, length, format,
 * embedded picture) and `MediaExtractor` (the codec inside an MP4/MKV/TS
 * container), and reached for JAudioTagger only where the platform parser fell
 * short (WAV's LIST/INFO and ID3 chunks, FLAC's VERSION comment). Here
 * JAudioTagger is the reader for everything it supports — MP3, FLAC, MP4/M4A,
 * Ogg Vorbis, WAV, AIFF, WMA, DSF/DFF — and libavformat ([FfmpegProbe])
 * answers for the codec inside a multi-codec container and for every format
 * JAudioTagger has no reader for (Opus, WavPack, APE, Musepack, Matroska, raw
 * AC-3/E-AC-3/DTS…), which on Android the platform extractor covered. What
 * comes out is the same [AudioTags], filled by the same rules.
 *
 * The Android constructor's `@ApplicationContext Context` was never read and
 * is gone.
 */
@Singleton
class TagReader @Inject constructor(
    private val artworkStore: ArtworkStore,
) {

    init {
        JaudiotaggerLog.quiet()
    }

    suspend fun readTags(
        filePath: String,
        folderArtCache: MutableMap<String, String?>? = null
    ): AudioTags {
        val file = File(filePath)
        if (!file.exists()) return AudioTags(filePath = filePath)

        return try {
            extractTags(file, filePath, folderArtCache)
        } catch (e: Exception) {
            fallbackTags(file, filePath)
        } catch (e: OutOfMemoryError) {
            // A malformed header can make a parser over-allocate; the file then
            // lands under its name, as an unreadable file did on Android.
            fallbackTags(file, filePath)
        }
    }

    private fun fallbackTags(file: File, filePath: String) = AudioTags(
        filePath = filePath,
        fileSizeBytes = file.length(),
        lastModified = file.lastModified(),
        codec = detectCodecFromExtension(filePath)
    )

    private fun extractTags(
        file: File,
        filePath: String,
        folderArtCache: MutableMap<String, String?>? = null
    ): AudioTags {
        val ext = filePath.substringAfterLast('.', "").lowercase()

        // JAudioTagger first: it reads only the header and the tag blocks.
        val audioFile = readAudioFile(file, ext)
        val tag = audioFile?.tag
        val header = runCatching { audioFile?.audioHeader }.getOrNull()

        // libavformat when the container can hold more than one codec (an
        // E-AC-3 Atmos track in an .mp4 is not AAC — Android's audioTrackMime),
        // or when JAudioTagger could not read the file, or its format, at all.
        val headerIncomplete = header == null ||
            (runCatching { header.sampleRateAsNumber }.getOrNull() ?: 0) <= 0 ||
            (runCatching { header.trackLength }.getOrNull() ?: 0) <= 0
        val probe = if (ext in AMBIGUOUS_CONTAINERS || audioFile == null || headerIncomplete) {
            FfmpegProbe.probe(
                filePath,
                readStreamInfo = ext in NEEDS_STREAM_INFO || audioFile == null,
                wantPicture = audioFile == null,
            )
        } else {
            null
        }

        fun fromTag(key: FieldKey): String? =
            tag?.let { t -> runCatching { t.getFirst(key) }.getOrNull() }
                ?.trim()?.takeIf { it.isNotEmpty() }
        fun fromProbe(vararg keys: String): String? =
            probe?.tags?.let { tags -> keys.firstNotNullOfOrNull { k -> tags[k]?.trim()?.takeIf { it.isNotEmpty() } } }

        val title = fromTag(FieldKey.TITLE) ?: fromProbe("title")
        val artist = fromTag(FieldKey.ARTIST) ?: fromProbe("artist")
        val albumArtist = fromTag(FieldKey.ALBUM_ARTIST) ?: fromProbe("album_artist", "albumartist", "album artist")
        val album = fromTag(FieldKey.ALBUM) ?: fromProbe("album")
        val genre = normalizeGenre(fromTag(FieldKey.GENRE) ?: fromProbe("genre"))
        val composer = fromTag(FieldKey.COMPOSER) ?: fromProbe("composer")
        val yearStr = fromTag(FieldKey.YEAR) ?: fromProbe("date", "year", "originaldate")

        // Parse track number (handles "3/12" format)
        val trackInfo = fromTag(FieldKey.TRACK) ?: fromProbe("track", "tracknumber")
        val (trackNumber, trackTotalInline) = parseTrackNumber(trackInfo)
        val trackTotal = trackTotalInline
            ?: positive(fromTag(FieldKey.TRACK_TOTAL) ?: fromProbe("tracktotal", "totaltracks"))
        val discInfo = fromTag(FieldKey.DISC_NO) ?: fromProbe("disc", "discnumber")
        val (discNumber, discTotalInline) = parseTrackNumber(discInfo)
        val discTotal = discTotalInline
            ?: positive(fromTag(FieldKey.DISC_TOTAL) ?: fromProbe("disctotal", "totaldiscs"))

        // Determine codec. The container MIME stands in for what the retriever
        // reported; the probed elementary-stream MIME outranks it.
        val containerMime = containerMimeFor(ext)
        val codec = detectCodec(probe?.audioMime ?: containerMime, filePath)

        // Format: the header's own fields (for WAV that is the fmt chunk, which
        // is what Android's readWavTags read), else what the demuxer saw.
        val sampleRate = runCatching { header?.sampleRateAsNumber }.getOrNull()?.takeIf { it > 0 }
            ?: probe?.sampleRate?.takeIf { it > 0 }
        val channels = parseChannels(runCatching { header?.channels }.getOrNull())
            ?: probe?.channels?.takeIf { it > 0 }
        val durationMs = runCatching { header?.preciseTrackLength }.getOrNull()
            ?.takeIf { it > 0.0 }?.let { (it * 1000).toLong() }
            ?: probe?.durationMs?.takeIf { it > 0 }
            ?: 0L
        val bitRateKbps = runCatching { header?.bitRateAsNumber }.getOrNull()?.takeIf { it > 0 }
            ?: probe?.bitRate?.takeIf { it > 0 }?.let { it / 1000 }
            ?: 0L
        // Lossy streams have no bit depth; MediaMetadataRetriever reported
        // none for them, and the quality labels read null as "not applicable".
        val bitsPerSample = if (codec in LOSSY_CODECS) null else {
            runCatching { header?.bitsPerSample }.getOrNull()?.takeIf { it > 0 }
                ?: probe?.bitsPerSample?.takeIf { it > 0 }
        }

        // THX Spatial Audio detection. Cheap first: the phrase in title/album
        // (covers sideloaded releases named that way). For FLAC, fall back to
        // the embedded VERSION/COMMENT Vorbis fields — the ones a Tryptify
        // download writes — so a re-scan re-derives the flag even when the
        // folded phrase never reached title/album.
        val thxFromText = tf.monochrome.desktop.domain.model.isThxSpatialAudio(
            title = title, albumTitle = album,
        )
        val isThx = thxFromText || (codec == AudioCodec.FLAC && detectThxInFlacTags(tag))

        // Dolby Atmos: best-effort from the MIME (eac3-joc / atmos) or a "Dolby
        // Atmos" phrase in the metadata. A bare .ec3/.eac3 is only Atmos-capable,
        // not necessarily Atmos, so the extension alone does not set the flag;
        // authoritative JOC detection arrives with the native demux.
        //
        // The container MIME ("audio/mp4") never carries the joc marker, so
        // the elementary-stream MIME is passed when there is one — that is
        // where "audio/eac3-joc" actually shows up.
        val isAtmos = tf.monochrome.desktop.domain.model.isDolbyAtmos(
            mimeType = probe?.audioMime ?: containerMime,
            title = title,
            albumTitle = album,
        )

        // Extract and cache artwork. For MP4/M4A this reads the `covr` atom;
        // for FLAC/Vorbis this reads the METADATA_BLOCK_PICTURE/coverart; for
        // ID3v2 (MP3, WAV, AIFF, DSF) this reads APIC; for the formats only
        // FFmpeg opens, the attached-picture stream. If nothing is embedded,
        // fall back to a sidecar image in the track's folder (cover.jpg,
        // folder.jpg, etc.).
        val artworkBytes = embeddedPicture(tag) ?: probe?.picture
        val hasArt = artworkBytes != null
        val artworkCacheKey = when {
            hasArt && artworkBytes != null -> artworkStore.put(artworkBytes, file.absolutePath)
            // Per-track sidecar: an image next to the audio file with the
            // same stem (e.g. "song.flac" + "song.jpg"). yt-dlp, Bandcamp,
            // and rip workflows all produce this convention. Checked before
            // folder-level cover.* / albumart.* fallback.
            else -> findPerTrackSidecar(filePath)
                ?: findFolderCoverArt(filePath, folderArtCache)
        }
        val hasArtOrSidecar = artworkCacheKey != null

        // Many sideloaded files (yt-dlp, loose rips) carry no ARTIST tag and
        // stuff the whole "Artist - Title" string into the title. When the
        // artist tag is genuinely absent, recover it from that convention so
        // the library doesn't show a wall of "Unknown Artist".
        val tagArtist = artist?.takeIf { it.isNotBlank() }
        var finalArtist = tagArtist
        var finalTitle = title?.takeIf { it.isNotBlank() }
        if (tagArtist == null) {
            val (derivedArtist, derivedTitle) = deriveArtistFromTitle(title, filePath)
            if (derivedArtist != null) {
                finalArtist = derivedArtist
                finalTitle = derivedTitle
            }
        }

        return AudioTags(
            title = finalTitle,
            artist = finalArtist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            year = parseYear(yearStr),
            trackNumber = trackNumber,
            trackTotal = trackTotal,
            discNumber = discNumber,
            discTotal = discTotal,
            composer = composer,
            codec = codec,
            sampleRate = sampleRate ?: 44100,
            bitDepth = bitsPerSample,
            bitRate = bitRateKbps.toInt(),
            channels = channels ?: 2,
            durationSeconds = (durationMs / 1000).toInt(),
            hasEmbeddedArt = hasArtOrSidecar,
            artworkCacheKey = artworkCacheKey,
            isThxSpatialAudio = isThx,
            isDolbyAtmos = isAtmos,
            filePath = filePath,
            fileSizeBytes = file.length(),
            lastModified = file.lastModified()
        )
    }

    /**
     * JAudioTagger's view of the file, or null when it has no reader for the
     * format or the file does not parse. Only the header and the tag blocks are
     * read — the audio is skipped. JAudioTagger picks its reader by extension,
     * so spellings it does not know, for containers it does read, are mapped
     * onto one it does.
     */
    private fun readAudioFile(file: File, ext: String): AudioFile? =
        readWithJaudiotagger(file, ext)

    /**
     * The embedded cover: the front cover when the tag names one, else the
     * first picture. Linked (URL) artwork carries no bytes and is skipped.
     */
    private fun embeddedPicture(tag: Tag?): ByteArray? = embeddedPictureOf(tag)

    /**
     * Reports whether a FLAC's embedded VERSION / COMMENT / TITLE / ALBUM
     * Vorbis comments name a THX Spatial Audio release. Fully guarded — any
     * failure returns false so scanning never breaks on a malformed file.
     * Android re-opened the file with JAudioTagger for this; here the tag the
     * scan has already read is reused.
     */
    private fun detectThxInFlacTags(tag: Tag?): Boolean = try {
        if (tag == null) {
            false
        } else {
            val candidates = listOfNotNull(
                runCatching { tag.getFirst(FieldKey.TITLE) }.getOrNull(),
                runCatching { tag.getFirst(FieldKey.ALBUM) }.getOrNull(),
                runCatching { tag.getFirst(FieldKey.COMMENT) }.getOrNull(),
                (tag as? org.jaudiotagger.tag.flac.FlacTag)?.let { runCatching { it.getFirst("VERSION") }.getOrNull() },
            )
            candidates.any {
                tf.monochrome.desktop.domain.model.isThxSpatialAudio(version = it)
            }
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Recover "Artist" / "Title" from a title formatted as `Artist - Title`,
     * used only when the file has no ARTIST tag at all. Falls back to the file
     * name (sans extension) when the title tag is also missing.
     *
     * See [splitArtistTitle] for which separators count. Returns
     * `(null, originalTitle)` when nothing can be confidently derived.
     */
    private fun deriveArtistFromTitle(rawTitle: String?, filePath: String): Pair<String?, String?> {
        val base = rawTitle?.takeIf { it.isNotBlank() }
            ?: File(filePath).nameWithoutExtension.takeIf { it.isNotBlank() }
            ?: return null to rawTitle
        return splitArtistTitle(base) ?: (null to rawTitle)
    }

    private fun parseTrackNumber(raw: String?): Pair<Int?, Int?> {
        if (raw == null) return null to null
        return if (raw.contains('/')) {
            val parts = raw.split('/')
            positive(parts[0]) to positive(parts.getOrNull(1))
        } else {
            positive(raw) to null
        }
    }

    /**
     * A tag number, or null. Tag writers fill an absent number with 0 (an MP4
     * `trkn` atom always carries both halves); the retriever reported those as
     * absent, and so does this.
     */
    private fun positive(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun parseYear(raw: String?): Int? {
        if (raw == null) return null
        // Handle full date strings like "2023-01-15"
        return raw.take(4).toIntOrNull()
    }

    /**
     * JAudioTagger reports channels as text: "2", or for MP3 the channel mode
     * ("Joint Stereo", "Mono"). Android read `METADATA_KEY_NUM_TRACKS` here —
     * the number of tracks, 1 for almost every file — so its count was right
     * only for WAV, which read the fmt chunk; this is the real count for all.
     */
    private fun parseChannels(raw: String?): Int? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        s.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        return when {
            s.contains("mono", ignoreCase = true) -> 1
            s.contains("stereo", ignoreCase = true) || s.contains("dual", ignoreCase = true) -> 2
            else -> null
        }
    }

    /**
     * ID3 genres can be stored as a numeric reference ("17", "(17)", or
     * "(17)Rock"); MediaMetadataRetriever resolved those to the name, and so
     * does this, through JAudioTagger's ID3 genre table.
     */
    private fun normalizeGenre(raw: String?): String? {
        val g = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val paren = ID3_GENRE_PAREN.matchEntire(g)
        if (paren != null) {
            val rest = paren.groupValues[2].trim()
            if (rest.isNotEmpty()) return rest
        }
        val numeric = paren?.groupValues?.get(1)
            ?: ID3_GENRE_BARE.matchEntire(g)?.groupValues?.get(1)
            ?: return g
        val id = numeric.toIntOrNull() ?: return g
        return runCatching { GenreTypes.getInstanceOf().getValueForId(id) }.getOrNull() ?: g
    }

    /**
     * The MIME `MediaMetadataRetriever` reported for the container, for the
     * formats it identified — what [detectCodec] was written against. Formats
     * with no [AudioCodec] of their own map to null and resolve by extension.
     */
    private fun containerMimeFor(ext: String): String? = when (ext) {
        "flac" -> "audio/flac"
        "mp3", "mp2", "mp1" -> "audio/mpeg"
        "m4a", "m4b", "m4p", "mp4", "alac" -> "audio/mp4"
        "m4v", "mov" -> "video/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav", "wave" -> "audio/x-wav"
        "aif", "aiff", "aifc" -> "audio/x-aiff"
        "wma" -> "audio/x-ms-wma"
        "ac3" -> "audio/ac3"
        "ec3", "eac3" -> "audio/eac3"
        else -> null
    }

    private fun detectCodec(mimeType: String?, filePath: String): AudioCodec {
        // Opus and Vorbis share the Ogg container, so the container MIME is
        // "audio/ogg" for both. ALAC and AAC share the MP4 container, so both
        // report as "audio/mp4". Check explicit codec MIMEs first, then fall
        // back to the file extension.
        //
        // MP4/MKV/TS are containers: the container MIME ("audio/mp4",
        // "video/mp4") never names the codec inside it, so an E-AC-3 Atmos
        // track was indistinguishable from AAC and got labelled "AAC 768".
        // The caller passes the real elementary-stream MIME for those.
        val mime = mimeType
        return when {
            mime?.contains("eac3") == true || mime?.contains("e-ac-3") == true ->
                AudioCodec.EAC3
            mime?.contains("ac3") == true || mime?.contains("ac-3") == true ->
                AudioCodec.AC3
            mime?.contains("flac") == true -> AudioCodec.FLAC
            mime?.contains("mpeg") == true -> AudioCodec.MP3
            mime?.contains("alac") == true -> AudioCodec.ALAC
            mime?.contains("mp4a") == true || mime?.contains("aac") == true ||
                mime?.contains("mp4") == true -> {
                val fromExt = detectCodecFromExtension(filePath)
                if (fromExt == AudioCodec.ALAC) AudioCodec.ALAC else AudioCodec.AAC
            }
            mime?.contains("opus") == true -> AudioCodec.OPUS
            mime?.contains("vorbis") == true -> AudioCodec.OGG_VORBIS
            mime?.contains("ogg") == true -> {
                val fromExt = detectCodecFromExtension(filePath)
                if (fromExt == AudioCodec.OPUS) AudioCodec.OPUS else AudioCodec.OGG_VORBIS
            }
            mime?.contains("wav") == true || mime?.contains("wave") == true -> AudioCodec.WAV
            mime?.contains("aiff") == true -> AudioCodec.AIFF
            mime?.contains("x-ms-wma") == true -> AudioCodec.WMA
            else -> detectCodecFromExtension(filePath)
        }
    }

    private fun detectCodecFromExtension(filePath: String): AudioCodec {
        return when (filePath.substringAfterLast('.').lowercase()) {
            "flac" -> AudioCodec.FLAC
            "mp3" -> AudioCodec.MP3
            "alac" -> AudioCodec.ALAC
            "m4a", "aac", "mp4" -> AudioCodec.AAC
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "wav" -> AudioCodec.WAV
            "aif", "aiff" -> AudioCodec.AIFF
            "ape" -> AudioCodec.APE
            "wma" -> AudioCodec.WMA
            else -> AudioCodec.UNKNOWN
        }
    }

    /**
     * Look for a sidecar cover art file in the track's folder. Matches the common
     * naming conventions (cover.*, folder.*, album.*, front.*, AlbumArt*.jpg) case-
     * insensitively, prefers larger images when multiple candidates exist, and
     * returns the file's absolute path (Coil will load it directly). Returns null
     * when the folder has no recognisable image.
     *
     * Pass a [cache] keyed by parent directory to avoid re-listing the same folder
     * for every track in an album. The scanner shares one (synchronized) cache
     * across parallel tag-read workers; the containsKey/put sequence here is not
     * atomic across calls, but a lost race just re-lists the same folder once
     * with an identical result.
     */
    private fun findFolderCoverArt(
        filePath: String,
        cache: MutableMap<String, String?>? = null
    ): String? {
        val parent = File(filePath).parentFile ?: return null
        if (!parent.isDirectory) return null
        val parentKey = parent.absolutePath
        cache?.let { if (it.containsKey(parentKey)) return it[parentKey] }
        val resolved = scanFolderCoverArt(parent)
        cache?.put(parentKey, resolved)
        return resolved
    }

    /**
     * Match an image file in the same directory whose stem equals the audio
     * file's stem. Common naming convention from yt-dlp, Bandcamp, and
     * various TIDAL/Qobuz rip workflows.
     *
     * Direct File.exists() probes rather than listFiles(): under Android's
     * scoped storage listFiles() could come back null where exists() worked,
     * and on a desktop file system the probes cost the same. Up to 8 stat()
     * calls per art-less track (4 extensions × 2 cases).
     */
    private fun findPerTrackSidecar(filePath: String): String? {
        val file = File(filePath)
        val parent = file.parentFile ?: return null
        val stem = file.nameWithoutExtension
        for (ext in IMAGE_EXTENSIONS) {
            val lower = File(parent, "$stem.$ext")
            if (lower.exists()) return lower.absolutePath
            val upper = File(parent, "$stem.${ext.uppercase()}")
            if (upper.exists()) return upper.absolutePath
        }
        return null
    }

    private fun scanFolderCoverArt(parent: File): String? {
        val files = parent.listFiles() ?: return null
        val candidates = files.filter { f ->
            if (!f.isFile) return@filter false
            val name = f.name.lowercase()
            val ext = name.substringAfterLast('.', "")
            if (ext !in IMAGE_EXTENSIONS) return@filter false
            val stem = name.substringBeforeLast('.')
            stem in COVER_STEMS || COVER_STEM_PREFIXES.any { stem.startsWith(it) }
        }
        if (candidates.isEmpty()) return null
        // Prefer exact stem match over prefix match, then the largest file
        // (Windows Media Player writes both AlbumArt.jpg and AlbumArtSmall.jpg).
        return candidates
            .sortedWith(
                compareByDescending<File> { f ->
                    val stem = f.name.lowercase().substringBeforeLast('.')
                    if (stem in COVER_STEMS) 1 else 0
                }.thenByDescending { it.length() }
            )
            .first()
            .absolutePath
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val COVER_STEMS = setOf(
            "cover", "folder", "album", "albumart", "front", "artwork"
        )
        private val COVER_STEM_PREFIXES = setOf(
            "albumart" // AlbumArt_{GUID}_Large.jpg (WMP)
        )

        /**
         * Containers that can hold more than one audio codec, so the
         * extension says nothing about what is inside; probed with FFmpeg.
         * A .flac or .mp3 needs no disambiguation and does not pay for it.
         * (.ec3/.eac3 are single-codec, but the probe can see the Atmos
         * profile the extension cannot.)
         */
        private val AMBIGUOUS_CONTAINERS = setOf(
            "mp4", "m4a", "m4v", "mov", "mkv", "mka", "webm", "ts", "m2ts", "mts", "ec3", "eac3",
        )

        /** Transport streams name no codecs in a header; FFmpeg has to look at packets. */
        private val NEEDS_STREAM_INFO = setOf("ts", "m2ts", "mts")

        private val LOSSY_CODECS = setOf(
            AudioCodec.MP3, AudioCodec.AAC, AudioCodec.OGG_VORBIS, AudioCodec.OPUS,
            AudioCodec.AC3, AudioCodec.EAC3,
        )

        private val ID3_GENRE_PAREN = Regex("""^\((\d{1,3})\)(.*)$""")
        private val ID3_GENRE_BARE = Regex("""^(\d{1,3})$""")
    }
}

/**
 * Desktop: JAudioTagger, read the way [TagReader] and the cover fetcher both
 * need it. Null when it has no reader for the format or the file does not
 * parse; only the header and the tag blocks are read.
 */
internal fun readWithJaudiotagger(file: File, ext: String = file.extension.lowercase()): AudioFile? {
    JaudiotaggerLog.quiet()
    val readerExt = when (ext) {
        in JAUDIOTAGGER_EXTENSIONS -> null
        "wave" -> "wav"
        "m4v", "alac" -> "mp4"
        else -> return null
    }
    return try {
        if (readerExt == null) AudioFileIO.read(file) else AudioFileIO.readAs(file, readerExt)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** The front cover when the tag names one, else the first picture with bytes. */
internal fun embeddedPictureOf(tag: Tag?): ByteArray? {
    if (tag == null) return null
    val pictures = runCatching { tag.artworkList }.getOrNull().orEmpty()
        .filter { art -> runCatching { !art.isLinked && art.binaryData?.isNotEmpty() == true }.getOrDefault(false) }
    val front = pictures.firstOrNull { art -> runCatching { art.pictureType }.getOrNull() == PictureTypes.DEFAULT_ID }
    return (front ?: pictures.firstOrNull())?.binaryData
}

/** Extensions JAudioTagger 3.0.1 has a reader for (its SupportedFileFormat). */
private val JAUDIOTAGGER_EXTENSIONS = setOf(
    "ogg", "oga", "mp3", "flac", "mp4", "m4a", "m4p", "m4b", "wma", "wav",
    "ra", "rm", "aif", "aiff", "aifc", "dsf", "dff",
)

/**
 * JAudioTagger logs every malformed frame it meets at WARNING/SEVERE through
 * java.util.logging, which over a large library is thousands of lines on
 * stderr. Held strongly: j.u.l. keeps loggers weakly, and a collected logger
 * comes back at the default level.
 */
internal object JaudiotaggerLog {
    private val logger: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    /** Touching the object is what silences the logger; this is the touch. */
    fun quiet() {
        logger.level = Level.OFF
    }
}

/**
 * "Artist - Title" or "Artist ~ Title" → (artist, title), or null.
 *
 * Only a *spaced* hyphen-minus or tilde counts. A spaced tilde is how a lot of
 * DJ and scene rips name files ("Banana Inc ~ Black Magic") and practically
 * never sits inside a title. En/em dashes (`–`, `—`) are routinely used inside
 * one ("Heroine — Pat B Remix"), so splitting on them would mangle good titles.
 */
internal fun splitArtistTitle(base: String): Pair<String, String>? {
    val sep = listOf(" - ", " ~ ")
        .mapNotNull { s -> base.indexOf(s).takeIf { it > 0 }?.let { it to s } }
        .minByOrNull { it.first } ?: return null
    val artist = base.substring(0, sep.first).trim()
    val title = base.substring(sep.first + sep.second.length).trim()
    return if (artist.isNotEmpty() && title.isNotEmpty()) artist to title else null
}
