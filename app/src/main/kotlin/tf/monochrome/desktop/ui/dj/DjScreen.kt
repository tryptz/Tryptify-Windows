@file:OptIn(ExperimentalLayoutApi::class)

package tf.monochrome.desktop.ui.dj

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.desktop.R
import tf.monochrome.desktop.dj.Deck
import tf.monochrome.desktop.dj.DjEngine
import tf.monochrome.desktop.dj.DjMath
import tf.monochrome.desktop.dj.controller.ControllerManager
import tf.monochrome.desktop.dj.controller.MidiAction
import tf.monochrome.desktop.dj.controller.MidiProfile
import tf.monochrome.desktop.dj.controller.MidiTarget
import tf.monochrome.desktop.dj.controller.MixxxImport
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.res.StringKey
import tf.monochrome.desktop.ui.components.PressableGlass
import tf.monochrome.desktop.ui.components.SearchOverlay
import tf.monochrome.desktop.ui.components.liquidGlass
import tf.monochrome.desktop.ui.mixer.FLKnobControl
import tf.monochrome.desktop.ui.mixer.GlassChoiceChip
import tf.monochrome.desktop.ui.mixer.VuMeter
import tf.monochrome.desktop.ui.navigation.LocalBottomChromeInset
import tf.monochrome.desktop.ui.navigation.popBackStackSafe
import tf.monochrome.desktop.ui.player.DynamicAlbumGlow
import tf.monochrome.desktop.ui.player.PlayerDesignTokens
import tf.monochrome.desktop.ui.player.dithered
import tf.monochrome.desktop.ui.player.dynamicPlayerBackground
import tf.monochrome.desktop.ui.settings.sliderWheel
import tf.monochrome.desktop.ui.theme.MonoDimens

// Desktop: the DJ console has no Android counterpart; this screen is desktop-only.

/** Deck A blue, deck B violet: the same pair the Mixer gives Mix A and Mix B. */
private val DeckColors = listOf(Color(0xFF6EA8FF), Color(0xFFB98CFF))
private val DeckLetters = listOf("A", "B")

/** Wide enough for deck, mixer, deck side by side; below it they stack. */
private val WideLayout = 1100.dp
private val MixerWidth = 330.dp

/**
 * A chip as wide as its label. [GlassChoiceChip] fills the width it is given,
 * which in a Row or FlowRow is the whole row; asking for its intrinsic width
 * sizes it to its text instead.
 */
private val Hug = Modifier.width(IntrinsicSize.Max)

private val FaderHeight = 150.dp

/**
 * Two decks around a two-channel mixer, the FX units under them, the browser
 * and the controllers. Everything here is also on a controller, and both
 * drive the same engine: what a knob on the hardware does shows here at once.
 */
@Composable
fun DjScreen(navController: NavController, viewModel: DjViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val running by viewModel.running.collectAsStateWithLifecycle()
    val decks by viewModel.decks.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val fx by viewModel.fx.collectAsStateWithLifecycle()
    val fxSlots by viewModel.fxSlots.collectAsStateWithLifecycle()
    val crossfader by viewModel.crossfader.collectAsStateWithLifecycle()

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerShape = RoundedCornerShape(
        bottomStart = PlayerDesignTokens.GlassCornerLarge,
        bottomEnd = PlayerDesignTokens.GlassCornerLarge,
    )
    val haze = rememberHazeState()
    val accent = colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        // The backdrop the panels' glass blurs: a sibling of them, never their parent.
        Box(Modifier.matchParentSize().hazeSource(haze)) {
            Box(Modifier.matchParentSize().dithered().background(dynamicPlayerBackground(accent)))
            DynamicAlbumGlow(accent)
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 18.dp, shape = headerShape, clip = false)
                    .background(colorScheme.surface.copy(alpha = 0.90f), headerShape)
                    .liquidGlass(
                        shape = headerShape,
                        tintAlpha = PlayerDesignTokens.GlassTintMedium,
                        borderAlpha = PlayerDesignTokens.GlassTintSoft,
                    )
                    .padding(top = statusBarPadding)
                    .padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GlassIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        description = stringResource(R.string.settings_back),
                        onClick = { navController.popBackStackSafe() },
                        size = 38.dp,
                    )
                    Text(
                        text = stringResource(R.string.dj_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    GlassChoiceChip(
                        label = stringResource(if (running) R.string.dj_console_on else R.string.dj_console_off),
                        selected = running,
                        accent = accent,
                        onClick = { viewModel.toggleRunning() },
                        modifier = Hug,
                    )
                }
                Text(
                    text = stringResource(R.string.dj_console_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
                )
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= WideLayout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MonoDimens.spacingMd),
                    verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
                ) {
                    val deck = @Composable { i: Int, modifier: Modifier ->
                        DeckPanel(i, decks[i], load[i], viewModel, haze, modifier)
                    }
                    val mixer = @Composable { modifier: Modifier ->
                        MixerPanel(decks, crossfader, viewModel, haze, modifier)
                    }
                    val fxUnit = @Composable { i: Int, modifier: Modifier ->
                        FxPanel(i, fx[i], fxSlots[i], viewModel, haze, modifier)
                    }
                    if (wide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd)) {
                            deck(0, Modifier.weight(1f))
                            mixer(Modifier.width(MixerWidth))
                            deck(1, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd)) {
                            fxUnit(0, Modifier.weight(1f))
                            fxUnit(1, Modifier.weight(1f))
                        }
                    } else {
                        deck(0, Modifier.fillMaxWidth())
                        deck(1, Modifier.fillMaxWidth())
                        mixer(Modifier.fillMaxWidth())
                        fxUnit(0, Modifier.fillMaxWidth())
                        fxUnit(1, Modifier.fillMaxWidth())
                    }
                    BrowserPanel(viewModel, Modifier.fillMaxWidth().height(420.dp))
                    ControllersPanel(viewModel, haze, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(LocalBottomChromeInset.current))
                }
            }
        }
    }
}

