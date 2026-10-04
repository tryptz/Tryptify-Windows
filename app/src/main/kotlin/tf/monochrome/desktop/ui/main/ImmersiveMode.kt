package tf.monochrome.desktop.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether Settings › System › Full screen is on, published by MainActivity so
 * anything that hides the bars for its own reasons can tell what to restore to.
 *
 * Without it every local fullscreen mode would end by showing the bars again on
 * the way out, silently cancelling the app-wide setting — see [SystemBarsHidden].
 */
val LocalImmersiveFullScreen = staticCompositionLocalOf { false }

/**
 * Hides or shows the status and navigation bars for as long as this composable
 * is in the composition.
 *
 * Desktop: a window has no status or navigation bar, so there is nothing to
 * hide or to restore, and this does nothing. It stays because the player calls
 * it for the visualiser's fullscreen mode.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    // Desktop: no WindowInsetsController to drive; see above.
}
