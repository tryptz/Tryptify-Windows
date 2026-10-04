package tf.monochrome.desktop.ui.input

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * Home and End jump a list to its first and last item, as they do in any
 * desktop list. The list already answers Page Up and Page Down by itself; these
 * are the two keys it leaves out.
 *
 * Put it on the list's own modifier, ahead of everything the list adds inside,
 * so it hears the keys on their way up from a focused row. A text field in the
 * list is further down that way and keeps Home and End for its caret. With a
 * modifier held the keys are left alone: Shift+Home and Ctrl+End mean something
 * else to whatever is focused.
 */
@Composable
fun Modifier.listHomeEnd(state: LazyListState): Modifier =
    homeEndKeys({ state.layoutInfo.totalItemsCount }) { state.scrollToItem(it) }

/** [listHomeEnd] for a grid. */
@Composable
fun Modifier.listHomeEnd(state: LazyGridState): Modifier =
    homeEndKeys({ state.layoutInfo.totalItemsCount }) { state.scrollToItem(it) }

@Composable
private fun Modifier.homeEndKeys(
    totalItems: () -> Int,
    scrollTo: suspend (Int) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return onKeyEvent { event ->
        if (
            event.type != KeyEventType.KeyDown ||
            event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        ) return@onKeyEvent false
        val target = homeEndTarget(event.key, totalItems()) ?: return@onKeyEvent false
        scope.launch { scrollTo(target) }
        true
    }
}

/** The item [key] jumps to in a list of [totalItems], or null when it is not Home or End. */
internal fun homeEndTarget(key: Key, totalItems: Int): Int? = when (key) {
    Key.MoveHome -> 0
    Key.MoveEnd -> (totalItems - 1).coerceAtLeast(0)
    else -> null
}
