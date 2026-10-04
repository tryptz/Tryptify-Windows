package tf.monochrome.desktop.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * What the app-wide shortcuts act on. Each is optional: whoever registers the
 * bindings fills in what it can reach, and a key with nothing behind it is left
 * for someone else.
 */
class ShortcutActions(
    val playPause: (() -> Unit)? = null,
    val next: (() -> Unit)? = null,
    val previous: (() -> Unit)? = null,
    /** Relative seek, in milliseconds. */
    val seekBy: ((Long) -> Unit)? = null,
    /** Relative volume, as a fraction of full scale. */
    val volumeBy: ((Float) -> Unit)? = null,
    val toggleMute: (() -> Unit)? = null,
    val toggleShuffle: (() -> Unit)? = null,
    val cycleRepeat: (() -> Unit)? = null,
    val toggleLike: (() -> Unit)? = null,
    val openSearch: (() -> Unit)? = null,
    /** The nav bar's tabs in the order the bar shows them, for Ctrl+1 to Ctrl+9. */
    val tabs: List<() -> Unit> = emptyList(),
    val openSettings: (() -> Unit)? = null,
    val openNowPlaying: (() -> Unit)? = null,
    val forward: (() -> Unit)? = null,
    val showHelp: (() -> Unit)? = null,
)

/**
 * The window's keyboard shortcuts.
 *
 * A phone has no keyboard, so the Android app had none of this. The window
 * hands every key to [preview] before anything focused sees it and to [handle]
 * after nothing did, and the bindings are the nav host's, registered with
 * [AppShortcutBindings].
 *
 * Almost everything is bound in [handle], after the focused control: a slider
 * keeps its arrows, a text field its Ctrl+arrows, a focused button its Enter.
 * Only Space is taken early, because a mouse click focuses what it hits and
 * Space would otherwise replay the last row clicked instead of pausing — which
 * is what every desktop player does with it. It still activates a control that
 * was reached with Tab ([DesktopInput.focusVisible]), and still types a space
 * in a text field.
 *
 * Printable keys (Space, M, /) are skipped while a text field has the keyboard.
 * A text field types from the character event, so the key-down underneath it
 * would otherwise reach these bindings as well.
 */
class AppShortcuts {
    /** Read when a key arrives, so the bindings always act on the current screen. */
    @Volatile
    internal var source: () -> ShortcutActions = NoActions

    private val actions get() = source()

    /** Window `onPreviewKeyEvent`: Space, before a focused row can take it. */
    fun preview(event: KeyEvent): Boolean {
        if (event.key != Key.Spacebar || event.hasModifiers()) return false
        if (DesktopInput.focusVisible || DesktopInput.isTextInputActive()) return false
        val action = actions.playPause ?: return false
        // Swallow the key-up as well, or a focused clickable sees a release
        // with no press and the next press it gets fires twice.
        if (event.type == KeyEventType.KeyDown) action()
        return true
    }

    /** Window `onKeyEvent`: everything nothing focused wanted. */
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val a = actions
        val ctrl = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val typing = DesktopInput.isTextInputActive()

        fun run(action: (() -> Unit)?): Boolean {
            action ?: return false
            action()
            return true
        }

        return when {
            // ── Playback ──────────────────────────────────────────────
            event.key == Key.Spacebar && !ctrl && !alt && !shift && !typing -> run(a.playPause)
            event.key == Key.P && ctrl && !shift && !alt -> run(a.playPause)
            event.key == Key.DirectionRight && ctrl && !alt -> run(a.next)
            event.key == Key.DirectionLeft && ctrl && !alt -> run(a.previous)
            event.key == Key.DirectionRight && !ctrl && !alt ->
                a.seekBy?.let { it(if (shift) SEEK_LONG_MS else SEEK_MS); true } ?: false
            event.key == Key.DirectionLeft && !ctrl && !alt ->
                a.seekBy?.let { it(if (shift) -SEEK_LONG_MS else -SEEK_MS); true } ?: false
            event.key == Key.DirectionUp && ctrl -> a.volumeBy?.let { it(VOLUME_STEP); true } ?: false
            event.key == Key.DirectionDown && ctrl -> a.volumeBy?.let { it(-VOLUME_STEP); true } ?: false
            event.key == Key.M && !ctrl && !alt && !typing -> run(a.toggleMute)
            event.key == Key.S && ctrl && !alt && !shift -> run(a.toggleShuffle)
            event.key == Key.R && ctrl && !alt && !shift -> run(a.cycleRepeat)
            event.key == Key.L && ctrl && !alt && !shift -> run(a.toggleLike)

            // ── Getting around ────────────────────────────────────────
            event.key == Key.F && ctrl && !alt -> run(a.openSearch)
            event.key == Key.Slash && !ctrl && !alt && !shift && !typing -> run(a.openSearch)
            event.key == Key.Comma && ctrl -> run(a.openSettings)
            event.key == Key.J && ctrl && !alt -> run(a.openNowPlaying)
            event.key == Key.DirectionLeft && alt -> {
                DesktopInput.goBack()
                true
            }
            event.key == Key.Backspace && !ctrl && !alt && !typing -> {
                DesktopInput.goBack()
                true
            }
            event.key == Key.DirectionRight && alt -> run(a.forward)
            event.key == Key.F1 -> run(a.showHelp)
            event.key == Key.Slash && shift && !ctrl && !alt && !typing -> run(a.showHelp)
            ctrl && !alt && !shift -> tabIndex(event.key)?.let { a.tabs.getOrNull(it) }?.let { run(it) } ?: false
            else -> false
        }
    }

    private fun KeyEvent.hasModifiers() = isCtrlPressed || isMetaPressed || isAltPressed || isShiftPressed

    private fun tabIndex(key: Key): Int? = DIGITS.indexOf(key).takeIf { it >= 0 }

    companion object {
        const val SEEK_MS = 5_000L
        const val SEEK_LONG_MS = 30_000L
        const val VOLUME_STEP = 0.05f
        private val DIGITS = listOf(
            Key.One, Key.Two, Key.Three, Key.Four, Key.Five,
            Key.Six, Key.Seven, Key.Eight, Key.Nine,
        )
    }
}

private val EMPTY_ACTIONS = ShortcutActions()
private val NoActions: () -> ShortcutActions = { EMPTY_ACTIONS }

val LocalAppShortcuts = staticCompositionLocalOf { AppShortcuts() }

/**
 * Registers [actions] as the window's shortcuts for as long as this is in the
 * composition. [actions] is read when a key arrives, not captured when this
 * first ran, so it always sees the current screen.
 */
@Composable
fun AppShortcutBindings(actions: ShortcutActions) {
    val shortcuts = LocalAppShortcuts.current
    val current by rememberUpdatedState(actions)
    DisposableEffect(shortcuts) {
        val source = { current }
        shortcuts.source = source
        onDispose { if (shortcuts.source === source) shortcuts.source = NoActions }
    }
}
