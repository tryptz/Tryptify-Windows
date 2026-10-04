// Desktop stand-in for the Media3 Player API surface the UI reads and drives.
// The desktop PlaybackEngine implements it, so PlayerViewModel keeps its
// Player.Listener and its transport calls; only the MediaController plumbing
// that fetched the player from an Android service goes away.
package androidx.media3.common

interface Player {
    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onRepeatModeChanged(repeatMode: Int) {}
        fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {}
        fun onVolumeChanged(volume: Float) {}
        fun onIsLoadingChanged(isLoading: Boolean) {}
    }

    class PositionInfo(@JvmField val mediaItem: MediaItem?, @JvmField val mediaItemIndex: Int, @JvmField val positionMs: Long)

    val playbackState: Int
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val isLoading: Boolean
    val currentMediaItem: MediaItem?
    val mediaMetadata: MediaMetadata
    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val duration: Long
    val currentPosition: Long
    val bufferedPosition: Long
    val contentPosition: Long get() = currentPosition
    val contentDuration: Long get() = duration
    var volume: Float
    var playbackParameters: PlaybackParameters
    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    val playerError: PlaybackException?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun setMediaItem(mediaItem: MediaItem)
    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long)
    fun setMediaItems(mediaItems: List<MediaItem>)
    fun addMediaItem(mediaItem: MediaItem)
    fun removeMediaItem(index: Int)
    fun getMediaItemAt(index: Int): MediaItem
    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long)
    fun seekToNext()
    fun seekToPrevious()
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun setPlaybackSpeed(speed: Float) { playbackParameters = playbackParameters.withSpeed(speed) }
    fun clearMediaItems()
    fun isCommandAvailable(command: Int): Boolean = true

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2

        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3

        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5

        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1
        const val DISCONTINUITY_REASON_INTERNAL = 5

        const val COMMAND_PLAY_PAUSE = 1
        const val COMMAND_SEEK_TO_NEXT = 9
        const val COMMAND_SEEK_TO_PREVIOUS = 7
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM = 5
        const val COMMAND_SET_MEDIA_ITEM = 31
    }
}

/** The error a Player.Listener receives; carries the Media3 error-code shape the UI switches on. */
open class PlaybackException(message: String?, cause: Throwable?, @JvmField val errorCode: Int) : Exception(message, cause) {
    val errorCodeName: String get() = "ERROR_CODE_$errorCode"

    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_REMOTE_ERROR = 1001
        const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
        const val ERROR_CODE_TIMEOUT = 1003
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
        const val ERROR_CODE_IO_NO_PERMISSION = 2006
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
        const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
        const val ERROR_CODE_DECODER_INIT_FAILED = 4001
        const val ERROR_CODE_DECODING_FAILED = 4003
        const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
        const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
        const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002
    }
}
