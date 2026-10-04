// Desktop stand-ins for Media3's MediaItem and MediaMetadata: the value types
// StreamResolver builds and the player UI reads. Same builder API shape as
// Media3 for the members the app uses; the desktop PlaybackEngine consumes
// them directly, so resolution code is unchanged from Android.
package androidx.media3.common

import android.net.Uri
import android.os.Bundle

class MediaItem private constructor(
    @JvmField val mediaId: String,
    @JvmField val mediaMetadata: MediaMetadata,
    @JvmField val localConfiguration: LocalConfiguration?,
    @JvmField val requestMetadata: RequestMetadata,
) {
    class LocalConfiguration(
        @JvmField val uri: Uri,
        @JvmField val mimeType: String?,
        @JvmField val customCacheKey: String?,
        @JvmField val tag: Any?,
    )

    class RequestMetadata(@JvmField val mediaUri: Uri?, @JvmField val extras: Bundle?) {
        companion object { @JvmField val EMPTY = RequestMetadata(null, null) }
    }

    fun buildUpon(): Builder = Builder().also {
        it.mediaId = mediaId; it.mediaMetadata = mediaMetadata
        it.uri = localConfiguration?.uri; it.mimeType = localConfiguration?.mimeType
        it.customCacheKey = localConfiguration?.customCacheKey; it.tag = localConfiguration?.tag
        it.requestMetadata = requestMetadata
    }

    override fun equals(other: Any?): Boolean =
        other is MediaItem && mediaId == other.mediaId && localConfiguration?.uri == other.localConfiguration?.uri && mediaMetadata == other.mediaMetadata
    override fun hashCode(): Int = mediaId.hashCode() * 31 + (localConfiguration?.uri?.hashCode() ?: 0)
    override fun toString(): String = "MediaItem(id=$mediaId, uri=${localConfiguration?.uri})"

    class Builder {
        internal var mediaId: String = DEFAULT_MEDIA_ID
        internal var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY
        internal var uri: Uri? = null
        internal var mimeType: String? = null
        internal var customCacheKey: String? = null
        internal var tag: Any? = null
        internal var requestMetadata: RequestMetadata = RequestMetadata.EMPTY

        fun setMediaId(mediaId: String): Builder = apply { this.mediaId = mediaId }
        fun setMediaMetadata(metadata: MediaMetadata): Builder = apply { this.mediaMetadata = metadata }
        fun setUri(uri: Uri?): Builder = apply { this.uri = uri }
        fun setUri(uri: String?): Builder = apply { this.uri = uri?.let { Uri.parse(it) } }
        fun setMimeType(mimeType: String?): Builder = apply { this.mimeType = mimeType }
        fun setCustomCacheKey(key: String?): Builder = apply { this.customCacheKey = key }
        fun setTag(tag: Any?): Builder = apply { this.tag = tag }
        fun setRequestMetadata(requestMetadata: RequestMetadata): Builder = apply { this.requestMetadata = requestMetadata }

        fun build(): MediaItem {
            val local = uri?.let { LocalConfiguration(it, mimeType, customCacheKey, tag) }
            return MediaItem(mediaId, mediaMetadata, local, requestMetadata)
        }
    }

    companion object {
        const val DEFAULT_MEDIA_ID = ""
        @JvmField val EMPTY: MediaItem = Builder().build()
        @JvmStatic fun fromUri(uri: Uri): MediaItem = Builder().setUri(uri).build()
        @JvmStatic fun fromUri(uri: String): MediaItem = Builder().setUri(uri).build()
    }
}

class MediaMetadata private constructor(
    @JvmField val title: CharSequence?,
    @JvmField val artist: CharSequence?,
    @JvmField val albumTitle: CharSequence?,
    @JvmField val albumArtist: CharSequence?,
    @JvmField val artworkUri: Uri?,
    @JvmField val artworkData: ByteArray?,
    @JvmField val mediaType: Int?,
    @JvmField val durationMs: Long?,
    @JvmField val trackNumber: Int?,
    @JvmField val totalTrackCount: Int?,
    @JvmField val genre: CharSequence?,
    @JvmField val station: CharSequence?,
    @JvmField val discNumber: Int?,
    @JvmField val extras: Bundle?,
) {
    fun buildUpon(): Builder = Builder().also {
        it.title = title; it.artist = artist; it.albumTitle = albumTitle; it.albumArtist = albumArtist
        it.artworkUri = artworkUri; it.artworkData = artworkData; it.mediaType = mediaType; it.durationMs = durationMs
        it.trackNumber = trackNumber; it.totalTrackCount = totalTrackCount; it.genre = genre; it.extras = extras
        it.station = station; it.discNumber = discNumber
    }

    override fun equals(other: Any?): Boolean = other is MediaMetadata &&
        title == other.title && artist == other.artist && albumTitle == other.albumTitle && artworkUri == other.artworkUri && mediaType == other.mediaType
    override fun hashCode(): Int = listOf(title, artist, albumTitle, artworkUri).hashCode()

    class Builder {
        internal var title: CharSequence? = null
        internal var artist: CharSequence? = null
        internal var albumTitle: CharSequence? = null
        internal var albumArtist: CharSequence? = null
        internal var artworkUri: Uri? = null
        internal var artworkData: ByteArray? = null
        internal var mediaType: Int? = null
        internal var durationMs: Long? = null
        internal var trackNumber: Int? = null
        internal var totalTrackCount: Int? = null
        internal var genre: CharSequence? = null
        internal var station: CharSequence? = null
        internal var discNumber: Int? = null
        internal var extras: Bundle? = null

        fun setTitle(v: CharSequence?): Builder = apply { title = v }
        fun setArtist(v: CharSequence?): Builder = apply { artist = v }
        fun setAlbumTitle(v: CharSequence?): Builder = apply { albumTitle = v }
        fun setAlbumArtist(v: CharSequence?): Builder = apply { albumArtist = v }
        fun setArtworkUri(v: Uri?): Builder = apply { artworkUri = v }
        fun setArtworkData(data: ByteArray?, pictureType: Int?): Builder = apply { artworkData = data }
        fun setMediaType(v: Int?): Builder = apply { mediaType = v }
        fun setDurationMs(v: Long?): Builder = apply { durationMs = v }
        fun setTrackNumber(v: Int?): Builder = apply { trackNumber = v }
        fun setTotalTrackCount(v: Int?): Builder = apply { totalTrackCount = v }
        fun setGenre(v: CharSequence?): Builder = apply { genre = v }
        fun setStation(v: CharSequence?): Builder = apply { station = v }
        fun setDiscNumber(v: Int?): Builder = apply { discNumber = v }
        fun setRecordingYear(v: Int?): Builder = this
        fun setReleaseYear(v: Int?): Builder = this
        fun setExtras(v: Bundle?): Builder = apply { extras = v }
        fun setIsBrowsable(v: Boolean?): Builder = this
        fun setIsPlayable(v: Boolean?): Builder = this
        fun build(): MediaMetadata = MediaMetadata(title, artist, albumTitle, albumArtist, artworkUri, artworkData, mediaType, durationMs, trackNumber, totalTrackCount, genre, station, discNumber, extras)
    }

    companion object {
        @JvmField val EMPTY: MediaMetadata = Builder().build()
        const val MEDIA_TYPE_MIXED = 0
        const val MEDIA_TYPE_MUSIC = 1
        const val MEDIA_TYPE_RADIO_STATION = 8
        const val MEDIA_TYPE_PLAYLIST = 21
        const val MEDIA_TYPE_ALBUM = 23
        const val PICTURE_TYPE_FRONT_COVER = 3
    }
}
