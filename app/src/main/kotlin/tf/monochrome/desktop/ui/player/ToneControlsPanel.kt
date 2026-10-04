package tf.monochrome.desktop.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.desktop.audio.eq.AutoEqEngine
import tf.monochrome.desktop.domain.model.ToneControls
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R
import tf.monochrome.desktop.ui.input.adjustKeys
import tf.monochrome.desktop.ui.input.contextClick
import tf.monochrome.desktop.ui.input.focusRing
import tf.monochrome.desktop.ui.input.wheelAdjust
import java.awt.Cursor

// The two gain "O" knobs are ~20% larger than the "o" cutoff/Q knobs, and sit on
// the outer edges — bass far left, treble far right — mirroring each other:
// "Ooo   ooO".
private val KNOB_BIG = 56.dp
private val KNOB_SMALL = 40.dp

/**
 * Collapsible bass/treble tone panel for the Audio tools sheet: a live frequency-
 * response curve of the two shelves plus their knobs (gain / cutoff / Q). These
 * shelves are layered AFTER the AutoEQ correction (Desktop: in-app; there is no
 * system-wide effect).
 */
@Composable
internal fun ToneControlsPanel(
    tone: ToneControls,
    accent: Color,
    onChange: (ToneControls) -> Unit,
    modifier: Modifier = Modifier,
    // White suits the dark audio-tools sheet; pass onSurface on themed screens.
    contentColor: Color = Color.White,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val d = ToneControls.DEFAULT
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Collapsible header with the tone on/off switch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tone),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f),
            )
            // Chevron on the LEFT so the switch sits flush right, in line with the
            // AutoEQ toggle above it.
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) stringResource(R.string.tone_collapse) else stringResource(R.string.tone_expand),
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = tone.enabled,
                onCheckedChange = { onChange(tone.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = accent,
                ),
            )
        }
        AnimatedVisibility(visible = expanded) {
            // Dim the controls while the stage is bypassed; still adjustable.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.alpha(if (tone.enabled) 1f else 0.45f),
            ) {
                ToneCurve(
                    tone = tone,
                    accent = accent,
                    baseline = contentColor,
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                )
                // Mirrored layout: bass big knob hard left, treble big knob hard
                // right, the small cutoff/Q knobs facing each other in the middle.
                // Double-tap any knob to reset it to its default.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Knob(stringResource(R.string.tone_bass), "%+.0f dB".format(tone.bassGainDb), tone.bassGainDb,
                        ToneControls.GAIN_MIN..ToneControls.GAIN_MAX, accent, contentColor, KNOB_BIG,
                        d.bassGainDb, step = 0.5f, fineStep = 0.5f,
                        exactText = "%+.1f dB".format(tone.bassGainDb)) {
                        onChange(tone.copy(bassGainDb = snap(it, 0.5f)))
                    }
                    Knob(stringResource(R.string.tone_freq), "${tone.bassFreq.roundToInt()} Hz", tone.bassFreq,
                        ToneControls.BASS_FREQ_MIN..ToneControls.BASS_FREQ_MAX, accent, contentColor, KNOB_SMALL,
                        d.bassFreq, step = 5f, fineStep = 1f) {
                        onChange(tone.copy(bassFreq = it))
                    }
                    Knob("Q", "%.2f".format(tone.bassQ), tone.bassQ,
                        ToneControls.Q_MIN..ToneControls.Q_MAX, accent, contentColor, KNOB_SMALL,
                        d.bassQ, step = 0.05f, fineStep = 0.01f) {
                        onChange(tone.copy(bassQ = it))
                    }
                    Knob("Q", "%.2f".format(tone.trebleQ), tone.trebleQ,
                        ToneControls.Q_MIN..ToneControls.Q_MAX, accent, contentColor, KNOB_SMALL,
                        d.trebleQ, step = 0.05f, fineStep = 0.01f) {
                        onChange(tone.copy(trebleQ = it))
                    }
                    Knob(stringResource(R.string.tone_freq), "${(tone.trebleFreq / 1000f).format1()} kHz", tone.trebleFreq,
                        ToneControls.TREBLE_FREQ_MIN..ToneControls.TREBLE_FREQ_MAX, accent, contentColor, KNOB_SMALL,
                        d.trebleFreq, step = 100f, fineStep = 10f,
                        exactText = "${tone.trebleFreq.roundToInt()} Hz") {
                        onChange(tone.copy(trebleFreq = it))
                    }
                    Knob(stringResource(R.string.tone_treble), "%+.0f dB".format(tone.trebleGainDb), tone.trebleGainDb,
                        ToneControls.GAIN_MIN..ToneControls.GAIN_MAX, accent, contentColor, KNOB_BIG,
                        d.trebleGainDb, step = 0.5f, fineStep = 0.5f,
                        exactText = "%+.1f dB".format(tone.trebleGainDb)) {
                        onChange(tone.copy(trebleGainDb = snap(it, 0.5f)))
                    }
                }
            }
        }
    }
}

