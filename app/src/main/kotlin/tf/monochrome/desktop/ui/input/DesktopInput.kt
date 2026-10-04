package tf.monochrome.desktop.ui.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * The window-wide half of desktop input: the parts that are not about any one
 * control. There is no Android original — a phone has one way in, the finger,
 * and the system already knows what Back means.
 *
 * Three things live here because only a listener on the whole AWT event stream
 * sees them, whichever layer (the window, a dialog, a menu) has the pointer:
 *
 * - **Whether focus should be shown.** CSS's `:focus-visible`: a mouse click
 *   focuses what it hits, and drawing a ring around every row you click is
 *   noise. A ring is for someone moving through the app with Tab and the
 *   arrows, so it shows after those keys and goes away at the next mouse press.
 * - **The mouse's Back and Forward buttons.** Back is sent as Escape, which
 *   Compose already turns into Back for the top-most layer: a sheet, a dialog,
 *   a pushed screen. A handler on the window's root never saw a click made over
 *   a dialog or a menu, so those could not be closed from the mouse.
 * - **Whether a text field is being typed in**, for the shortcuts in
 *   [AppShortcuts] that are printable keys.
 */
object DesktopInput {
    /** True while focus was last moved from the keyboard. Snapshot state: rings recompose on it. */
    var focusVisible by mutableStateOf(false)
        private set

    /**
     * What the mouse's Forward button does. The nav host owns it, because only
     * it knows where forward is; null leaves the button doing nothing.
     */
    @Volatile
    var onForward: (() -> Unit)? = null

    /** [focusVisible] as it was before the navigation key now being handled. */
    private var focusVisibleBeforeKey = false

    private var installed = false

    /** Starts listening. Called once, from main, before the window opens. */
    fun install() {
        if (installed) return
        installed = true
        Toolkit.getDefaultToolkit().addAWTEventListener(
            ::onAwtEvent,
            AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK,
        )
    }

    /**
     * True while a text field has the keyboard.
     *
     * Compose turns input methods on for exactly as long as a text field is
     * being edited, and hands the window's component the field's
     * `InputMethodRequests` for that time (`ComposeSceneMediator.enableInput`).
     * So this needs no list of fields: a search box, a dialog's name field and a
     * BPM editor all answer the same way.
     */
    fun isTextInputActive(): Boolean =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.inputMethodRequests != null

    /**
     * Back, the way Escape does it: to whichever layer has focus, so the
     * top-most sheet or dialog closes before the screen under it goes back.
     */
    fun goBack(fallback: Component? = null) {
        // With nothing focused (the window has just been activated by the
        // click itself), the window's last focus owner is where Compose listens.
        val target = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            ?: fallback?.let { SwingUtilities.getWindowAncestor(it)?.mostRecentFocusOwner ?: it }
            ?: return
        val now = System.currentTimeMillis()
        for (id in intArrayOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            target.dispatchEvent(KeyEvent(target, id, now, 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED))
        }
    }

    /**
     * The arrow just pressed was a shortcut (a seek, a skip, a volume step), not
     * a move. The AWT listener sees an arrow before anyone has acted on it, so
     * it has already shown focus; put that back. Otherwise a seek after clicking a
     * row would show the row's ring and hand it Space, replaying the row instead
     * of pausing.
     */
    fun keyWasShortcut() {
        focusVisible = focusVisibleBeforeKey
    }

    private fun onAwtEvent(event: AWTEvent) {
        when (event) {
            is KeyEvent -> if (event.id == KeyEvent.KEY_PRESSED && event.keyCode in NAVIGATION_KEYS) {
                focusVisibleBeforeKey = focusVisible
                focusVisible = true
            }
            is MouseEvent -> when (event.id) {
                MouseEvent.MOUSE_PRESSED -> focusVisible = false
                // On release, as a browser does: a press that is dragged off
                // and let go elsewhere still counts, and the press itself has
                // already gone to whatever is under the pointer.
                MouseEvent.MOUSE_RELEASED -> when (event.button) {
                    MOUSE_BACK -> goBack(event.component)
                    MOUSE_FORWARD -> onForward?.invoke()
                }
            }
        }
    }

    // AWT numbers the side buttons 4 and 5, after left, middle and right.
    private const val MOUSE_BACK = 4
    private const val MOUSE_FORWARD = 5

    /** The keys that move focus. Typing, Space and Enter do not, so they leave the ring as it was. */
    private val NAVIGATION_KEYS = setOf(
        KeyEvent.VK_TAB,
        KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
        KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
    )
}
