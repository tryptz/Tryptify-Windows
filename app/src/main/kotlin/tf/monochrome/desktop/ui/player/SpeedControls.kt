package tf.monochrome.desktop.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.desktop.R
import tf.monochrome.desktop.ui.components.bounceClick
import tf.monochrome.desktop.ui.input.contextClick
import tf.monochrome.desktop.ui.input.desktopHover

// The speed panel's one button family. The panel used to mix four kinds of
// control — Material's segmented row (grey when selected, whatever the
// theme), a square-cornered chip, and two sizes of outlined pill whose
// accent-coloured labels sat accent-on-accent on a tinted panel — so nothing
// in it read as belonging together and the steppers barely read at all.
//
// Here every control is the same capsule at the same height: a quiet tonal
// fill that sits on the glass rather than being more glass (a button on the
// pane, not a pane), labels in the panel's own content colour for contrast,
// and the accent kept for what is live — the selected segment, the stepper's
// signs. Press feedback is bounceClick, the app's squeeze
// for controls that aren't glass themselves.

/** Height of every control in the panel: the 48dp touch minimum, less a hair. */
internal val SpeedControlHeight: Dp = 44.dp

private val Capsule = RoundedCornerShape(percent = 50)

/** The quiet fill and hairline every unselected control shares. */
@Composable
private fun tonalFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

@Composable
private fun tonalBorder(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

/** Readable content on an accent fill, whatever the theme's accent is. */
private fun onAccent(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

/**
 * A two-or-more-way choice as one capsule; the selected segment fills with
 * the accent. Replaces Material's SegmentedButton row in this panel, whose
 * selected colour is the theme's secondary container — grey on most themes.
 */
@Composable
internal fun SpeedSegmented(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(SpeedControlHeight)
            .clip(Capsule)
            .background(tonalFill())
            .border(1.dp, tonalBorder(), Capsule)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(if (selected) accent else Color.Transparent, label = "segFill")
            val content by animateColorAsState(
                if (selected) onAccent(accent) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                label = "segContent",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(Capsule)
                    .background(fill)
                    .bounceClick(scaleDown = 0.97f) { onSelect(index) }
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `[ − ]  value  [ + ]` in one capsule: the number sits between the two
 * buttons that move it, instead of two wide pills that never showed it.
 * [compact] is the pitch row's, which shares its line with a label and reset.
 */
@Composable
internal fun SpeedStepper(
    value: String,
    accent: Color,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementLabel: String,
    incrementLabel: String,
    modifier: Modifier = Modifier,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    compact: Boolean = false,
    /** Tap on the number — the BPM row measures the tempo again with it. */
    onValueClick: (() -> Unit)? = null,
    valueClickLabel: String? = null,
    /** Long-press on the number — the BPM row opens a keyboard with it. */
    onValueLongPress: (() -> Unit)? = null,
    valueLongPressLabel: String? = null,
) {
    // Read through updated state so the gesture detector, keyed once, always
    // calls the current lambdas: re-keying it on every recomposition would
    // cancel a long-press in progress whenever the panel redrew.
    val tap by rememberUpdatedState(onValueClick)
    val longPress by rememberUpdatedState(onValueLongPress)
    val interactive = onValueClick != null || onValueLongPress != null
    val valueInteraction = remember { MutableInteractionSource() }
    // Desktop: what a click and a right-click on the number do, which nothing on it says.
    val clickHint = if (onValueClick != null && valueClickLabel != null) {
        stringResource(R.string.hint_click_desktop, valueClickLabel)
    } else null
    val rightClickHint = if (onValueLongPress != null && valueLongPressLabel != null) {
        stringResource(R.string.hint_right_click_desktop, valueLongPressLabel)
    } else null
    Row(
        modifier = modifier
            .height(SpeedControlHeight)
            .clip(Capsule)
            .background(tonalFill())
            .border(1.dp, tonalBorder(), Capsule)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepSign(Icons.Default.Remove, decrementLabel, accent, canDecrement, onDecrement)
        PlayerTooltip(
            text = { listOfNotNull(clickHint, rightClickHint).joinToString("\n").ifEmpty { null } },
            // The weight belongs on the box the hint wraps the number in.
            modifier = if (compact) Modifier else Modifier.weight(1f),
        ) {
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = (if (compact) Modifier.widthIn(min = 52.dp) else Modifier.fillMaxWidth())
                    .then(
                        if (interactive) Modifier
                            // Desktop: the long press is also a right-click, the
                            // Menu key and Shift+F10. Before the clickable, so it
                            // hears the keys while the number has focus.
                            .contextClick(enabled = onValueLongPress != null) { longPress?.invoke() }
                            .desktopHover(valueInteraction, Capsule)
                            // A clickable rather than a bare tap detector, so Tab
                            // reaches the number and Enter measures again.
                            .combinedClickable(
                                interactionSource = valueInteraction,
                                indication = null,
                                onClickLabel = valueClickLabel,
                                onLongClickLabel = valueLongPressLabel,
                                onLongClick = if (onValueLongPress != null) {
                                    { longPress?.invoke() }
                                } else null,
                                onClick = { tap?.invoke() },
                            )
                        else Modifier
                    )
                    .padding(horizontal = 6.dp),
            )
        }
        // The long press, in sight: a mouse never holds a button down to see
        // what happens, and a keyboard cannot.
        if (onValueLongPress != null && !compact) {
            StepSign(
                icon = Icons.Default.Edit,
                label = valueLongPressLabel.orEmpty(),
                accent = Color.Transparent,
                enabled = true,
                onClick = { longPress?.invoke() },
                iconSize = 16.dp,
            )
        }
        StepSign(Icons.Default.Add, incrementLabel, accent, canIncrement, onIncrement)
    }
}

@Composable
private fun StepSign(
    icon: ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = Modifier
            .size(SpeedControlHeight - 8.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = if (enabled) 0.18f else 0.06f))
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (enabled) Modifier.bounceClick(scaleDown = 0.9f, hoverShape = CircleShape, onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(iconSize))
    }
}
