package tf.monochrome.desktop.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tf.monochrome.desktop.R
import tf.monochrome.desktop.res.StringKey
import tf.monochrome.desktop.ui.theme.MonoDimens

/**
 * Opens [ShortcutHelpDialog], for a control that offers it to the mouse: F1 and
 * ? only help someone who already knows they are there. Published by the nav
 * host, which owns the dialog; null outside it, where a caller shows nothing.
 */
val LocalShowShortcutHelp = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Every keyboard and mouse binding the app has, on one page. F1 or ? opens it.
 *
 * A phone shows what it can do; a keyboard shortcut is invisible until someone
 * says it exists. The window-wide bindings are [AppShortcuts]'s, read off its
 * `handle`; the rest are the per-control ones that `ControlInput`,
 * `PointerInput` and `HoverScrollRow` give every slider, row and shelf. Change a
 * binding there and change its line here.
 *
 * A plain dialog, not glass: it sits over the whole window and has no backdrop
 * of its own to blur (`docs/ui-invariants.md`, on panes inside a dialog).
 * Escape closes it as Back closes any dialog, through [onDismiss]; the key
 * handler below never touches it.
 */
@Composable
fun ShortcutHelpDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(vertical = MonoDimens.spacingXl)
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.92f),
            shape = MonoDimens.shapeLg,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = MonoDimens.spacingSm)) {
                Text(
                    text = stringResource(R.string.shortcut_help_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = MonoDimens.spacingXl),
                )

                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                val contentFocus = remember { FocusRequester() }
                val lineStep = with(LocalDensity.current) { 48.dp.roundToPx() }
                val sections = remember { helpSections() }
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(top = MonoDimens.spacingSm)
                        // Before the focus target, so the keys reach it. The
                        // list holds focus when the dialog opens, and the
                        // arrows, the page keys, Home and End scroll it. With a
                        // modifier held the key is someone else's: Ctrl+arrows
                        // still set the volume and skip tracks from here.
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                                return@onKeyEvent false
                            }
                            val target = when (event.key) {
                                Key.DirectionDown -> scroll.value + lineStep
                                Key.DirectionUp -> scroll.value - lineStep
                                Key.PageDown -> scroll.value + scroll.viewportSize
                                Key.PageUp -> scroll.value - scroll.viewportSize
                                Key.MoveHome -> 0
                                Key.MoveEnd -> scroll.maxValue
                                else -> return@onKeyEvent false
                            }
                            scope.launch { scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue)) }
                            true
                        }
                        .focusRequester(contentFocus)
                        .focusTarget(),
                ) {
                    Column(
                        Modifier
                            .verticalScroll(scroll)
                            .padding(horizontal = MonoDimens.spacingXl),
                    ) {
                        sections.forEach { section -> HelpSection(section) }
                    }
                    // Only while the list overflows: on a tall window the bar
                    // would stretch the dialog to the window's height.
                    if (scroll.overflows) ColumnScrollbar(scroll)
                }
                // Focus starts on the list, so the arrows scroll it straight
                // away. Not on Close: Enter would shut the dialog on the first
                // press.
                LaunchedEffect(Unit) { contentFocus.requestFocus() }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonoDimens.spacingMd),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

@Composable
private fun HelpSection(section: HelpGroup) {
    Text(
        text = stringResource(section.title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = MonoDimens.spacingLg, bottom = MonoDimens.spacingXs),
    )
    section.lines.forEach { line -> HelpLine(line) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpLine(line: HelpEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (line.arg == null) stringResource(line.label) else stringResource(line.label, line.arg),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(0.45f)
                .padding(end = MonoDimens.spacingLg),
        )
        // Weighted as well, so a long list of keys wraps under itself instead
        // of squeezing the label out of a narrow window.
        FlowRow(
            modifier = Modifier.weight(0.55f),
            horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            line.combos.forEach { combo -> KeyCombo(combo) }
        }
    }
}

