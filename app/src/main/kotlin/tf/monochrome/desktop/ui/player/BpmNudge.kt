package tf.monochrome.desktop.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R
import tf.monochrome.desktop.ui.input.focusRing
import tf.monochrome.desktop.ui.input.wheelAdjust
import java.awt.Cursor

/**
 * A spring-loaded BPM nudge — a DJ's pitch bend, laid out like the stepper
 * above it: − on the left, + on the right.
 *
 * Push the thumb off centre and the tempo keeps moving for as long as it is
 * held there, faster the further it is pushed (the rate grows with the square
 * of the push, so a light touch drifts a fraction of a BPM per second and a
 * full push sweeps [MAX_RATE] a second). Let go and it springs back to centre;
 * the tempo stays where it got to.
 *
 * The readout follows every frame, but the audio is told at most every
 * [APPLY_EVERY_NANOS] and only when the tempo moved — each speed change resets
 * the player's playback parameters, and doing that 60 times a second is what
 * would make the audio stutter.
 */
@Composable
fun BpmNudge(
    playedBpm: Float,
    accent: Color,
    onBpmChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb = remember { Animatable(0f) }          // -1..1, 0 at rest
    var held by remember { mutableStateOf(false) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    // The tempo being dragged, ahead of what the player has been told.
    var liveBpm by remember { mutableFloatStateOf(playedBpm) }
    if (!held) liveBpm = playedBpm
    val apply by rememberUpdatedState(onBpmChange)
    val scope = rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Where the grip ridges are, scrolled by the bend the way a platter's
    // edge moves under the hand.
    var ridgePhase by remember { mutableFloatStateOf(0f) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The push a held arrow key is giving, 0 when no key is bending.
    var keyPush by remember { mutableFloatStateOf(0f) }
    fun release() {
        held = false
        scope.launch { thumb.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }
    }
    // A key-up that goes to another control or window never reaches the
    // handler below, and a bend nobody lets go of runs to the end of the range.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, windowFocused) {
        if ((!focused || !windowFocused) && keyPush != 0f) {
            keyPush = 0f
            release()
        }
    }

    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        var last = 0L
        var lastApplied = 0L
        var appliedBpm = liveBpm
        // The platter has mass: the rate eases toward what the push asks for
        // rather than jumping to it.
        var rate = 0f
        var lastWhole = kotlin.math.floor(liveBpm)
        while (isActive && held) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = (now - last) / 1_000_000_000f
                val x = thumb.value
                val target = sign(x) * x * x * MAX_RATE
                rate += (target - rate) * (dt * PLATTER_RESPONSE).coerceAtMost(1f)
                liveBpm = (liveBpm + rate * dt).coerceIn(MIN_BPM, MAX_BPM)
                ridgePhase += rate * dt * RIDGES_PER_BPM
                // A detent click on every whole BPM crossed.
                val whole = kotlin.math.floor(liveBpm)
                if (whole != lastWhole) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    lastWhole = whole
                }
            }
            last = now
            if (now - lastApplied >= APPLY_EVERY_NANOS && abs(liveBpm - appliedBpm) >= 0.05f) {
                apply(liveBpm)
                appliedBpm = liveBpm
                lastApplied = now
            }
        }
        // Whatever the last frames moved, the player gets on release.
        if (abs(liveBpm - appliedBpm) >= 0.01f) apply(liveBpm)
    }

    val shape = RoundedCornerShape(50)
    val nudgeDescription = stringResource(R.string.bpm_nudge_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .focusRing(focused, shape)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .semantics { contentDescription = nudgeDescription }
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            // Desktop: each wheel notch nudges the tempo by a tenth of a BPM.
            .wheelAdjust(liveBpm, MIN_BPM..MAX_BPM, step = WHEEL_STEP_BPM) { apply(it) }
            // Desktop: a held arrow pushes the thumb as a held finger does, and
            // letting the key go springs it back; Shift pushes it all the way.
            // The key-up is handled too, because it is what ends the push.
            // Ctrl and Alt arrows are left to the app (skip, back, forward).
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1f
                    Key.DirectionRight -> 1f
                    else -> return@onKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.isCtrlPressed || event.isAltPressed) return@onKeyEvent false
                        val push = direction * if (event.isShiftPressed) 1f else KEY_PUSH
                        // Auto-repeat sends the same press again; only a new push moves the thumb.
                        if (push != keyPush) {
                            keyPush = push
                            held = true
                            scope.launch { thumb.animateTo(push) }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (keyPush == 0f || sign(keyPush) != direction) return@onKeyEvent false
                        keyPush = 0f
                        release()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) {
                widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { held = true },
                    onDragEnd = {
                        held = false
                        scope.launch { thumb.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) }
                    },
                    onDragCancel = {
                        held = false
                        scope.launch { thumb.animateTo(0f) }
                    },
                ) { change, dx ->
                    change.consume()
                    val travel = (widthPx / 2f - TRAVEL_INSET_PX).coerceAtLeast(1f)
                    scope.launch { thumb.snapTo((thumb.value + dx / travel).coerceIn(-1f, 1f)) }
                }
            }
            // Desktop: a mouse held still on the strip bends toward where it
            // is held, harder the further from centre, and the thumb then
            // follows it. One that moves at once is the drag above. After it in
            // the chain, so it sees the press first and, once holding, consumes
            // the moves that would start that drag.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type != PointerType.Mouse) return@awaitEachGesture
                    val letGoOrMoved = withTimeoutOrNull(MOUSE_HOLD_MS) {
                        var gone = false
                        while (!gone) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            gone = change == null || !change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        }
                    }
                    if (letGoOrMoved != null) return@awaitEachGesture
                    val travel = (size.width / 2f - TRAVEL_INSET_PX).coerceAtLeast(1f)
                    fun pushAt(x: Float) = ((x - size.width / 2f) / travel).coerceIn(-1f, 1f)
                    held = true
                    scope.launch { thumb.animateTo(pushAt(down.position.x)) }
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed && change.position != change.previousPosition) {
                                scope.launch { thumb.snapTo(pushAt(change.position.x)) }
                            }
                            event.changes.forEach { it.consume() }
                        } while (change != null && change.pressed)
                    } finally {
                        release()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cy = size.height / 2f
            val half = size.width / 2f
            // Track, then the fill from centre to the thumb in the accent.
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(cy, cy))
            // Grip ridges, like the edge of a platter, sliding with the bend.
            val gap = 14f
            val shift = ((ridgePhase * gap) % gap + gap) % gap
            var rx = shift
            while (rx < size.width) {
                drawLine(Color.White.copy(alpha = 0.07f), Offset(rx, cy - 10f), Offset(rx, cy + 10f), 2f)
                rx += gap
            }
            val tx = half + thumb.value * (half - TRAVEL_INSET_PX)
            val left = minOf(half, tx)
            drawRoundRect(
                accent.copy(alpha = 0.35f),
                topLeft = Offset(left, cy - 6f),
                size = Size(abs(tx - half), 12f),
                cornerRadius = CornerRadius(6f, 6f),
            )
            drawLine(Color.White.copy(alpha = 0.35f), Offset(half, cy - 14f), Offset(half, cy + 14f), 2f)
            drawCircle(accent, radius = 17f + 4f * abs(thumb.value), center = Offset(tx, cy))
        }
        Text("−", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
        Text("+", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp))
        if (held) {
            Text(
                SpeedUnitFormat.bpm(liveBpm),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            )
        }
    }
}

