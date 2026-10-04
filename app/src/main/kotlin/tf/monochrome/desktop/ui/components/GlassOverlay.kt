package tf.monochrome.desktop.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import tf.monochrome.desktop.domain.model.PlayerGlassSettings
import tf.monochrome.desktop.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R

/**
 * Where a floating pane goes when it has to be made of glass.
 *
 * A `Dialog` and a `ModalBottomSheet` each open their own window, and a haze
 * effect cannot sample a layer belonging to another one: the backdrop it wants
 * to blur was captured into the window underneath. Handed a haze state across
 * that boundary it paints its own base colour and comes out as a flat slab,
 * which is the failure `docs/ui-invariants.md` keeps warning about, and there is
 * no setting that fixes it. The pane has to be drawn in the window whose
 * background it is blurring.
 *
 * So this is that window's slot. The host is published by the nav host, which
 * renders whatever is registered as the topmost sibling *of* its haze source —
 * a sibling, not a descendant, because a pane inside the source would be trying
 * to blur a picture it is part of. The page indicator above the pager already
 * works exactly this way.
 *
 * What that costs is the scrim and Back, which `Dialog` gave away for free and
 * are handled here instead. That is the cheaper half of the trade.
 */
@Stable
class GlassOverlayHost {

    internal class Entry(
        val content: @Composable BoxScope.() -> Unit,
        val onDismiss: () -> Unit,
    )

    internal var entry by mutableStateOf<Entry?>(null)
        private set

    internal fun show(e: Entry) {
        entry = e
    }

    /**
     * Withdraw [e], and only [e]. Two panes can overlap for a frame while one
     * replaces another, and a blind clear would take the incoming one down with
     * the outgoing one.
     */
    internal fun hide(e: Entry) {
        if (entry === e) entry = null
    }
}

val LocalGlassOverlayHost = staticCompositionLocalOf<GlassOverlayHost?> { null }

/**
 * Shows [content] in the app's overlay slot for as long as this call is in
 * composition, which makes it a drop-in for the `if (visible) { Dialog(…) }`
 * shape the screens already use.
 *
 * [content] is composed in the *host's* composition rather than the caller's,
 * so it is held in [rememberUpdatedState] and read through it: a lambda
 * captured once would go on rendering the values it closed over, and a dialog
 * showing yesterday's playlist name is a worse bug than one that does not open.
 */
@Composable
fun GlassOverlay(
    onDismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val host = LocalGlassOverlayHost.current
    if (host == null) {
        // No slot in this window -- a preview, or a screen hosted outside the
        // nav host. Fall back to a plain dialog rather than rendering nothing:
        // it cannot be glass there for the reason above, and a pane that
        // silently fails to open is a far worse answer than a solid one.
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f),
                shape = MonoDimens.shapeLg,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                Box(Modifier.padding(14.dp)) { content() }
            }
        }
        return
    }
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    // Keyed on the host alone: the entry reads both lambdas through state, so it
    // never needs re-registering when the caller recomposes.
    val entry = remember(host) {
        GlassOverlayHost.Entry(
            content = { latestContent() },
            onDismiss = { latestDismiss() },
        )
    }
    DisposableEffect(host, entry) {
        host.show(entry)
        onDispose { host.hide(entry) }
    }
}

/**
 * Renders the registered pane. Called by the nav host as the last child of the
 * box that holds its haze source, so this sits above everything and blurs it.
 */
@Composable
fun BoxScope.GlassOverlayLayer(
    host: GlassOverlayHost,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
) {
    val entry = host.entry ?: return

    BackHandler(enabled = true) { entry.onDismiss() }

    // Keyboard focus moves into the pane when it opens and cannot Tab back out
    // to the page behind the scrim, as it could not leave a Dialog: Tab past the
    // last control comes round to the first, and Shift+Tab past the first to the
    // last. Focus lands on the pane itself rather than its first field, so
    // opening one does not raise a soft keyboard on a phone; once it has moved
    // on to a control the pane stops being a stop of its own, or Shift+Tab would
    // land on an outline-less nothing between the first control and the last.
    val paneFocus = remember { FocusRequester() }
    var paneHoldsEntry by remember(entry) { mutableStateOf(true) }
    LaunchedEffect(entry) { paneFocus.requestFocus() }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // No ripple and no indication: this is a dismiss region, not a
            // button, and a ripple blooming across the whole screen reads as
            // one. Not a Tab stop either: Escape and the pane's own buttons
            // already dismiss it.
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.action_dismiss),
                onClick = { entry.onDismiss() },
            ),
    )

    Box(
        modifier = Modifier
            .matchParentSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            // The playlist pane has text fields in it; without this the keyboard
            // covers the one being typed into.
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassPanel(
            hazeState = hazeState,
            glass = glass,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                // Bubbling, not preview: a control that uses Tab itself, such as
                // a multi-line field, keeps it.
                .onKeyEvent { event ->
                    if (
                        event.type != KeyEventType.KeyDown || event.key != Key.Tab ||
                        event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
                    ) return@onKeyEvent false
                    val back = event.isShiftPressed
                    if (!focusManager.moveFocus(if (back) FocusDirection.Previous else FocusDirection.Next)) {
                        // At an edge, where onExit below refused the way out.
                        // Entering the pane from its bottom-right corner reaches
                        // the last control; Next/Previous cannot enter a group.
                        val held = paneHoldsEntry
                        paneHoldsEntry = false
                        if (!paneFocus.requestFocus(if (back) FocusDirection.Up else FocusDirection.Enter)) {
                            paneHoldsEntry = held
                        }
                    }
                    true
                }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .focusProperties { canFocus = paneHoldsEntry }
                .onFocusChanged { if (it.hasFocus && !it.isFocused) paneHoldsEntry = false }
                .focusRequester(paneFocus)
                .focusTarget(),
            // The layer already holds off the navigation bar; a second inset
            // here would push a centred pane visibly off-centre.
            avoidNavigationBar = false,
        ) {
            entry.content(this)
        }
    }
}
