package tf.monochrome.desktop.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import tf.monochrome.desktop.ui.components.TrackSelectionState
import tf.monochrome.desktop.ui.input.DesktopInput

/**
 * Ctrl+click and Shift+click for a list backed by a [TrackSelectionState].
 *
 * On a phone a long press starts a selection. A mouse has no reason to hold a
 * button down, so on a desktop that left multi-select hidden. Here a click with
 * Ctrl toggles one row and a click with Shift selects the run from the last row
 * toggled, as in a file manager. A plain click does what it always did: it
 * toggles while a selection is open and opens the row otherwise, so touch is
 * unchanged.
 *
 * The modifiers are read when the click lands, from the window, because a
 * clickable's onClick does not carry them.
 */
@Stable
class SelectionClicks<K : Any> internal constructor(
    val selection: TrackSelectionState<K>,
    private val windowInfo: WindowInfo,
) {
    /** The row a Shift+click extends from. Not state: nothing draws it. */
    private var anchor: K? = null

    /**
     * A click on the row [key]. [keys] are the rows in the order they are
     * drawn, for a range; [open] is what a plain click does outside a
     * selection.
     */
    fun click(key: K, keys: List<K>, open: () -> Unit) {
        val mods = windowInfo.keyboardModifiers
        when {
            mods.isShiftPressed -> selectRange(key, keys)
            mods.isCtrlPressed || mods.isMetaPressed || selection.active -> toggle(key)
            else -> open()
        }
    }

    /** A long press, or anything else that toggles one row. */
    fun toggle(key: K) {
        selection.toggle(key)
        anchor = key
    }

    /** Ctrl+A: adds every row in [keys], keeping what is already selected. */
    fun selectAll(keys: List<K>) {
        for (k in keys) if (k !in selection.selectedIds) selection.toggle(k)
    }

    private fun selectRange(key: K, keys: List<K>) {
        val to = keys.indexOf(key)
        val from = anchor?.takeIf { selection.active }?.let(keys::indexOf)?.takeIf { it >= 0 }
        if (from == null || to < 0) {
            if (key !in selection.selectedIds) toggle(key) else anchor = key
            return
        }
        // Adds, never removes: a range over rows already chosen keeps them.
        for (i in minOf(from, to)..maxOf(from, to)) {
            val k = keys[i]
            if (k !in selection.selectedIds) selection.toggle(k)
        }
    }
}

@Composable
fun <K : Any> rememberSelectionClicks(selection: TrackSelectionState<K>): SelectionClicks<K> {
    val windowInfo = LocalWindowInfo.current
    return remember(selection, windowInfo) { SelectionClicks(selection, windowInfo) }
}

/**
 * Ctrl+A selects every row and Delete acts on the selection, while focus is
 * somewhere in the list this is on. Put it on the list itself: its rows are
 * what Tab and a click focus, and key events bubble up to here from them.
 *
 * Both are skipped while a text field has the keyboard, where Ctrl+A selects
 * text and Delete deletes it.
 */
fun Modifier.selectionKeys(
    onSelectAll: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (DesktopInput.isTextInputActive()) return@onKeyEvent false
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    when {
        event.key == Key.A && ctrl && !event.isShiftPressed && !event.isAltPressed -> {
            val action = onSelectAll ?: return@onKeyEvent false
            action()
            true
        }
        event.key == Key.Delete && !ctrl && !event.isShiftPressed && !event.isAltPressed -> {
            val action = onDelete ?: return@onKeyEvent false
            action()
            true
        }
        else -> false
    }
}
