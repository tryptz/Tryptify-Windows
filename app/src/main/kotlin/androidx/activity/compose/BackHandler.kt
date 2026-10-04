package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi

/**
 * Android's BackHandler, forwarded to Compose Multiplatform's. On the desktop
 * the back gesture is the Escape key (and the mouse's back button, which the
 * window root maps onto the same dispatcher).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)
}
