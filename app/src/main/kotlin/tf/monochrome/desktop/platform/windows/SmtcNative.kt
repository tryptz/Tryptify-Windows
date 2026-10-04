package tf.monochrome.desktop.platform.windows

import tf.monochrome.desktop.platform.NativeLibraries

/** JNI surface of native/smtc/smtc_jni.cpp (Windows System Media Transport Controls). */
internal object SmtcNative {
    /** False off Windows and in MinGW builds, which cannot compile C++/WinRT. */
    val isAvailable: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
            try {
                NativeLibraries.load("monochrome_smtc")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
    }

    /** Called from the WinRT thread pool. */
    interface Callback {
        /** A SystemMediaTransportControlsButton value: 0 play, 1 pause, 2 stop, 6 next, 7 previous. */
        fun onButton(button: Int)
        fun onSeek(positionMs: Long)
    }

    const val BUTTON_PLAY = 0
    const val BUTTON_PAUSE = 1
    const val BUTTON_STOP = 2
    const val BUTTON_NEXT = 6
    const val BUTTON_PREVIOUS = 7

    const val STATUS_CLOSED = 0
    const val STATUS_CHANGING = 1
    const val STATUS_STOPPED = 2
    const val STATUS_PLAYING = 3
    const val STATUS_PAUSED = 4

    @JvmStatic external fun nativeAttach(hwnd: Long, callback: Callback): Long
    @JvmStatic external fun nativeSetMetadata(handle: Long, title: String?, artist: String?, album: String?, albumArtist: String?, trackNumber: Int, thumbnail: String?)
    @JvmStatic external fun nativeSetPlaybackStatus(handle: Long, status: Int)
    @JvmStatic external fun nativeSetTimeline(handle: Long, positionMs: Long, durationMs: Long, seekable: Boolean)
    @JvmStatic external fun nativeSetNavigation(handle: Long, canPrevious: Boolean, canNext: Boolean)
    @JvmStatic external fun nativeDetach(handle: Long)
}
