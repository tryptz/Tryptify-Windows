package tf.monochrome.desktop.ui.mixer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Desktop: the mouse wheel turns a knob or moves a fader, as it does in a DAW.
 * There is no Android original; touch has no wheel.
 *
 * Each notch moves the value by [step], wheel up raising it; a touchpad's
 * fractional deltas move it by the same fraction of a step. The event is
 * consumed, so a wheel over a control adjusts it rather than scrolling the
 * row or list it sits in. A list already in a wheel scroll keeps scrolling
 * past the controls, because the scroller claims the event on the initial
 * pass while it is moving. A horizontal wheel (Shift+wheel on Windows) is
 * left alone, so it still scrolls the channel row.
 *
 * Notches that land between two frames build on each other instead of on the
 * value the last frame drew, or a fast spin would lose most of them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.wheelAdjust(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
): Modifier {
    // [0] the value this was last handed, [1] what it emitted from it.
    val pending = remember { floatArrayOf(Float.NaN, Float.NaN) }
    // The node takes the newest lambda on every recomposition, so [value] and
    // [onValueChange] are never stale here.
    return onPointerEvent(PointerEventType.Scroll) { event ->
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        val notches = change.scrollDelta.y
        if (notches == 0f || change.isConsumed) return@onPointerEvent
        val base = if (pending[0] == value) pending[1] else value
        val next = (base - notches * step).coerceIn(range.start, range.endInclusive)
        pending[0] = value
        pending[1] = next
        if (next != base) onValueChange(next)
        change.consume()
    }
}
