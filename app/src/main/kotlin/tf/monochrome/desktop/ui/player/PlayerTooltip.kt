package tf.monochrome.desktop.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Desktop: a hover hint for a player control whose exact value, or whose
 * second gesture, is not written on it. A finger cannot hover, so on Android
 * this is [content] alone.
 *
 * Plain and opaque on purpose. The hint is a popup, which is a separate
 * window, and a glass pane there has no backdrop to sample
 * (docs/ui-invariants.md).
 *
 * [modifier] goes on the box that holds [content], so a Row's weight or a
 * Box's alignment belongs here rather than on the control inside.
 *
 * @param text read only while the hint shows, so a value changing under the
 *   pointer redraws the hint and not the control. Null shows nothing, which
 *   keeps the control's place in the tree when there is nothing to say.
 * @param above over the pointer instead of under it, for a control the hint
 *   would otherwise cover, such as a scrubber being read along.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerTooltip(
    text: () -> String?,
    modifier: Modifier = Modifier,
    delayMillis: Int = PLAYER_TOOLTIP_DELAY_MS,
    above: Boolean = false,
    content: @Composable () -> Unit,
) {
    val placement = remember(above) {
        if (above) {
            TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, (-12).dp), alignment = Alignment.TopCenter)
        } else {
            TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp))
        }
    }
    TooltipArea(
        tooltip = {
            val shown = text()
            if (shown != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        },
        modifier = modifier,
        delayMillis = delayMillis,
        tooltipPlacement = placement,
        content = content,
    )
}

/** The usual desktop wait before a hint, long enough that passing over a control shows nothing. */
internal const val PLAYER_TOOLTIP_DELAY_MS = 500
