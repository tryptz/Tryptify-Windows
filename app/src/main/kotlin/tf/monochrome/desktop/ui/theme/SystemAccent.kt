package tf.monochrome.desktop.ui.theme

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import tf.monochrome.desktop.platform.AppPaths

/**
 * Desktop: the palette "System colors" follows on Windows, which stands in for
 * Android's Material You palette.
 *
 * Windows keeps one accent colour (Settings › Personalization › Colors), and
 * with "Automatic" chosen it derives that colour from the wallpaper. That is the
 * idea Material You is built on, reduced to the one colour Windows publishes.
 * DWM keeps it as `AccentColor` under the user's DWM key.
 *
 * Null off Windows and wherever the value cannot be read. The theme then falls
 * back to Monochrome, and [selectableThemes] stops offering the entry, because
 * an entry that silently resolves to another theme is worse than none.
 */
internal object SystemAccent {
    private const val DWM_KEY = "Software\\Microsoft\\Windows\\DWM"

    /** How often [rememberMaterialYouScheme] looks again; one registry read each time. */
    const val POLL_MS = 3_000L

    /** The accent as opaque ARGB, or null. DWM stores it as 0xAABBGGRR. */
    fun argb(): Int? {
        if (!AppPaths.isWindows) return null
        return runCatching {
            val abgr = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, DWM_KEY, "AccentColor")
            (0xFF shl 24) or ((abgr and 0xFF) shl 16) or (abgr and 0xFF00) or ((abgr shr 16) and 0xFF)
        }.getOrNull()
    }
}
