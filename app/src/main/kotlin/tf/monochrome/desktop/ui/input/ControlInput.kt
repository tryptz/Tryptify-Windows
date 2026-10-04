package tf.monochrome.desktop.ui.input

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalViewConfiguration

/**
 * Desktop: the mouse wheel turns a knob or moves a fader, as it does in a DAW.
 * There is no Android original; touch has no wheel.
 *
 * Each notch moves the value by [step], wheel up raising it; a touchpad's
 * fractional deltas move it by the same fraction of a step. Held Ctrl, a notch
 * moves it by [fineStep] instead, for the last half-decibel. (Not Shift: on
 * Windows Shift turns the wheel sideways, and a sideways wheel is left alone so
 * it still scrolls the row a control sits in.)
 *
 * The event is consumed, so a wheel over a control adjusts it rather than
 * scrolling the list it sits in. A list already in a wheel scroll keeps
 * scrolling past the controls, because the scroller claims the event on the
 * initial pass while it is moving.
 *
 * Notches that land between two frames build on each other instead of on the
 * value the last frame drew, or a fast spin would lose most of them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.wheelAdjust(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    fineStep: Float = step / 10f,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
): Modifier {
    if (!enabled) return this
    // [0] the value this was last handed, [1] what it emitted from it.
    val pending = remember { floatArrayOf(Float.NaN, Float.NaN) }
    // The node takes the newest lambda on every recomposition, so [value] and
    // [onValueChange] are never stale here.
    return onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        val notches = change.scrollDelta.y
        if (notches == 0f || change.isConsumed) return@onPointerEvent
        val by = if (event.keyboardModifiers.isCtrlPressed) fineStep else step
        val base = if (pending[0] == value) pending[1] else value
        val next = (base - notches * by).coerceIn(range.start, range.endInclusive)
        pending[0] = value
        pending[1] = next
        if (next != base) onValueChange(next)
        change.consume()
    }
}

/**
 * The keys a desktop slider answers to, for a control that draws itself:
 * the arrows move it by [step] (Up and Right raise it), Page Up and Page Down by
 * [bigStep], Home and End to either end. Held Ctrl, the arrows move it by
 * [fineStep].
 *
 * Makes the element focusable, so Tab reaches it; pair it with [focusRing] so
 * the listener can see where Tab went. Pass the same [interactionSource] to
 * both if the control already has one.
 */
fun Modifier.adjustKeys(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    bigStep: Float = step * 10f,
    fineStep: Float = step / 10f,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Float) -> Unit,
): Modifier = if (!enabled) this else composed {
    val current by rememberUpdatedState(value)
    val emit by rememberUpdatedState(onValueChange)
    this
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val small = if (event.isCtrlPressed) fineStep else step
            val next = when (event.key) {
                Key.DirectionUp, Key.DirectionRight -> current + small
                Key.DirectionDown, Key.DirectionLeft -> current - small
                Key.PageUp -> current + bigStep
                Key.PageDown -> current - bigStep
                Key.MoveHome -> range.start
                Key.MoveEnd -> range.endInclusive
                else -> return@onKeyEvent false
            }.coerceIn(range.start, range.endInclusive)
            if (next != current) emit(next)
            true
        }
        .focusable(interactionSource = interactionSource)
}

/**
 * Double-click to put a control back where it started, as every DAW does with a
 * knob. On a phone the reset hid behind a long press or did not exist.
 *
 * Counts primary-button presses only and consumes nothing, so a drag that
 * starts on the control still turns it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.doubleClickReset(enabled: Boolean = true, onReset: () -> Unit): Modifier =
    if (!enabled) this else composed {
        val reset by rememberUpdatedState(onReset)
        val config = LocalViewConfiguration.current
        // When and where the last press landed.
        val lastAt = remember { longArrayOf(Long.MIN_VALUE / 2) }
        val lastPos = remember { floatArrayOf(0f, 0f) }
        onPointerEvent(PointerEventType.Press) { event ->
            if (event.button != PointerButton.Primary) return@onPointerEvent
            val change = event.changes.firstOrNull() ?: return@onPointerEvent
            val at = change.position
            val near = (at - Offset(lastPos[0], lastPos[1])).getDistance() <= config.touchSlop
            val quick = change.uptimeMillis - lastAt[0] <= config.doubleTapTimeoutMillis
            if (near && quick) {
                // A third click starts a new pair rather than resetting again.
                lastAt[0] = Long.MIN_VALUE / 2
                reset()
            } else {
                lastAt[0] = change.uptimeMillis
                lastPos[0] = at.x
                lastPos[1] = at.y
            }
        }
    }
