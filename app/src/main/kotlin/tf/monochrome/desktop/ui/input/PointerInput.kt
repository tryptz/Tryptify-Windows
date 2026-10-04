package tf.monochrome.desktop.ui.input

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The desktop's ways to ask for what a long press opens on a phone: a
 * right-click, the keyboard's Menu key, and Shift+F10.
 *
 * Holding the left button still works — Compose turns a 500 ms hold into a long
 * click on the desktop too — but nobody looks for a menu that way with a mouse,
 * so on its own it left every row menu, every multi-select and every "remove"
 * hidden.
 *
 * The keys only arrive while this element or something inside it has focus, so
 * put this *before* the `clickable` in the chain: the clickable is what Tab
 * focuses, and a modifier earlier in the chain is its ancestor.
 *
 * The click is consumed, so a menu inside a row that has a menu of its own opens
 * only the inner one.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.contextClick(enabled: Boolean = true, onContextClick: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val action by rememberUpdatedState(onContextClick)
        this
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.button != PointerButton.Secondary) return@onPointerEvent
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (change.isConsumed || change.type != PointerType.Mouse) return@onPointerEvent
                event.changes.forEach { it.consume() }
                action()
            }
            .onKeyEvent { event ->
                val menuKey = event.key.nativeKeyCode == AwtKeyEvent.VK_CONTEXT_MENU ||
                    (event.key == Key.F10 && event.isShiftPressed)
                if (!menuKey) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) action()
                true
            }
    }

/**
 * What a mouse sees before it clicks: the hand cursor over anything that does
 * something, a faint rim while the pointer is over it, and a firm one while it
 * has keyboard focus.
 *
 * Android's clickables had no hover state because a finger has none; most of
 * the app's own clickables also pass `indication = null`, to make room for the
 * squeeze. On a desktop that left nothing to tell a row you can click from a
 * label you cannot, until you had clicked it.
 *
 * Drawn **over** the element as an outline in [shape], never as a pane under
 * it. A tint behind a glass row is a slab under glass (see
 * docs/ui-invariants.md), and a fill on top would veil the artwork. The rim is
 * where glass already carries its light.
 *
 * [interactionSource] must be the one the element's `clickable` reports to,
 * which is where hover and focus arrive.
 */
fun Modifier.desktopHover(
    interactionSource: InteractionSource,
    shape: Shape = DefaultHoverShape,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else composed {
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val ring = focused && DesktopInput.focusVisible
    val ink = MaterialTheme.colorScheme.onSurface
    this
        .pointerHoverIcon(PointerIcon.Hand)
        .drawWithContent {
            drawContent()
            if (!hovered && !ring) return@drawWithContent
            val width = (if (ring) 2.dp else 1.dp).toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = ink.copy(alpha = if (ring) 0.75f else 0.22f),
                style = Stroke(width = width),
            )
        }
}

private val DefaultHoverShape = RoundedCornerShape(12.dp)

/**
 * Calls [onActivity] whenever the mouse moves over, enters, or wheels on this
 * element. For overlays that hide themselves after a few idle seconds: on a
 * phone a tap brought them back, and a mouse never taps just to look.
 *
 * Nothing is consumed; whatever is underneath still gets the pointer.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onPointerActivity(onActivity: () -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onActivity)
    this
        .onPointerEvent(PointerEventType.Move) { event ->
            if (event.changes.firstOrNull()?.type == PointerType.Mouse) action()
        }
        .onPointerEvent(PointerEventType.Enter) { action() }
        .onPointerEvent(PointerEventType.Scroll) { action() }
}

/**
 * A keyboard focus ring for a custom control that draws itself: a knob, a
 * fader, a seek tube. Only shown when focus came from the keyboard; see
 * [DesktopInput.focusVisible].
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape = DefaultHoverShape): Modifier {
    val ring = focused && DesktopInput.focusVisible
    val ink = MaterialTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        if (ring) {
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = ink.copy(alpha = 0.75f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