@Composable
private fun KeyCombo(caps: List<Cap>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        caps.forEachIndexed { index, cap ->
            if (index > 0) {
                Text(
                    text = COMBO_JOINER,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            KeyCap(
                when (cap) {
                    is Cap.Word -> stringResource(cap.name)
                    is Cap.Glyph -> cap.text
                },
            )
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    // Drawn in the theme's ink, as the scroll arrows are, so it reads on light
    // and dark schemes alike without a colour of its own.
    val ink = MaterialTheme.colorScheme.onSurface
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .background(ink.copy(alpha = 0.07f), MonoDimens.shapeSm)
            .border(1.dp, ink.copy(alpha = 0.18f), MonoDimens.shapeSm)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private sealed interface Cap {
    /** A key or mouse action whose name is translated: Ctrl is Strg in German. */
    data class Word(val name: StringKey) : Cap

    /**
     * What is printed on the key itself, the same on every keyboard: letters,
     * digits, arrows, symbols and F-keys. Not text to translate.
     */
    data class Glyph(val text: String) : Cap
}

private class HelpEntry(val label: StringKey, val combos: List<List<Cap>>, val arg: Any? = null)

private class HelpGroup(val title: StringKey, val lines: List<HelpEntry>)

// Between the keys of one combination, as in Ctrl+P: a symbol every language
// writes the same way, like the glyphs on the keys.
private const val COMBO_JOINER = "+"

private fun keys(vararg caps: Cap): List<Cap> = caps.toList()

private fun glyph(text: String): Cap = Cap.Glyph(text)

private fun helpSections(): List<HelpGroup> {
    val ctrl = Cap.Word(R.string.key_ctrl)
    val shift = Cap.Word(R.string.key_shift)
    val alt = Cap.Word(R.string.key_alt)
    val space = Cap.Word(R.string.key_space)
    val esc = Cap.Word(R.string.key_esc)
    val tab = Cap.Word(R.string.key_tab)
    val enter = Cap.Word(R.string.key_enter)
    val backspace = Cap.Word(R.string.key_backspace)
    val home = Cap.Word(R.string.key_home)
    val end = Cap.Word(R.string.key_end)
    val pageUp = Cap.Word(R.string.key_page_up)
    val pageDown = Cap.Word(R.string.key_page_down)
    val menuKey = Cap.Word(R.string.key_menu)
    val delete = Cap.Word(R.string.key_delete)
    val wheel = Cap.Word(R.string.mouse_wheel)
    val rightClick = Cap.Word(R.string.mouse_right_click)
    val doubleClick = Cap.Word(R.string.mouse_double_click)
    val mouseBack = Cap.Word(R.string.mouse_back_button)
    val mouseForward = Cap.Word(R.string.mouse_forward_button)
    val left = glyph("←")
    val right = glyph("→")
    val up = glyph("↑")
    val down = glyph("↓")
    val arrows = glyph("← ↑ ↓ →")

    return listOf(
        HelpGroup(
            R.string.settings_playback,
            listOf(
                HelpEntry(R.string.shortcut_play_pause, listOf(keys(space), keys(ctrl, glyph("P")))),
                HelpEntry(R.string.shortcut_next_track, listOf(keys(ctrl, right))),
                HelpEntry(R.string.shortcut_previous_track, listOf(keys(ctrl, left))),
                HelpEntry(
                    R.string.shortcut_seek,
                    listOf(keys(left), keys(right)),
                    arg = (AppShortcuts.SEEK_MS / 1000).toInt(),
                ),
                HelpEntry(
                    R.string.shortcut_seek,
                    listOf(keys(shift, left), keys(shift, right)),
                    arg = (AppShortcuts.SEEK_LONG_MS / 1000).toInt(),
                ),
                HelpEntry(R.string.shortcut_volume, listOf(keys(ctrl, up), keys(ctrl, down))),
                HelpEntry(R.string.shortcut_mute, listOf(keys(glyph("M")))),
                HelpEntry(R.string.shortcut_shuffle, listOf(keys(ctrl, glyph("S")))),
                HelpEntry(R.string.shortcut_repeat, listOf(keys(ctrl, glyph("R")))),
                HelpEntry(R.string.shortcut_like, listOf(keys(ctrl, glyph("L")))),
            ),
        ),
        HelpGroup(
            R.string.shortcut_section_navigation,
            listOf(
                HelpEntry(R.string.tab_search, listOf(keys(ctrl, glyph("F")), keys(glyph("/")))),
                HelpEntry(R.string.settings, listOf(keys(ctrl, glyph(",")))),
                HelpEntry(R.string.now_playing, listOf(keys(ctrl, glyph("J")))),
                HelpEntry(R.string.shortcut_tabs, listOf(keys(ctrl, glyph("1–9")))),
                HelpEntry(
                    R.string.action_back,
                    listOf(keys(esc), keys(alt, left), keys(backspace), keys(mouseBack)),
                ),
                HelpEntry(R.string.action_forward, listOf(keys(alt, right), keys(mouseForward))),
                HelpEntry(R.string.action_fullscreen, listOf(keys(glyph("F11")))),
                HelpEntry(R.string.shortcut_move_focus, listOf(keys(tab), keys(shift, tab))),
                HelpEntry(R.string.shortcut_activate, listOf(keys(enter), keys(space))),
                HelpEntry(R.string.shortcut_show_help, listOf(keys(glyph("F1")), keys(glyph("?")))),
            ),
        ),
        HelpGroup(
            R.string.shortcut_section_controls,
            listOf(
                HelpEntry(R.string.shortcut_adjust, listOf(keys(arrows), keys(wheel))),
                HelpEntry(R.string.shortcut_adjust_fine, listOf(keys(ctrl, arrows), keys(ctrl, wheel))),
                HelpEntry(R.string.shortcut_adjust_big, listOf(keys(pageUp), keys(pageDown))),
                HelpEntry(R.string.shortcut_min_max, listOf(keys(home), keys(end))),
                HelpEntry(R.string.action_reset, listOf(keys(doubleClick), keys(delete))),
            ),
        ),
        HelpGroup(
            R.string.shortcut_section_lists,
            listOf(
                HelpEntry(
                    R.string.shortcut_item_menu,
                    listOf(keys(rightClick), keys(menuKey), keys(shift, glyph("F10"))),
                ),
                HelpEntry(R.string.shortcut_menu_move, listOf(keys(up), keys(down))),
                HelpEntry(R.string.shortcut_scroll_row, listOf(keys(shift, wheel))),
            ),
        ),
    )
}
