package tf.monochrome.desktop.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.desktop.ui.components.adjustableSemantics
import tf.monochrome.desktop.ui.input.AppShortcuts
import tf.monochrome.desktop.ui.input.DesktopInput
import tf.monochrome.desktop.ui.input.wheelAdjust
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R

/**
 * Scrubber plus elapsed / center-label / total time. The center label carries
 * the quality or queue-position context from the generated design.
 *
 * When the player glass is on with the "Glass progress bar" setting, the
 * scrubber is a thin liquid-glass tube (a thermometer) that fills up, with a
 * playhead dot that swells the tube into a smooth sine-wave bulge. Otherwise it
 * falls back to a plain Material slider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerProgress(
    fraction: Float,
    elapsedLabel: String,
    totalLabel: String,
    centerLabel: String,
    accent: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether to draw the scrubber at all. Off for a live stream, where there is
     * no position to drag to: a slider that moves under your thumb and then
     * snaps back reads as a broken control, while a row that just says LIVE
     * reads as the truth about what you are listening to.
     */
    showScrubber: Boolean = true,
    /** The track's length, so a wheel notch over the scrubber is a fixed number of seconds. */
    durationMs: Long = 0L,
) {
    val glass = LocalPlayerGlass.current
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else accent

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!showScrubber) {
            // Nothing where the tube would be; the labels below carry the row.
        } else if (glass.enabled && glass.progressGlass) {
            GlassProgressTube(
                fraction = fraction,
                tint = tint,
                onSeek = onSeek,
                onSeekFinished = onSeekFinished,
                modifier = Modifier.fillMaxWidth(),
                durationMs = durationMs,
            )
        } else {
            PlainSlider(fraction, tint, onSeek, onSeekFinished, durationMs)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = elapsedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            if (centerLabel.isNotBlank()) {
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The liquid-glass "thermometer" scrubber: a thin tube whose vertical thickness
 * follows a raised-cosine (sine-wave) bump centred on the playhead, so the dot
 * reads as a smooth bulge in the tube. The played portion is filled with [tint];
 * the whole thing is handed to the [playerGlass] shader, which bevels the tube
 * and the bulge into refractive glass. Drag or tap to seek.
 */
@Composable
internal fun GlassProgressTube(
    fraction: Float,
    tint: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 0L,
    /** What a screen reader calls the control; the seek bar's name unless it is something else. */
    semanticsLabel: String? = null,
    /** How a screen reader reads a position; a percentage unless given. */
    stateText: ((Float) -> String)? = null,
) {
    val label = semanticsLabel ?: stringResource(R.string.action_seek)
    var dragging by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = focused && DesktopInput.focusVisible
    // The position the finger is currently over, committed on release. Held in a
    // MutableState so the pointerInput(Unit) closures (created once) read/write
    // the live value instead of a stale captured fraction.
    var seekTarget by remember { mutableFloatStateOf(0f) }
    // The dot always shows a bulge; it swells further while dragging.
    val bulge by animateFloatAsState(
        targetValue = if (dragging) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "progressBulge",
    )
    val frac = fraction.coerceIn(0f, 1f)
    // Desktop: where the pointer rests, in the tube's own pixels.
    var hoverX by remember { mutableFloatStateOf(0f) }
    var hoverWidth by remember { mutableIntStateOf(0) }

    // Desktop: hovering names the time a click there seeks to, mapped the way
    // the tap below maps it.
    PlayerTooltip(
        text = { seekHint(hoverX / hoverWidth, durationMs) },
        modifier = modifier,
        delayMillis = SEEK_HINT_DELAY_MS,
        above = true,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .trackHover { x, width -> hoverX = x; hoverWidth = width }
                .adjustableSemantics(
                    label = label,
                    value = frac,
                    range = 0f..1f,
                    stateText = stateText ?: { java.text.NumberFormat.getPercentInstance().format(it) },
                    onValueChange = { onSeek(it); onSeekFinished(it) },
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .wheelSeek(frac, durationMs, onSeek, onSeekFinished)
                // Desktop: Tab reaches the tube. The arrows are left to the app's
                // own seek (five seconds, thirty with Shift), so a focused tube
                // and an unfocused one answer them the same way; only the ends
                // are the tube's.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val to = when (event.key) {
                        Key.MoveHome -> 0f
                        Key.MoveEnd -> 1f
                        else -> return@onKeyEvent false
                    }
                    onSeek(to)
                    onSeekFinished(to)
                    true
                }
                .focusable(interactionSource = interaction)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val f = (pos.x / size.width).coerceIn(0f, 1f)
                        onSeek(f)
                        onSeekFinished(f)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            val f = (pos.x / size.width).coerceIn(0f, 1f)
                            seekTarget = f
                            onSeek(f)
                        },
                        onDragEnd = { dragging = false; onSeekFinished(seekTarget) },
                        // Commit on cancel too — otherwise the parent stayed in
                        // seek mode and the bar/time froze at the last finger
                        // position until the next seek.
                        onDragCancel = { dragging = false; onSeekFinished(seekTarget) },
                        onHorizontalDrag = { change, _ ->
                            val f = (change.position.x / size.width).coerceIn(0f, 1f)
                            seekTarget = f
                            onSeek(f)
                        },
                    )
                }
                .playerGlass(tint = tint)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val w = size.width
            val cy = size.height / 2f
            val baseHalf = 2.6.dp.toPx()                          // thin tube half-thickness
            val bulgeHalf = 7.dp.toPx() + 5.dp.toPx() * bulge     // dot half-thickness
            val sigma = 26.dp.toPx()                              // bulge half-width
            // Inset by the bulge's HALF-WIDTH, not its half-thickness. Insetting by
            // the thickness only kept the dot's core on screen — the raised-cosine
            // skirt around it still ran off the left edge, so at 0:00 the bubble was
            // sliced flat by x=0 instead of closing. At sigma the bump reaches zero
            // exactly at the edge, so the bubble is always whole at both ends.
            // Bounded so a narrow bar can't inset away most of its own travel.
            val inset = minOf(sigma, w / 4f)
            val thumbX = inset + frac * (w - 2f * inset)

            // Raised-cosine (sine-wave) contour: fat at the dot, thin along the tube.
            fun halfAt(x: Float): Float {
                val d = abs(x - thumbX)
                val bump = if (d < sigma) 0.5f * (1f + cos(PI.toFloat() * d / sigma)) else 0f
                return baseHalf + (bulgeHalf - baseHalf) * bump
            }

            // The contour runs between the cap centres; the caps themselves are
            // added as circles below, so the tube ends round off instead of being
            // squared away by the canvas edge the way the bubble was.
            val tubeStart = baseHalf
            val tubeEnd = w - baseHalf
            val path = Path()
            val step = 3f
            path.moveTo(tubeStart, cy - halfAt(tubeStart))
            var x = tubeStart + step
            while (x < tubeEnd) { path.lineTo(x, cy - halfAt(x)); x += step }
            path.lineTo(tubeEnd, cy - halfAt(tubeEnd))
            x = tubeEnd - step
            while (x > tubeStart) { path.lineTo(x, cy + halfAt(x)); x -= step }
            path.lineTo(tubeStart, cy + halfAt(tubeStart))
            path.close()
            // Same winding direction, so these union with the contour rather than
            // punching holes in it.
            path.addOval(Rect(center = Offset(tubeStart, cy), radius = baseHalf))
            path.addOval(Rect(center = Offset(tubeEnd, cy), radius = baseHalf))

            // Inactive glass tube.
            drawPath(path, color = Color.White.copy(alpha = 0.22f))
            // Filled (played) portion up to the dot.
            clipRect(right = thumbX) { drawPath(path, color = tint) }
            // A defined dot core at the playhead so the bulge reads as the thumb.
            drawCircle(
                color = tint,
                radius = baseHalf + (bulgeHalf - baseHalf) * 0.6f,
                center = Offset(thumbX, cy),
            )
            // Keyboard focus, traced on the tube itself rather than boxed around it.
            if (ring) drawPath(path, color = Color.White.copy(alpha = 0.75f), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/**
 * Desktop: reports where the pointer rests over a scrubber, in the control's
 * own pixels. Read on the Initial pass, ahead of the tap and drag detectors,
 * so their consuming a change does not hide it.
 */
private fun Modifier.trackHover(onHover: (x: Float, width: Int) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                    onHover(event.changes.first().position.x, size.width)
                }
            }
        }
    }

