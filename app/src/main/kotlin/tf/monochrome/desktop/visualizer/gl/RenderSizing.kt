package tf.monochrome.desktop.visualizer.gl

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A size in device pixels. */
internal data class PixelSize(val width: Int, val height: Int) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        val Zero = PixelSize(0, 0)
    }
}

/** The longest side the visualizer renders at, in either orientation. */
internal const val MAX_RENDER_LONG_SIDE = 1920

/** The shortest side the visualizer renders at, in either orientation. */
internal const val MAX_RENDER_SHORT_SIDE = 1080

/**
 * The size projectM renders at for a surface of [width]×[height] pixels: the
 * surface's own size, scaled down with its aspect ratio kept until it fits
 * 1920×1080 turned whichever way the surface is.
 *
 * Every frame is read back to the CPU and uploaded again by Skia, so the cost
 * is paid twice per pixel per frame. Past 1080p that is a 4K panel's worth of
 * bus traffic for detail that MilkDrop's soft, feedback-blurred images do not
 * have; drawn back at the surface's size the difference does not show.
 */
internal fun renderSizeFor(
    width: Int,
    height: Int,
    maxLongSide: Int = MAX_RENDER_LONG_SIDE,
    maxShortSide: Int = MAX_RENDER_SHORT_SIDE,
): PixelSize {
    if (width <= 0 || height <= 0) return PixelSize.Zero
    val landscape = width >= height
    val scale = min(
        1.0,
        min(maxLongSide.toDouble() / max(width, height), maxShortSide.toDouble() / min(width, height)),
    )
    return PixelSize(
        (width * scale).roundToInt().coerceIn(1, if (landscape) maxLongSide else maxShortSide),
        (height * scale).roundToInt().coerceIn(1, if (landscape) maxShortSide else maxLongSide),
    )
}

/**
 * [wanted], shrunk with its aspect ratio kept until it fits [availableWidth]×
 * [availableHeight] — the framebuffer the hidden window actually got.
 *
 * A window can come out smaller than asked for: Windows clamps a window to
 * what its monitor can show. Rendering at the requested size would then
 * clip the right and top of the frame, and fitting each side separately would
 * stretch it, so the frame is scaled as a whole.
 */
internal fun fitWithin(wanted: PixelSize, availableWidth: Int, availableHeight: Int): PixelSize {
    if (wanted.isEmpty || availableWidth <= 0 || availableHeight <= 0) return PixelSize.Zero
    if (wanted.width <= availableWidth && wanted.height <= availableHeight) return wanted
    val scale = min(
        availableWidth.toDouble() / wanted.width,
        availableHeight.toDouble() / wanted.height,
    )
    return PixelSize(
        (wanted.width * scale).toInt().coerceIn(1, availableWidth),
        (wanted.height * scale).toInt().coerceIn(1, availableHeight),
    )
}

/**
 * Holds a new render size back until the surface has stopped changing.
 *
 * Dragging a window edge resizes the composable on every mouse move, and each
 * resize reallocates projectM's render targets, which restarts a preset's
 * feedback trails. Until a size has held for [settleNanos] the frame keeps its
 * old size and is scaled to the surface on draw; the very first size is taken
 * at once, since there is nothing to keep.
 */
internal class ResizeSettler(private val settleNanos: Long) {
    var current: PixelSize = PixelSize.Zero
        private set
    private var candidate: PixelSize = PixelSize.Zero
    private var candidateSince = 0L

    /**
     * Offers the size the surface wants at [nowNanos]. Returns the size to
     * render at, which is [current] until a change has settled.
     */
    fun offer(wanted: PixelSize, nowNanos: Long): PixelSize {
        if (wanted == current) {
            candidate = current
            return current
        }
        if (current.isEmpty || wanted.isEmpty) {
            current = wanted
            candidate = wanted
            return current
        }
        if (wanted != candidate) {
            candidate = wanted
            candidateSince = nowNanos
        } else if (nowNanos - candidateSince >= settleNanos) {
            current = wanted
        }
        return current
    }

    /** Nanoseconds until a pending size settles, or 0 when nothing is pending. */
    fun pendingNanos(nowNanos: Long): Long =
        if (candidate == current) 0L else max(1L, settleNanos - (nowNanos - candidateSince))

    fun reset() {
        current = PixelSize.Zero
        candidate = PixelSize.Zero
    }
}
