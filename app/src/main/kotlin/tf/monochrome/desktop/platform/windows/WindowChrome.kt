package tf.monochrome.desktop.platform.windows

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference

/**
 * The title bar Windows draws around the app's window, matched to the theme.
 *
 * Windows paints a light caption unless asked otherwise, which reads as a
 * white slab above the dark player. DWM takes two hints: dark mode (Windows
 * 10 20H1 and later) and, on Windows 11, the caption's exact colour, so the
 * title bar continues the app's background instead of framing it. Both are
 * hints: older builds ignore them and keep the default caption, which is
 * the right fallback.
 */
object WindowChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Suppress("FunctionName")
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: DwmApi? by lazy {
        if (!windows) null else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    /**
     * [hwnd] is ComposeWindow.windowHandle; [captionArgb] and [textArgb] are
     * 0xAARRGGBB colours (alpha ignored), or null to leave Windows' own.
     */
    fun apply(hwnd: Long, dark: Boolean, captionArgb: Int? = null, textArgb: Int? = null) {
        val api = dwm ?: return
        if (hwnd == 0L) return
        val handle = WinDef.HWND(Pointer(hwnd))
        val flag = IntByReference(if (dark) 1 else 0)
        // Builds before 20H1 used attribute 19 for the same switch.
        if (api.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE, flag, 4) != 0) {
            api.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, flag, 4)
        }
        captionArgb?.let { api.DwmSetWindowAttribute(handle, DWMWA_CAPTION_COLOR, IntByReference(colorRef(it)), 4) }
        textArgb?.let { api.DwmSetWindowAttribute(handle, DWMWA_TEXT_COLOR, IntByReference(colorRef(it)), 4) }
    }

    /** DWM takes a COLORREF: 0x00BBGGRR. */
    internal fun colorRef(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