/** A live curve of the two shelves across 20 Hz–20 kHz (log x, ±12 dB y). */
@Composable
private fun ToneCurve(tone: ToneControls, accent: Color, baseline: Color, modifier: Modifier) {
    val bands = tone.toBands()
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        drawLine(
            color = baseline.copy(alpha = 0.14f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )
        val steps = 110
        val path = Path()
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val freq = 20f * 1000f.pow(fx) // 20..20000, log-spaced
            var db = 0f
            for (b in bands) db += AutoEqEngine.calculateBiquadResponse(freq, b)
            val y = midY - (db / 12f).coerceIn(-1f, 1f) * (midY * 0.9f)
            val x = fx * size.width
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A rotary knob: 270° sweep, vertical drag to change. [knobSize] sets its diameter. */
@Composable
private fun Knob(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    contentColor: Color,
    knobSize: Dp,
    resetValue: Float,
    /** Desktop: one wheel notch or arrow press; Ctrl moves [fineStep]. */
    step: Float = (range.endInclusive - range.start) / 48f,
    fineStep: Float = step / 10f,
    /**
     * Desktop: the value finer than [valueText] rounds it, for the hover hint.
     * The gain knobs move in half decibels and read in whole ones.
     */
    exactText: String? = null,
    onChange: (Float) -> Unit,
) {
    val span = range.endInclusive - range.start
    val curValue by rememberUpdatedState(value)
    val curOnChange by rememberUpdatedState(onChange)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var resetMenu by remember { mutableStateOf(false) }
    val resetHint = stringResource(R.string.knob_reset_hint_desktop)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(horizontal = 1.dp),
    ) {
        // Desktop: the double-click reset is otherwise found only by accident.
        PlayerTooltip(text = { listOfNotNull(exactText, resetHint).joinToString("\n") }) {
            Box(
                modifier = Modifier
                    .size(knobSize)
                    .focusRing(focused, CircleShape)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                    // Desktop: the wheel turns it, the arrows step it once Tab has
                    // reached it, right-click or the Menu key offers the reset the
                    // double-tap does, and Delete does it outright.
                    .wheelAdjust(value, range, step, fineStep) { curOnChange(it) }
                    .contextClick { resetMenu = true }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.Delete) return@onKeyEvent false
                        curOnChange(resetValue)
                        true
                    }
                    .adjustKeys(value, range, step, fineStep = fineStep, interactionSource = interaction) {
                        curOnChange(it)
                    }
                    // Double-tap resets the knob to its default.
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { curOnChange(resetValue) })
                    }
                    .pointerInput(Unit) {
                        // Built up apart from the value, which the gain knobs snap
                        // to 0.5 dB: built on the snapped value, a slow drag rounded
                        // back to where it started on every event and never moved.
                        var raw = 0f
                        detectDragGestures(onDragStart = { raw = curValue }) { change, drag ->
                            change.consume()
                            // ~150 px of vertical travel spans the whole range; up = up.
                            raw = (raw - drag.y / 150f * span)
                                .coerceIn(range.start, range.endInclusive)
                            curOnChange(raw)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(knobSize)) {
                    val stroke = 4.dp.toPx()
                    val radius = size.minDimension / 2f - stroke
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val startAngle = 135f
                    val sweep = 270f
                    drawArc(
                        color = contentColor.copy(alpha = 0.15f),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
                    drawArc(
                        color = accent,
                        startAngle = startAngle,
                        sweepAngle = sweep * frac,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    val ang = Math.toRadians((startAngle + sweep * frac).toDouble())
                    val px = center.x + (radius * cos(ang)).toFloat()
                    val py = center.y + (radius * sin(ang)).toFloat()
                    drawCircle(color = contentColor, radius = stroke * 0.7f, center = Offset(px, py))
                }
                DropdownMenu(expanded = resetMenu, onDismissRequest = { resetMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reset)) },
                        onClick = {
                            resetMenu = false
                            curOnChange(resetValue)
                        },
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
        Text(valueText, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}

private fun snap(value: Float, step: Float): Float = (value / step).roundToInt() * step

private fun Float.format1(): String = "%.1f".format(this)
