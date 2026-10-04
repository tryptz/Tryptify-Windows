package tf.monochrome.desktop.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.platform.AppPaths

/**
 * Windows' own text size (Settings › Accessibility › Text size): the desktop's
 * counterpart of the font scale Android reported in its Configuration.
 *
 * Compose for Desktop scales the window for the display's DPI but carries no
 * font scale of its own, so without this "Use system font size" would always
 * mean 1.0. Windows keeps the slider as a percentage, `TextScaleFactor`
 * (100..225) under the user's Accessibility key, and writes it only once the
 * slider has been moved; until then the text size is 100%.
 */
internal object SystemTextScale {
    private const val KEY = "Software\\Microsoft\\Accessibility"
    private const val VALUE = "TextScaleFactor"

    /** How often [rememberSystemFontScale] looks again while it is followed; one registry read. */
    const val POLL_MS = 3_000L

    /** The text size as a scale, 1.0 off Windows and wherever it cannot be read. */
    fun current(): Float {
        if (!AppPaths.isWindows) return 1f
        return runCatching {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, VALUE)) 1f
            else Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE) / 100f
        }.getOrDefault(1f).coerceIn(1f, 2.25f)
    }
}

/**
 * The system font scale "Use system font size" hands the typography to:
 * Compose's own (1.0 on the desktop unless something sets it) times Windows'
 * text size. Re-read every [SystemTextScale.POLL_MS] while [follow] is on, so
 * moving the slider takes effect without a restart, as a configuration change
 * did on Android.
 */
@Composable
internal fun rememberSystemFontScale(follow: Boolean): Float {
    val densityScale = LocalDensity.current.fontScale
    // Read up front only when it is wanted, so the first frame is already at
    // the right size; otherwise not at all until it is switched on.
    var windowsScale by remember { mutableFloatStateOf(if (follow) SystemTextScale.current() else 1f) }
    LaunchedEffect(follow) {
        while (follow) {
            windowsScale = withContext(Dispatchers.IO) { SystemTextScale.current() }
            delay(SystemTextScale.POLL_MS)
        }
    }
    return densityScale * windowsScale
}