/** The time at [fraction] of the track, as the elapsed label writes it; null while the length is unknown. */
private fun seekHint(fraction: Float, durationMs: Long): String? {
    if (durationMs <= 0L || fraction.isNaN()) return null
    val totalSeconds = (fraction.coerceIn(0f, 1f) * durationMs).toLong() / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Short, since reading along the bar is the point; long enough that crossing it shows nothing. */
private const val SEEK_HINT_DELAY_MS = 150

/**
 * Desktop: the wheel over the scrubber seeks, a notch at a time ([AppShortcuts.SEEK_MS],
 * or a fifth of that with Ctrl), and commits once the wheel rests, so a fast spin
 * is one seek rather than twenty. Without a known length a notch is 1% of the track.
 */
@Composable
private fun Modifier.wheelSeek(
    fraction: Float,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val finish by rememberUpdatedState(onSeekFinished)
    val pending = remember { arrayOfNulls<Job>(1) }
    val target = remember { floatArrayOf(0f) }
    val step = if (durationMs > 0L) (AppShortcuts.SEEK_MS.toFloat() / durationMs).coerceAtMost(0.1f) else 0.01f
    return wheelAdjust(fraction, 0f..1f, step = step, fineStep = step / 5f) { f ->
        target[0] = f
        onSeek(f)
        pending[0]?.cancel()
        pending[0] = scope.launch {
            delay(WHEEL_COMMIT_MS)
            finish(target[0])
        }
    }
}

private const val WHEEL_COMMIT_MS = 350L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlainSlider(
    fraction: Float,
    tint: Color,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    durationMs: Long = 0L,
) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    // Hold the live drag value: onValueChangeFinished(fraction) committed the
    // composition-captured (stale) fraction, so a tap-to-seek snapped back.
    var latestSeek by remember { mutableFloatStateOf(fraction) }
    val thumbSize by animateDpAsState(
        targetValue = if (dragged) 18.dp else PlayerDesignTokens.ProgressThumbSize,
        label = "progressThumb",
    )
    val colors = SliderDefaults.colors(
        thumbColor = tint,
        activeTrackColor = tint,
        inactiveTrackColor = Color.White.copy(alpha = 0.20f),
    )
    var hoverX by remember { mutableFloatStateOf(0f) }
    var hoverWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // The slider runs its value between the thumb's centres at either end,
    // not edge to edge, so the hint maps the pointer the same way.
    PlayerTooltip(
        text = {
            val thumb = with(density) { thumbSize.toPx() }
            seekHint((hoverX - thumb / 2f) / (hoverWidth - thumb), durationMs)
        },
        delayMillis = SEEK_HINT_DELAY_MS,
        above = true,
    ) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { latestSeek = it; onSeek(it) },
            onValueChangeFinished = { onSeekFinished(latestSeek) },
            modifier = Modifier
                .fillMaxWidth()
                .trackHover { x, width -> hoverX = x; hoverWidth = width }
                .wheelSeek(fraction.coerceIn(0f, 1f), durationMs, onSeek, onSeekFinished),
            interactionSource = interaction,
            colors = colors,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = colors,
                    thumbSize = DpSize(thumbSize, thumbSize),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(PlayerDesignTokens.ProgressHeight),
                    colors = colors,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}
