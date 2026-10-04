package tf.monochrome.desktop.ui.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * A horizontal row (a shelf of albums, a strip of chips) with arrows at its
 * ends while the mouse is over it.
 *
 * On a phone a shelf scrolls under the thumb. A mouse cannot drag it — Compose
 * does not scroll lists on a mouse drag — and the plain wheel deliberately goes
 * on scrolling the page the shelf sits in, so without these a mouse user saw
 * the first four albums and no way to the fifth. Shift+wheel and a touchpad's
 * sideways swipe still scroll the row directly.
 *
 * Each arrow appears only while there is something its way, and moves the row
 * by most of its own width, leaving an item's worth of overlap so the eye keeps
 * its place.
 */
@Composable
fun HoverScrollRow(
    state: ScrollableState,
    modifier: Modifier = Modifier,
    /** Keeps the arrows clear of the row's own content padding, if it has any. */
    arrowInset: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val page = { direction: Int ->
        scope.launch { state.animateScrollBy(direction * width * PAGE_FRACTION) }
        Unit
    }
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .hoverable(hover),
    ) {
        content()
        ScrollArrow(
            visible = hovered && state.canScrollBackward,
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = arrowInset),
            onClick = { page(-1) },
        )
        ScrollArrow(
            visible = hovered && state.canScrollForward,
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = arrowInset),
            onClick = { page(1) },
        )
    }
}

@Composable
private fun ScrollArrow(visible: Boolean, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val scheme = MaterialTheme.colorScheme
        Box(
            Modifier
                .size(36.dp)
                .background(scheme.surface.copy(alpha = 0.92f), CircleShape)
                .border(1.dp, scheme.onSurface.copy(alpha = 0.18f), CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                // Not focusable: the row's own items are what Tab walks, and an
                // arrow that only exists under the mouse is no place for focus.
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}

private const val PAGE_FRACTION = 0.8f

/**
 * Scrollbars in the theme's ink rather than Compose's default black, which is
 * invisible on the app's dark grounds. Thin at rest and thicker under the
 * mouse, as Windows 11 draws its own.
 */
@Composable
fun ThemedScrollbars(content: @Composable () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val style = remember(ink) {
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = RoundedCornerShape(3.dp),
            hoverDurationMillis = 250,
            unhoverColor = ink.copy(alpha = 0.18f),
            hoverColor = ink.copy(alpha = 0.45f),
        )
    }
    CompositionLocalProvider(LocalScrollbarStyle provides style, content = content)
}

/** A scrollbar on the right edge of a [Box] holding a lazy list. */
@Composable
fun BoxScope.ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
    )
}

/** A scrollbar on the right edge of a [Box] holding a lazy grid. */
@Composable
fun BoxScope.GridScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
    )
}

/** A scrollbar on the right edge of a [Box] holding a `verticalScroll` column. */
@Composable
fun BoxScope.ColumnScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp),
    )
}
