package tf.monochrome.desktop.ui.components
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import tf.monochrome.desktop.ui.input.contextClick
import tf.monochrome.desktop.ui.input.desktopHover
import tf.monochrome.desktop.ui.theme.MonoDimens
import tf.monochrome.desktop.ui.theme.PressSpring
import tf.monochrome.desktop.ui.theme.reduceMotion

/**
 * The app's card and row click: a squeeze on press, no ripple.
 *
 * Desktop: the hand cursor and a rim in [hoverShape] under the mouse, and a
 * firmer rim with keyboard focus (see [desktopHover]). Pass the shape the
 * element is drawn in, so the rim sits on its edge.
 */
fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    hoverShape: Shape = MonoDimens.shapeMd,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }

    // With animations off, drop the whole spring: no press state to collect, no
    // animation to run, and no graphicsLayer to allocate. This modifier is on
    // every card and lazy-list row in the app, so skipping the layer here is
    // worth more than the same skip anywhere else.
    if (reduceMotion()) {
        return@composed this
            .desktopHover(interactionSource, hoverShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = PressSpring,
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .desktopHover(interactionSource, hoverShape)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

/**
 * [bounceClick] with a long press. On the desktop the long press is also a
 * right-click, the Menu key and Shift+F10 ([contextClick]), because that is
 * where a mouse looks for a menu; pass [onContextClick] when the two should
 * differ, or null to leave the right button alone.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceCombinedClick(
    scaleDown: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    hoverShape: Shape = MonoDimens.shapeMd,
    onContextClick: (() -> Unit)? = onLongClick,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }
    // Before the clickable, so it is the clickable's ancestor and hears the
    // Menu key while the row has focus.
    val context = Modifier.contextClick(enabled = onContextClick != null) { onContextClick?.invoke() }

    if (reduceMotion()) {
        return@composed this
            .desktopHover(interactionSource, hoverShape)
            .then(context)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = PressSpring,
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .desktopHover(interactionSource, hoverShape)
        .then(context)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}
