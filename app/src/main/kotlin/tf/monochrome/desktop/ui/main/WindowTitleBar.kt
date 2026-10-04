package tf.monochrome.desktop.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update

/**
 * Desktop: the colours the window's title bar continues, where Android screens
 * styled the system bars.
 *
 * The theme sets them (TryptifyApp, through main()). A screen that paints a
 * ground of its own right under the bar, as the player does with its black,
 * takes them over with [WindowTitleBarColors] for as long as it is composed,
 * the way the player set the status bar's icons over its gradient on Android;
 * they go back to the theme's when it leaves. main() applies whichever is in
 * force through WindowChrome, and only when it changes.
 */
class WindowTitleBar {
    data class Colors(val background: Color, val onBackground: Color)

    private class Claim(val colors: Colors)

    private val theme = MutableStateFlow<Colors?>(null)
    private val claims = MutableStateFlow<List<Claim>>(emptyList())

    /** The newest claim's colours, else the theme's; nothing until the theme has reported. */
    val colors: Flow<Colors> =
        combine(theme, claims) { themed, held -> held.lastOrNull()?.colors ?: themed }
            .filterNotNull()
            .distinctUntilChanged()

    fun setTheme(background: Color, onBackground: Color) {
        theme.value = Colors(background, onBackground)
    }

    internal fun claim(colors: Colors): Any = Claim(colors).also { c -> claims.update { it + c } }

    internal fun release(token: Any) {
        claims.update { held -> held.filterNot { it === token } }
    }
}

/** The window's title bar; null outside the window (previews, tests), where claims do nothing. */
val LocalWindowTitleBar = staticCompositionLocalOf<WindowTitleBar?> { null }

/** Gives the title bar [background] and [onBackground] while this is in the composition. */
@Composable
fun WindowTitleBarColors(background: Color, onBackground: Color) {
    val bar = LocalWindowTitleBar.current ?: return
    DisposableEffect(bar, background, onBackground) {
        val token = bar.claim(WindowTitleBar.Colors(background, onBackground))
        onDispose { bar.release(token) }
    }
}
