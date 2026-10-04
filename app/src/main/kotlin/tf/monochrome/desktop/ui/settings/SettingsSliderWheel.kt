package tf.monochrome.desktop.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import tf.monochrome.desktop.ui.input.focusRing
import tf.monochrome.desktop.ui.input.wheelAdjust

/**
 * Desktop: the mouse wheel moves a settings slider the way a drag does.
 * Goes on the modifier of a Material 3 Slider, which supplies the focus and
 * the arrow keys this builds on.
 *
 * A notch moves one detent on a slider with [steps], and a fiftieth of the
 * range on a continuous one (Ctrl for a tenth of that). [onValueChange] is
 * what the slider's own onValueChange does. [onCommit] is what its
 * onValueChangeFinished does — the write a drag saves until the finger lifts —
 * and runs once the wheel has rested rather than on every notch, so a spin of
 * the wheel is one write, not twenty. A slider that leaves the screen inside
 * that pause still writes.
 *
 * The wheel moves the slider only while it has focus: a click on it or Tab
 * gives it that. A settings page is mostly sliders, and the list's own scroll
 * gives up the wheel to whatever is under the pointer a moment after each
 * spin, so a hover-only wheel would stop a page on the first slider that
 * scrolled under the pointer and start changing it.
 */
@Composable
internal fun Modifier.sliderWheel(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    step: Float = if (steps > 0) {
        (range.endInclusive - range.start) / (steps + 1)
    } else {
        (range.endInclusive - range.start) / 50f
    },
    fineStep: Float = if (steps > 0) step else step / 10f,
    enabled: Boolean = true,
    onCommit: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
): Modifier {
    var turns by remember { mutableIntStateOf(0) }
    // Whether a notch has moved the value and the save after it has not run.
    val unsaved = remember { booleanArrayOf(false) }
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(turns) {
        if (turns == 0) return@LaunchedEffect
        delay(SLIDER_WHEEL_COMMIT_DELAY_MS)
        unsaved[0] = false
        commit?.invoke()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (unsaved[0]) {
                unsaved[0] = false
                commit?.invoke()
            }
        }
    }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    return this
        .focusRequester(focusRequester)
        .onFocusChanged { focused = it.isFocused }
        .focusRing(focused)
        // The Slider takes focus from Tab only; a click has to give it focus
        // too, or the wheel would never reach it from the mouse alone.
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Press) continue
                    if (!event.buttons.isPrimaryPressed) continue
                    if (event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                    focusRequester.requestFocus()
                }
            }
        }
        .wheelAdjust(
            value = value,
            range = range,
            step = step,
            fineStep = fineStep,
            enabled = enabled && focused,
        ) {
            onValueChange(it)
            if (onCommit != null) {
                unsaved[0] = true
                turns++
            }
        }
}

/** How long a settings slider waits after the last wheel notch before it saves. */
private const val SLIDER_WHEEL_COMMIT_DELAY_MS = 400L
