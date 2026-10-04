package tf.monochrome.desktop.ui.onboarding.steps

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tf.monochrome.desktop.ui.input.DesktopInput
import tf.monochrome.desktop.ui.input.desktopHover
import tf.monochrome.desktop.ui.input.focusRing
import tf.monochrome.desktop.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.desktop.R

private data class TourSlide(
    val icon: ImageVector,
    val title: tf.monochrome.desktop.res.StringKey,
    val text: tf.monochrome.desktop.res.StringKey,
)

private val tourSlides = listOf(
    TourSlide(Icons.Default.Tune, R.string.tour_sound_title, R.string.tour_sound_text),
    TourSlide(Icons.Default.Equalizer, R.string.tour_dsp_title, R.string.tour_dsp_text),
    TourSlide(Icons.Default.HighQuality, R.string.tour_hires_title, R.string.tour_hires_text),
    TourSlide(Icons.Default.Radio, R.string.tour_radio_title, R.string.tour_radio_text),
)

/**
 * Swipeable 4-slide feature tour; the button reads Next until the last slide.
 * [active] is false while the step is animating out, so it no longer takes Back
 * or the arrows from the step that replaced it.
 */
@Composable
fun FeatureTourStep(onDone: () -> Unit, active: Boolean = true) {
    val pagerState = rememberPagerState(pageCount = { tourSlides.size })
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == tourSlides.lastIndex
    // Callers step from targetPage, where a running animation is heading, so
    // quick presses add up instead of repeating the same slide.
    val goTo = { page: Int ->
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, tourSlides.lastIndex)) }
        Unit
    }
    // Back turns back a slide before it leaves the tour. Registered after
    // OnboardingScreen's handler, so it wins while there is a slide to go to;
    // Escape and the mouse's Back button arrive here too.
    BackHandler(enabled = active && pagerState.targetPage > 0) { goTo(pagerState.targetPage - 1) }
    val pagerHover = remember { MutableInteractionSource() }
    val pagerHovered by pagerHover.collectIsHoveredAsState()
    val pagerFocused by pagerHover.collectIsFocusedAsState()
    // Desktop: the focus left with the previous step's button, and the arrows
    // only reach the tour from something focused inside it. Reached from the
    // keyboard, the tour opens with Next focused, so Enter works at once; from
    // the mouse, with the slides focused, which shows nothing until a key is
    // pressed, rather than a tinted button.
    val nextFocus = remember { FocusRequester() }
    val slidesFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching {
            if (DesktopInput.focusVisible) nextFocus.requestFocus() else slidesFocus.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MonoDimens.spacingXl)
            // Desktop: Left and Right turn the slides, as a swipe does, from
            // anywhere in the step that holds focus.
            .onKeyEvent { event ->
                if (!active || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft -> goTo(pagerState.targetPage - 1)
                    Key.DirectionRight -> goTo(pagerState.targetPage + 1)
                    else -> return@onKeyEvent false
                }
                true
            }
    ) {
        // Desktop: a mouse cannot swipe a pager, so arrows at its edges while
        // the pointer is over it. They are not focusable; the keys and the
        // dots below cover the keyboard. The slides are a Tab stop of their
        // own, where Left and Right turn them.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRing(pagerFocused)
                .focusRequester(slidesFocus)
                .focusable(interactionSource = pagerHover)
                .hoverable(pagerHover)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val slide = tourSlides[page]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = slide.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        text = stringResource(slide.title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = MonoDimens.spacingLg)
                    )
                    Text(
                        text = stringResource(slide.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(
                            top = MonoDimens.spacingSm,
                            start = MonoDimens.spacingLg,
                            end = MonoDimens.spacingLg
                        )
                    )
                }
            }
            TourArrow(
                visible = pagerHovered && pagerState.canScrollBackward,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                description = stringResource(R.string.action_previous),
                modifier = Modifier.align(Alignment.CenterStart),
                onClick = { goTo(pagerState.targetPage - 1) }
            )
            TourArrow(
                visible = pagerHovered && pagerState.canScrollForward,
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                description = stringResource(R.string.action_next),
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = { goTo(pagerState.targetPage + 1) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MonoDimens.spacingLg)
                .selectableGroup(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(tourSlides.size) { index ->
                // Each dot is a way to its slide: clickable for the mouse, a Tab
                // stop for the keyboard. The hit area is wider than the dot.
                val dotInteraction = remember { MutableInteractionSource() }
                val slideTitle = stringResource(tourSlides[index].title)
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .desktopHover(dotInteraction, CircleShape)
                        .selectable(
                            selected = index == pagerState.currentPage,
                            interactionSource = dotInteraction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { goTo(index) }
                        )
                        .semantics { contentDescription = slideTitle },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = if (index == pagerState.currentPage) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                },
                                shape = MonoDimens.shapeCircle
                            )
                    )
                }
            }
        }

        Button(
            onClick = {
                if (onLastPage) onDone()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            shape = MonoDimens.shapePill,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .focusRequester(nextFocus)
        ) {
            Text(
                text = if (onLastPage) stringResource(R.string.action_done) else stringResource(R.string.action_next),
                style = MaterialTheme.typography.titleMedium
            )
        }
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(MonoDimens.spacingLg)
        )
    }
}

/** A slide arrow at the pager's edge, shown only under the mouse. */
@Composable
private fun TourArrow(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val scheme = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(scheme.surface.copy(alpha = 0.92f), CircleShape)
                .border(1.dp, scheme.onSurface.copy(alpha = 0.18f), CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .focusProperties { canFocus = false }
                .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = scheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}