private object SpeedUnitFormat {
    fun bpm(v: Float) = tf.monochrome.desktop.audio.SpeedUnit.formatBpm(v)
}

/** BPM a second at full push. */
private const val MAX_RATE = 12f
private const val MIN_BPM = 20f
private const val MAX_BPM = 400f
private const val APPLY_EVERY_NANOS = 80_000_000L
private const val TRAVEL_INSET_PX = 60f
/** How fast the bend rate catches up with the push, per second. */
private const val PLATTER_RESPONSE = 6f
/** Ridges scrolled per BPM bent. */
private const val RIDGES_PER_BPM = 3f
/** Desktop: the push a held arrow key gives, of a full one. */
private const val KEY_PUSH = 0.6f
/** Desktop: how long a mouse press stays put before it counts as a hold. */
private const val MOUSE_HOLD_MS = 180L
/** Desktop: BPM per wheel notch; Ctrl+wheel moves a tenth of it. */
private const val WHEEL_STEP_BPM = 0.1f

/**
 * Type the song's own tempo: opened by long-pressing the BPM number, for when
 * the measurement got it wrong. The speed is left alone, so the played tempo
 * moves with it. Decimal keyboard, focused on open, Done sets it. An ordinary
 * dialog — it holds no glass.
 */
@Composable
fun BpmEntryDialog(current: Float, onDismiss: () -> Unit, onSet: (Float) -> Unit) {
    var text by remember { mutableStateOf(String.format(java.util.Locale.US, "%.1f", current)) }
    val parsed = text.replace(',', '.').toFloatOrNull()?.takeIf { it in MIN_BPM..MAX_BPM }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bpm_song_tempo_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(R.string.bpm_song_tempo_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                    singleLine = true,
                    suffix = { Text("BPM") },
                    isError = parsed == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { parsed?.let(onSet) },
                    ),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) { Text(stringResource(R.string.action_set)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
