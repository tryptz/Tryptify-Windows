package tf.monochrome.desktop.ui.mixer

import tf.monochrome.desktop.ui.input.wheelAdjust
import tf.monochrome.desktop.ui.input.adjustKeys
import tf.monochrome.desktop.ui.input.contextClick
import tf.monochrome.desktop.ui.input.doubleClickReset
import tf.monochrome.desktop.ui.input.focusRing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.desktop.ui.components.adjustableSemantics
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R

/** The send knob's visual diameter; its touch target is the 48dp minimum. */
internal val SendKnobSize: Dp = 28.dp

/** The quietest a send knob goes: dragging down never deletes the route. */
private const val MinSend = 0.01f

/** A send level as the strip and the rack print it: "0" at unity, else dB. */
internal fun sendDbLabel(level: Float): String =
    if (level >= 0.995f) "0"
    else "%.0f".format(20f * kotlin.math.log10(level.coerceAtLeast(0.001f)))

/**
 * FL's send knob: where the selected bus is routed, the strip's route arrow
 * becomes this, and it sets how much of the selected bus goes there.
 *
 * The arc runs from 7 o'clock and is as long as the send's linear gain, so
 * half way round is -6 dB, matching the rack's send slider. Drag up or down
 * to turn it (vertical and relative, like [PanKnob], so a sideways swipe still
 * scrolls the strip row and a grab never jumps the value); it stops just
 * above silence, so removing a route is always the deliberate tap, as it was
 * on the arrow this replaces. The dB reads inside the knob while it turns.
 *
 * Desktop: a mouse click does not remove the route, because a click is how a
 * mouse grabs a knob; removing is in the right-click menu (also the Menu key,
 * Shift+F10 or Delete), and a double-click puts the send back to unity.
 */
@Composable
internal fun SendKnob(
    level: Float,
    destinationName: String,
    accentColor: Color,
    onLevelChange: (Float) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val latestLevel by rememberUpdatedState(level)
    val latestOnLevelChange by rememberUpdatedState(onLevelChange)
    val latestOnRemove by rememberUpdatedState(onRemove)
    val removeRouteLabel = stringResource(R.string.mixer_remove_route)
    var dragging by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val windowInfo = LocalWindowInfo.current

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(SendKnobSize)
            .focusRing(focused, CircleShape)
            .adjustableSemantics(
                label = stringResource(R.string.mixer_send_to, destinationName),
                value = level,
                range = MinSend..1f,
                stateText = { "${sendDbLabel(it)} dB" },
                onValueChange = onLevelChange,
            )
            // Desktop: the wheel turns it too, stopping above silence like a drag.
            .wheelAdjust(value = level, range = MinSend..1f, step = 0.05f, onValueChange = onLevelChange)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(removeRouteLabel) { latestOnRemove(); true }
                )
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .contextClick { menuOpen = true }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.Delete) return@onKeyEvent false
                menuOpen = true
                true
            }
            .adjustKeys(
                value = level,
                range = MinSend..1f,
                step = 0.05f,
                bigStep = 0.25f,
                fineStep = 0.01f,
                interactionSource = interaction,
                onValueChange = onLevelChange,
            )
            .doubleClickReset { onLevelChange(1f) }
            .pointerInput(Unit) {
                // A tap removes the route; a mouse click never does (see above).
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (down.type == PointerType.Mouse) return@awaitEachGesture
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestOnRemove()
                }
            }
            .pointerInput(Unit) {
                var startVal = 0f
                var accumPx = 0f
                var atUnity = false
                detectVerticalDragGestures(
                    onDragStart = {
                        startVal = latestLevel
                        accumPx = 0f
                        atUnity = startVal >= 0.995f
                        dragging = true
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumPx += dragAmount * windowInfo.dragScale()
                        // ~150px of travel spans the whole knob, as on PanKnob.
                        val v = (startVal - accumPx / 150f).coerceIn(MinSend, 1f)
                        latestOnLevelChange(v)
                        // A tick when it reaches unity, the usual resting place.
                        val nowUnity = v >= 0.995f
                        if (nowUnity && !atUnity) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        atUnity = nowUnity
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 2.5.dp.toPx()
            val ringRadius = size.minDimension / 2f - stroke / 2f
            val bodyRadius = ringRadius - stroke * 1.2f
            val c = Offset(size.width / 2f, size.height / 2f)
            // The body: a dark dome, lit from above, with a soft drop below.
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = bodyRadius + 1.dp.toPx(), center = c + Offset(0f, 1.dp.toPx()))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF5C6168), Color(0xFF2E3237)),
                    center = c - Offset(0f, bodyRadius * 0.45f),
                    radius = bodyRadius * 1.6f,
                ),
                radius = bodyRadius,
                center = c,
            )
            // The ring: groove, then the level.
            val arcSize = androidx.compose.ui.geometry.Size(ringRadius * 2f, ringRadius * 2f)
            val arcTopLeft = c - Offset(ringRadius, ringRadius)
            drawArc(
                color = Color.White.copy(alpha = 0.14f),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = arcTopLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accentColor,
                startAngle = 135f, sweepAngle = 270f * level.coerceIn(0f, 1f), useCenter = false,
                topLeft = arcTopLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (dragging) {
            Text(
                text = sendDbLabel(level),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_reset)) },
                onClick = {
                    menuOpen = false
                    onLevelChange(1f)
                },
            )
            DropdownMenuItem(
                text = { Text(removeRouteLabel) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}