// ── A deck ─────────────────────────────────────────────────────────────

@Composable
private fun DeckPanel(
    index: Int,
    state: DjEngine.DeckState,
    load: DjEngine.LoadState,
    vm: DjViewModel,
    haze: HazeState,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val color = DeckColors[index]
    var clearing by remember { mutableStateOf(false) }

    Panel(haze, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            Text(
                text = stringResource(if (index == 0) R.string.dj_deck_a else R.string.dj_deck_b),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            if (state.leader) Tag(stringResource(R.string.dj_leader), color)
            Spacer(Modifier.weight(1f))
            if (state.bpm > 0.0) {
                Text(
                    text = stringResource(R.string.dj_bpm, "%.1f".format(state.bpm)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
            }
            Text(
                text = "%+.1f".format((state.speed - 1.0) * 100) + "%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
            )
        }

        // What is on it, or on its way.
        Column(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
            when {
                load.loading -> Text(stringResource(R.string.dj_loading, load.title), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                load.error != null -> Text(stringResource(R.string.dj_load_failed, load.error), style = MaterialTheme.typography.bodyMedium, color = cs.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                !state.loaded -> Text(stringResource(R.string.dj_deck_empty), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                else -> {
                    Text(state.title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.artist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.loaded && state.truncated) {
                Text(stringResource(R.string.dj_truncated, clock(state.lengthSeconds)), style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
            }
            if (state.loaded && state.bpm <= 0.0) {
                Text(stringResource(R.string.dj_no_bpm), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }

        // Where it is.
        Row(Modifier.fillMaxWidth()) {
            Text(clock(state.positionSeconds), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            Text("-" + clock(state.lengthSeconds - state.positionSeconds), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
        }
        val fraction = if (state.lengthSeconds > 0.0) (state.positionSeconds / state.lengthSeconds).toFloat().coerceIn(0f, 1f) else 0f
        val seekLabel = stringResource(R.string.dj_seek)
        Slider(
            value = fraction,
            onValueChange = { vm.seek(index, it) },
            enabled = state.loaded,
            colors = deckSliderColors(color),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = seekLabel }
                .sliderWheel(value = fraction, range = 0f..1f, onValueChange = { vm.seek(index, it) }),
        )
        // Still decoding: how much of it can be played already.
        if (state.loaded && !state.complete) {
            LinearProgressIndicator(
                progress = { state.decoded },
                color = color.copy(alpha = 0.6f),
                trackColor = color.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }

        // Tempo: the fader within its range, and the range.
        val fader = DjMath.faderFor(state.speed, state.tempoRange)
        val tempoLabel = stringResource(R.string.dj_action_tempo)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            Text(tempoLabel, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Slider(
                value = fader,
                onValueChange = { vm.setTempo(index, it) },
                valueRange = -1f..1f,
                colors = deckSliderColors(color),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = tempoLabel }
                    .sliderWheel(value = fader, range = -1f..1f, steps = 199, onValueChange = { vm.setTempo(index, it) }),
            )
            GlassChoiceChip(
                label = "±" + (state.tempoRange * 100).roundToInt() + "%",
                selected = false,
                accent = color,
                description = stringResource(R.string.dj_tempo_range),
                onClick = {
                    val ranges = DjMath.TEMPO_RANGES
                    val at = ranges.indexOfFirst { it >= state.tempoRange - 1e-4f }
                    vm.setTempoRange(index, ranges[(at + 1) % ranges.size])
                },
                modifier = Hug,
            )
        }

        // Transport.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassChoiceChip(
                label = stringResource(R.string.dj_action_cue),
                selected = false,
                accent = color,
                // Held, not clicked: down sets or previews, up returns. Seen on
                // the way in (Initial) so the glass's own press still plays.
                onClick = {},
                modifier = Hug.pointerInput(index) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        vm.cueDown(index)
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        vm.cueUp(index)
                    }
                },
            )
            GlassChoiceChip(stringResource(R.string.dj_action_play), state.playing, color, { vm.togglePlay(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_sync), false, color, { vm.sync(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_sync_lock), state.syncLock, color, { vm.toggleSyncLock(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_keylock), state.keylock, color, { vm.toggleKeylock(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_quantize), state.quantize, color, { vm.toggleQuantize(index) }, Hug)
        }

        // Hot cues: lit where set. With the clear toggle on, the next one pressed is cleared.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(Deck.HOT_CUES) { slot ->
                GlassChoiceChip(
                    label = "${slot + 1}",
                    selected = state.hotCues.getOrNull(slot) != null,
                    accent = if (clearing) cs.error else color,
                    description = stringResource(R.string.dj_hot_cue_n, slot + 1),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (clearing) {
                            vm.clearHotCue(index, slot)
                            clearing = false
                        } else {
                            vm.hotCue(index, slot)
                        }
                    },
                )
            }
            GlassIcon(
                icon = Icons.Default.Backspace,
                description = stringResource(R.string.dj_action_clear_hot_cue),
                onClick = { clearing = !clearing },
                active = clearing,
                accent = cs.error,
            )
        }

        // Loops.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(Icons.Default.Remove, stringResource(R.string.dj_action_loop_halve), { vm.resizeLoop(index, -1) })
            GlassChoiceChip(
                label = stringResource(R.string.dj_action_loop) + " " + DjMath.beatsLabel(state.loopBeats),
                selected = state.loopActive,
                accent = color,
                onClick = { vm.toggleLoop(index) },
                modifier = Hug,
            )
            GlassIcon(Icons.Default.Add, stringResource(R.string.dj_action_loop_double), { vm.resizeLoop(index, 1) })
            GlassChoiceChip(stringResource(R.string.dj_action_loop_in), false, color, { vm.loopIn(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_loop_out), false, color, { vm.loopOut(index) }, Hug)
            GlassChoiceChip(stringResource(R.string.dj_action_reloop), false, color, { vm.reloop(index) }, Hug)
        }

        // Beat jumps, and the eject.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GlassIcon(Icons.Default.FastRewind, stringResource(R.string.dj_action_jump_back), { vm.beatJump(index, forward = false) })
            GlassIcon(Icons.Default.ChevronLeft, stringResource(R.string.dj_jump_smaller), { vm.resizeJump(index, -1) })
            Text(
                text = DjMath.beatsLabel(state.jumpBeats),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
                modifier = Modifier.width(36.dp),
                maxLines = 1,
            )
            GlassIcon(Icons.Default.ChevronRight, stringResource(R.string.dj_jump_larger), { vm.resizeJump(index, 1) })
            GlassIcon(Icons.Default.FastForward, stringResource(R.string.dj_action_jump_forward), { vm.beatJump(index, forward = true) })
            Spacer(Modifier.weight(1f))
            GlassIcon(
                icon = Icons.Default.Eject,
                description = stringResource(R.string.dj_action_eject),
                onClick = { vm.eject(index) },
                enabled = state.loaded && !state.playing,
            )
        }
    }
}

// ── The mixer ──────────────────────────────────────────────────────────

@Composable
private fun MixerPanel(
    decks: List<DjEngine.DeckState>,
    crossfader: Float,
    vm: DjViewModel,
    haze: HazeState,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Panel(haze, modifier) {
        PanelTitle(stringResource(R.string.dj_mixer))
        // Mirrored, so the two EQ columns meet in the middle as on a real mixer.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ChannelKnobs(0, decks[0], vm, mirrored = false)
            ChannelKnobs(1, decks[1], vm, mirrored = true)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChannelFader(0, decks[0], vm)
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = MonoDimens.spacingSm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
            ) {
                Text(stringResource(R.string.dj_phase), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                PhaseMeter(decks, Modifier.fillMaxWidth().height(30.dp))
            }
            ChannelFader(1, decks[1], vm, mirrored = true)
        }

        val crossfaderLabel = stringResource(R.string.dj_action_crossfader)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            Text("A", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = DeckColors[0])
            Slider(
                value = crossfader,
                onValueChange = vm::setCrossfader,
                valueRange = -1f..1f,
                colors = deckSliderColors(cs.onSurface),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = crossfaderLabel }
                    .sliderWheel(value = crossfader, range = -1f..1f, onValueChange = vm::setCrossfader),
            )
            Text("B", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = DeckColors[1])
        }
    }
}

/**
 * A channel's gain and filter, and its three EQ bands, in dB. The engine's
 * knobs are 0..1 with unity in the middle, as a controller's are; turned in dB
 * here, so 0 dB sits where the range puts it, not at twelve o'clock.
 */
@Composable
private fun ChannelKnobs(index: Int, state: DjEngine.DeckState, vm: DjViewModel, mirrored: Boolean) {
    val color = DeckColors[index]
    val knob = Modifier.width(72.dp)
    val outer = @Composable {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FLKnobControl(
                label = stringResource(R.string.dj_action_trim),
                value = DjMath.trimDb(state.trim),
                min = DjMath.TRIM_MIN_DB, max = DjMath.TRIM_MAX_DB, unit = "dB", color = color,
                onValueChange = { vm.setTrim(index, DjMath.trimKnob(it)) },
                modifier = knob, default = 0f,
            )
            FLKnobControl(
                label = stringResource(R.string.dj_action_filter),
                value = state.filter,
                min = -1f, max = 1f, unit = "", color = color,
                onValueChange = { vm.setFilter(index, it) },
                modifier = knob, default = 0f,
            )
        }
    }
    val inner = @Composable {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for ((label, value, set) in listOf(
                Triple(R.string.dj_action_eq_high, state.eqHigh, vm::setEqHigh),
                Triple(R.string.dj_action_eq_mid, state.eqMid, vm::setEqMid),
                Triple(R.string.dj_action_eq_low, state.eqLow, vm::setEqLow),
            )) {
                FLKnobControl(
                    label = stringResource(label),
                    value = DjMath.eqDb(value),
                    min = DjMath.EQ_MIN_DB, max = DjMath.EQ_MAX_DB, unit = "dB", color = color,
                    onValueChange = { set(index, DjMath.eqKnob(it)) },
                    modifier = knob, default = 0f,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs)) {
        if (mirrored) { inner(); outer() } else { outer(); inner() }
    }
}

/** A channel's volume fader and its level, the meter on the inside. */
@Composable
private fun ChannelFader(index: Int, state: DjEngine.DeckState, vm: DjViewModel, mirrored: Boolean = false) {
    val color = DeckColors[index]
    val levelDb = if (state.peak > 0f) 20f * log10(state.peak) else -60f
    val label = stringResource(R.string.dj_action_volume) + " " + DeckLetters[index]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val fader = @Composable {
            VerticalSlider(
                value = state.volume,
                onValueChange = { vm.setVolume(index, it) },
                color = color,
                description = label,
                modifier = Modifier.height(FaderHeight),
            )
        }
        val meter = @Composable { VuMeter(levelDb = levelDb, muted = false, accentColor = color, modifier = Modifier.height(FaderHeight)) }
        if (mirrored) { meter(); fader() } else { fader(); meter() }
    }
}

/**
 * A Slider stood on end, bottom to top. Rotated after layout, so it is
 * measured with its width and height swapped and placed back into its box.
 */
@Composable
private fun VerticalSlider(value: Float, onValueChange: (Float) -> Unit, color: Color, description: String, modifier: Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        colors = deckSliderColors(color),
        modifier = modifier
            .semantics { contentDescription = description }
            .sliderWheel(value = value, range = 0f..1f, onValueChange = onValueChange)
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
            },
    )
}

/**
 * Where each deck is in its beat, a lane each: lined up, the two bars rise
 * and fall together. Empty for a deck with no grid.
 */
@Composable
private fun PhaseMeter(decks: List<DjEngine.DeckState>, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val desc = stringResource(R.string.dj_phase)
    Canvas(modifier.semantics { contentDescription = desc }) {
        val gap = 4.dp.toPx()
        val lane = (size.height - gap) / 2f
        val r = CornerRadius(lane / 3f)
        decks.take(2).forEachIndexed { i, d ->
            val top = i * (lane + gap)
            drawRoundRect(track, Offset(0f, top), Size(size.width, lane), r)
            val phase = d.beatPhase ?: return@forEachIndexed
            drawRoundRect(DeckColors[i], Offset(0f, top), Size(size.width * phase.toFloat(), lane), r)
        }
    }
}

// ── An FX unit ─────────────────────────────────────────────────────────

@Composable
private fun FxPanel(
    unit: Int,
    state: DjEngine.FxUnit,
    slots: List<DjEngine.FxSlot?>,
    vm: DjViewModel,
    haze: HazeState,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val color = DeckColors[unit]
    val letter = DeckLetters[unit]
    Panel(haze, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.dj_fx_unit, letter),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.weight(1f),
            )
            GlassChoiceChip(
                label = stringResource(R.string.dj_fx_assigned, letter),
                selected = state.assigned,
                accent = color,
                description = stringResource(R.string.dj_action_fx_assign),
                onClick = { vm.toggleFxAssign(unit) },
                modifier = Hug,
            )
        }
        val amountLabel = stringResource(R.string.dj_action_fx_amount)
        slots.forEachIndexed { slot, effect ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                GlassChoiceChip(
                    label = effect?.name ?: stringResource(R.string.dj_fx_empty),
                    selected = effect?.on == true,
                    accent = color,
                    description = stringResource(R.string.dj_fx_slot, slot + 1),
                    onClick = { vm.toggleFx(unit, slot) },
                    modifier = Modifier.width(150.dp),
                )
                val amount = state.amounts[slot]
                Slider(
                    value = amount,
                    onValueChange = { vm.setFxAmount(unit, slot, it) },
                    enabled = effect != null,
                    colors = deckSliderColors(color),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = amountLabel }
                        .sliderWheel(value = amount, range = 0f..1f, onValueChange = { vm.setFxAmount(unit, slot, it) }),
                )
            }
        }
        val mixLabel = stringResource(R.string.dj_action_fx_mix)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            Text(mixLabel, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.width(150.dp))
            Slider(
                value = state.mix,
                onValueChange = { vm.setFxMix(unit, it) },
                colors = deckSliderColors(color),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = mixLabel }
                    .sliderWheel(value = state.mix, range = 0f..1f, onValueChange = { vm.setFxMix(unit, it) }),
            )
        }
        Text(stringResource(R.string.dj_fx_hint, "FX $letter"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

// ── The browser ────────────────────────────────────────────────────────

@Composable
private fun BrowserPanel(vm: DjViewModel, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val browser by vm.browser.collectAsStateWithLifecycle()
    val crate by vm.crate.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // A controller's browse knob moves the selection: keep it in sight.
    LaunchedEffect(browser.selected, browser.tracks.size) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val i = browser.selected
        if (browser.tracks.isNotEmpty() && visible.isNotEmpty() && (i <= visible.first().index || i >= visible.last().index)) {
            listState.animateScrollToItem((i - 1).coerceAtLeast(0))
        }
    }

    val loadTo = { deck: Int, track: Track ->
        if (!vm.loadTo(deck, track)) {
            Toast.makeText(context, context.getString(R.string.dj_load_refused, DeckLetters[deck]), Toast.LENGTH_SHORT).show()
        }
    }

    // The overlay brings its own haze source, so this pane blurs nothing behind it.
    Box(modifier.liquidGlass(shape = MonoDimens.shapeLg)) {
        SearchOverlay(
            open = true,
            query = query,
            onQueryChange = vm::setQuery,
            placeholder = stringResource(R.string.dj_browser_search),
            onClose = null,
            autoFocus = false,
            scrollState = listState,
            barContent = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (c in DjViewModel.Crate.entries) {
                        GlassChoiceChip(
                            label = stringResource(crateLabel(c)),
                            selected = c == crate,
                            accent = cs.primary,
                            onClick = { vm.setCrate(c) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        ) { topInset ->
            if (browser.tracks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(top = topInset), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.dj_browser_empty), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topInset, bottom = MonoDimens.spacingSm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(browser.tracks, key = { i, t -> "$i:${t.id}" }) { i, track ->
                        BrowserRow(
                            track = track,
                            selected = i == browser.selected,
                            onSelect = { vm.select(i) },
                            onLoad = { deck -> loadTo(deck, track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(track: Track, selected: Boolean, onSelect: () -> Unit, onLoad: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MonoDimens.listRowHeight)
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = MonoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.displayArtist, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.duration > 0) {
            Text(clock(track.duration.toDouble()), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
        }
        for (deck in 0..1) {
            GlassChoiceChip(
                label = DeckLetters[deck],
                selected = false,
                accent = DeckColors[deck],
                description = stringResource(if (deck == 0) R.string.dj_load_to_a else R.string.dj_load_to_b),
                onClick = { onLoad(deck) },
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

private fun crateLabel(crate: DjViewModel.Crate): StringKey = when (crate) {
    DjViewModel.Crate.QUEUE -> R.string.dj_crate_queue
    DjViewModel.Crate.LOCAL -> R.string.dj_crate_local
    DjViewModel.Crate.LIKED -> R.string.dj_crate_liked
    DjViewModel.Crate.HISTORY -> R.string.dj_crate_history
}

// ── Controllers ────────────────────────────────────────────────────────

@Composable
private fun ControllersPanel(vm: DjViewModel, haze: HazeState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controllers by vm.controllerList.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val logFile by vm.logFile.collectAsStateWithLifecycle()
    val invertTempo by vm.invertTempo.collectAsStateWithLifecycle()
    val logReports by vm.logReports.collectAsStateWithLifecycle()

    var learnPort by remember { mutableStateOf<String?>(null) }
    var resetPort by remember { mutableStateOf<String?>(null) }
    var importPort by remember { mutableStateOf<String?>(null) }
    var shownPort by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val port = importPort
        if (uri == null || port == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val r = vm.importMixxx(port, input)
                        context.getString(R.string.dj_import_done, r.profile.bindings.size, r.scripted, r.unsupported)
                    }
                } catch (e: MixxxImport.FormatException) {
                    context.getString(R.string.dj_import_failed, e.message.orEmpty())
                } catch (e: java.io.IOException) {
                    context.getString(R.string.dj_import_failed, e.message.orEmpty())
                }
            }
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Panel(haze, modifier) {
        PanelTitle(stringResource(R.string.dj_controllers))
        if (controllers.isEmpty()) {
            Text(stringResource(R.string.dj_controllers_none), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        for (c in controllers) {
            ControllerCard(
                status = c,
                profile = profiles[c.name],
                bindingsShown = shownPort == c.name,
                onLearn = { learnPort = c.name },
                onImport = {
                    importPort = c.name
                    importLauncher.launch("text/xml")
                },
                onReset = { resetPort = c.name },
                onToggleBindings = { shownPort = if (shownPort == c.name) null else c.name },
                vm = vm,
            )
        }

        SwitchRow(
            title = stringResource(R.string.dj_invert_tempo),
            description = stringResource(R.string.dj_invert_tempo_desc),
            checked = invertTempo,
            onCheckedChange = vm::setInvertTempo,
        )
        SwitchRow(
            title = stringResource(R.string.dj_log_reports),
            description = stringResource(R.string.dj_log_reports_desc),
            checked = logReports,
            onCheckedChange = vm::setLogReports,
        )
        val file = logFile
        if (logReports && file != null) {
            Text(stringResource(R.string.dj_log_file, file.path), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
        }
    }

    learnPort?.let { port ->
        LearnDialog(port, vm, onDismiss = {
            vm.cancelLearn()
            learnPort = null
        })
    }
    resetPort?.let { port ->
        AlertDialog(
            onDismissRequest = { resetPort = null },
            title = { Text(stringResource(R.string.dj_reset_mapping_title, port)) },
            text = { Text(stringResource(R.string.dj_reset_mapping_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetMapping(port)
                    resetPort = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { resetPort = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ControllerCard(
    status: ControllerManager.Status,
    profile: MidiProfile?,
    bindingsShown: Boolean,
    onLearn: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onToggleBindings: () -> Unit,
    vm: DjViewModel,
) {
    val cs = MaterialTheme.colorScheme
    val stateColor = when (status.state) {
        ControllerManager.State.CONNECTED -> cs.primary
        ControllerManager.State.UNMAPPED -> cs.onSurfaceVariant
        ControllerManager.State.BUSY, ControllerManager.State.FAILED -> cs.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeMd)
            .background(cs.onSurface.copy(alpha = 0.04f))
            .padding(MonoDimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
            Tag(stringResource(if (status.midi) R.string.dj_controller_midi else R.string.dj_controller_hid), cs.secondary)
            Text(status.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Text(
            text = stringResource(
                when (status.state) {
                    ControllerManager.State.CONNECTED -> R.string.dj_controller_connected
                    ControllerManager.State.BUSY -> R.string.dj_controller_busy
                    ControllerManager.State.FAILED -> R.string.dj_controller_failed
                    ControllerManager.State.UNMAPPED -> R.string.dj_controller_unmapped
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
        )
        status.mapping?.let { Text(stringResource(R.string.dj_controller_mapping, it), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        if (!status.verified) {
            Text(stringResource(R.string.dj_controller_unverified), style = MaterialTheme.typography.bodySmall, color = cs.tertiary)
        }
        // Only a MIDI port is mapped here: a HID controller's mapping is its driver.
        if (status.midi) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassChoiceChip(stringResource(R.string.dj_learn), false, cs.primary, onLearn, Hug)
                GlassChoiceChip(stringResource(R.string.dj_import_mixxx), false, cs.primary, onImport, Hug)
                GlassChoiceChip(stringResource(R.string.dj_reset_mapping), false, cs.error, onReset, Hug)
                if (profile != null && profile.bindings.isNotEmpty()) {
                    GlassChoiceChip(stringResource(R.string.dj_mapping_controls, profile.bindings.size), bindingsShown, cs.primary, onToggleBindings, Hug)
                }
            }
            if (bindingsShown && profile != null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(profile.bindings) { b ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(MonoDimens.listRowHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
                        ) {
                            Text(b.key.toString(), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant, modifier = Modifier.width(130.dp))
                            Text(targetLabel(b.target), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            GlassIcon(Icons.Default.Close, stringResource(R.string.dj_forget), { vm.forget(status.name, b) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Learning a control: what it should do (and on which deck or unit, and which
 * slot), then Listen, and the next control moved on [port] is bound to it.
 */
@Composable
private fun LearnDialog(port: String, vm: DjViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val learning by vm.learning.collectAsStateWithLifecycle()
    var action by remember { mutableStateOf<MidiAction?>(null) }
    var unit by remember { mutableIntStateOf(0) }
    var slot by remember { mutableIntStateOf(0) }
    var listened by remember { mutableStateOf(false) }
    val waiting = learning?.port == port

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dj_learn_title, port)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
            ) {
                val current = learning
                Text(
                    text = when {
                        waiting && current != null -> stringResource(R.string.dj_learn_waiting, targetLabel(current.target))
                        listened -> stringResource(R.string.dj_learn_done)
                        else -> stringResource(R.string.dj_learn_pick)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (waiting) cs.primary else cs.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in MidiAction.entries) {
                        GlassChoiceChip(
                            label = stringResource(actionLabel(a)),
                            selected = a == action,
                            accent = cs.primary,
                            onClick = {
                                action = a
                                slot = slot.coerceAtMost(slotCount(a) - 1)
                                listened = false
                            },
                            modifier = Hug,
                        )
                    }
                }
                val a = action
                if (a != null && a.scope != MidiAction.Scope.GLOBAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (u in 0..1) {
                            GlassChoiceChip(
                                label = if (a.scope == MidiAction.Scope.FX) stringResource(R.string.dj_fx_unit, DeckLetters[u])
                                else stringResource(if (u == 0) R.string.dj_deck_a else R.string.dj_deck_b),
                                selected = u == unit,
                                accent = DeckColors[u],
                                onClick = { unit = u },
                                modifier = Hug,
                            )
                        }
                    }
                }
                if (a != null && a.slotted) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(slotCount(a)) { s ->
                            GlassChoiceChip(
                                label = "${s + 1}",
                                selected = s == slot,
                                accent = cs.primary,
                                onClick = { slot = s },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = action != null && !waiting,
                onClick = {
                    val a = action ?: return@TextButton
                    vm.learn(port, MidiTarget(a, if (a.scope == MidiAction.Scope.GLOBAL) 0 else unit, if (a.slotted) slot else 0))
                    listened = true
                },
            ) { Text(stringResource(R.string.dj_learn_listen)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun slotCount(action: MidiAction): Int = when {
    !action.slotted -> 1
    action.scope == MidiAction.Scope.FX -> DjEngine.FX_SLOTS
    else -> Deck.HOT_CUES
}

/** "Hot cue 3 · Deck B", "Effect 2 · FX A", "Crossfader". */
@Composable
private fun targetLabel(target: MidiTarget): String {
    val a = target.action
    val what = when {
        a.slotted && a.scope == MidiAction.Scope.FX -> stringResource(actionLabel(a)) + " · " + stringResource(R.string.dj_fx_slot, target.slot + 1)
        a.slotted -> stringResource(actionLabel(a)) + " " + (target.slot + 1)
        else -> stringResource(actionLabel(a))
    }
    val where = when (a.scope) {
        MidiAction.Scope.DECK -> stringResource(if (target.unit == 0) R.string.dj_deck_a else R.string.dj_deck_b)
        MidiAction.Scope.FX -> stringResource(R.string.dj_fx_unit, DeckLetters.getOrElse(target.unit) { "?" })
        MidiAction.Scope.GLOBAL -> null
    }
    return if (where == null) what else "$what · $where"
}

private fun actionLabel(action: MidiAction): StringKey = when (action) {
    MidiAction.PLAY -> R.string.dj_action_play
    MidiAction.CUE -> R.string.dj_action_cue
    MidiAction.START -> R.string.dj_action_start
    MidiAction.SYNC -> R.string.dj_action_sync
    MidiAction.SYNC_LOCK -> R.string.dj_action_sync_lock
    MidiAction.KEYLOCK -> R.string.dj_action_keylock
    MidiAction.QUANTIZE -> R.string.dj_action_quantize
    MidiAction.LOAD -> R.string.dj_action_load
    MidiAction.EJECT -> R.string.dj_action_eject
    MidiAction.HOT_CUE -> R.string.dj_action_hot_cue
    MidiAction.CLEAR_HOT_CUE -> R.string.dj_action_clear_hot_cue
    MidiAction.LOOP_IN -> R.string.dj_action_loop_in
    MidiAction.LOOP_OUT -> R.string.dj_action_loop_out
    MidiAction.LOOP -> R.string.dj_action_loop
    MidiAction.RELOOP -> R.string.dj_action_reloop
    MidiAction.LOOP_HALVE -> R.string.dj_action_loop_halve
    MidiAction.LOOP_DOUBLE -> R.string.dj_action_loop_double
    MidiAction.JUMP_BACK -> R.string.dj_action_jump_back
    MidiAction.JUMP_FORWARD -> R.string.dj_action_jump_forward
    MidiAction.JOG_TOUCH -> R.string.dj_action_jog_touch
    MidiAction.VOLUME -> R.string.dj_action_volume
    MidiAction.TEMPO -> R.string.dj_action_tempo
    MidiAction.TRIM -> R.string.dj_action_trim
    MidiAction.EQ_HIGH -> R.string.dj_action_eq_high
    MidiAction.EQ_MID -> R.string.dj_action_eq_mid
    MidiAction.EQ_LOW -> R.string.dj_action_eq_low
    MidiAction.FILTER -> R.string.dj_action_filter
    MidiAction.JOG -> R.string.dj_action_jog
    MidiAction.CROSSFADER -> R.string.dj_action_crossfader
    MidiAction.BROWSE -> R.string.dj_action_browse
    MidiAction.FX_TOGGLE -> R.string.dj_action_fx_toggle
    MidiAction.FX_ASSIGN -> R.string.dj_action_fx_assign
    MidiAction.FX_MIX -> R.string.dj_action_fx_mix
    MidiAction.FX_AMOUNT -> R.string.dj_action_fx_amount
}

// ── Pieces ─────────────────────────────────────────────────────────────

/** A pane of glass over the screen's backdrop. */
@Composable
private fun Panel(haze: HazeState, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .liquidGlass(hazeState = haze, shape = MonoDimens.shapeLg)
            .padding(MonoDimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        content = content,
    )
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

/** A small label on a tinted pill: LEADER, USB MIDI. */
@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(MonoDimens.shapePill)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A round glass button with an icon; [active] lights it in [accent]. */
@Composable
private fun GlassIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    size: Dp = 34.dp,
) {
    val cs = MaterialTheme.colorScheme
    PressableGlass(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .background(if (active) accent.copy(alpha = 0.20f) else Color.Transparent, CircleShape),
        shape = CircleShape,
        enabled = enabled,
        onClickLabel = description,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                !enabled -> cs.onSurfaceVariant.copy(alpha = 0.38f)
                active -> accent
                else -> cs.onSurfaceVariant
            },
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun deckSliderColors(color: Color): SliderColors = SliderDefaults.colors(
    thumbColor = color,
    activeTrackColor = color,
    inactiveTrackColor = color.copy(alpha = 0.22f),
)

/** "3:07". */
private fun clock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
