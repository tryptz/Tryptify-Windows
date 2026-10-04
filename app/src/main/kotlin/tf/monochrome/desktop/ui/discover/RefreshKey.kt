package tf.monochrome.desktop.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent

/**
 * F5 runs [onRefresh] for as long as this is in the composition: the key for a
 * pull-to-refresh, which a mouse cannot make. Not Ctrl+R, which is repeat.
 *
 * Heard from the whole window rather than through a key modifier on the
 * screen. A Compose key handler only hears keys while focus is inside it, and
 * after a click on the nav bar or the mini player focus is not, so F5 did
 * nothing until something on the page had been clicked first.
 *
 * Once per press: a held key repeats faster than the refreshing state can
 * reach the caller's guard, and each repeat would start another fetch.
 */
@Composable
fun RefreshKey(onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)
    DisposableEffect(Unit) {
        var held = false
        val listener = AWTEventListener { event ->
            if (event is KeyEvent && event.keyCode == KeyEvent.VK_F5) {
                if (event.id == KeyEvent.KEY_PRESSED) {
                    if (!held && event.modifiersEx == 0) refresh()
                    held = true
                } else if (event.id == KeyEvent.KEY_RELEASED) {
                    held = false
                }
            } else if (event is WindowEvent && event.id == WindowEvent.WINDOW_LOST_FOCUS) {
                // A release made in another window never arrives here.
                held = false
            }
        }
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(
            listener,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK,
        )
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
}
