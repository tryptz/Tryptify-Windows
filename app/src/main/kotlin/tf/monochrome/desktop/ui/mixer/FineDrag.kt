package tf.monochrome.desktop.ui.mixer

import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.WindowInfo

/** Desktop: how fast a knob or fader follows the mouse while Shift or Ctrl is held. */
internal const val FineDragScale = 0.25f

/**
 * Desktop: what to scale a drag's travel by right now, [FineDragScale] while
 * Shift or Ctrl is held and 1 otherwise, so the last fraction of a decibel is
 * reachable with a mouse as it is in a DAW. Read on every drag event and
 * applied to that event's travel only, so pressing or letting go of the key
 * mid-drag changes the speed without jumping the value. A phone has neither
 * key and always reads 1.
 */
internal fun WindowInfo.dragScale(): Float =
    if (keyboardModifiers.isShiftPressed || keyboardModifiers.isCtrlPressed) FineDragScale else 1f
