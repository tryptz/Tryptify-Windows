package tf.monochrome.desktop.data.presence

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Spin the album art as a disc, as an animated WebP.
 *
 * The small-image slot is a fixed circle, so a badge can never span the cover's
 * width — the only surface with that width is the cover itself. Which means the
 * image has to be built here, on the device, because the artwork is different
 * every track.
 *
 * This used to draw a spectrum across the bottom third of the sleeve. It read as
 * a graphic *stuck on* the artwork rather than as anything the artwork was
 * doing, and on a busy cover the curve and the picture fought each other. A
 * turning disc is the same idea told properly: nothing is drawn over the art at
 * all, the art *is* the moving part, and everybody already knows what a record
 * going round means.
 *
 * Deliberately cheaper than the pre-drawn badges. Those are 60fps because a
 * build machine drew them once; this runs on a phone at every track change, and
 * a rotation at 60fps in 320px is a hundred and twenty full-canvas composites
 * and encodes for something rendered at a couple of hundred pixels on somebody
 * else's screen. [FRAMES] is the honest number for a device — and rotation is
 * the one motion where the step between frames is visible as judder, so it buys
 * a few more than the spectrum needed.
 *
 * Desktop: drawn with Skia (`org.jetbrains.skia`, already on the classpath
 * through Compose, and the bitmap type Coil hands back on the JVM) instead of
 * `android.graphics`. That is the library Android's Canvas is a thin layer
 * over, so the shaders, the rotation and the anti-aliasing are the same calls
 * underneath, and the frames go through the same encoder: Skia's
 * `SkWebpEncoder` (libwebp), which is what `Bitmap.compress(WEBP, q)` runs on
 * Android. [AnimatedWebP] then stitches the stills exactly as it does there.
 * The JDK has no WebP writer, and a GIF fallback would mean 256 colours and a
 * larger file against the same [MAX_BYTES] budget for a worse picture, so the
 * container Discord's media proxy animates stays animated WebP.
 */
object PresenceArtwork {

    /**
     * Canvas size. Smaller than the spectrum's 320 for one reason: total file
     * size (see [MAX_BYTES]). A spinning disc is a full, distinct picture every
     * frame — the spectrum reused one near-static cover — so at 320 it more than
     * doubled the spectrum's bytes and blew past Discord's animation cap. 288 is
     * still sharper than the slot renders it at.
     */
    const val SIZE = 288

    /**
     * Frames per revolution.
     *
     * Every frame is one whole re-encoded canvas, so this is the cost knob for
     * both time and bytes. 28 steps the disc ~13° at a time, which still reads
     * as turning rather than ticking.
     */
    const val FRAMES = 28

    /**
     * The ceiling the assembled animation is kept under.
     *
     * Discord's media proxy will not animate an asset past roughly a quarter of
     * a megabyte — over it, the client shows the first frame and nothing else,
     * which on a disc at rest is just a round cover that never turns. That is
     * exactly the "animation isn't showing" this exists to prevent. The number
     * is a little under 256 KiB to leave room for the container around the
     * frames. [render] steps the encoder's quality down until it fits.
     *
     * Internal rather than private so the desktop test can hold the budget.
     */
    internal const val MAX_BYTES = 248_000

    /** Where the quality search starts, and the floor it will not go below. */
    private const val QUALITY_MAX = 74
    private const val QUALITY_MIN = 40
    const val QUALITY = QUALITY_MAX

    /** The disc, as a fraction of the canvas. Short of the edge so it reads as round. */
    private const val DISC = 0.94f

    /** The label hole at the centre, as a fraction of the disc's radius. */
    private const val HUB = 0.15f

    /** A rhythm: where the hits land in a 4/4 bar, and how fast the bar runs. */
    data class Groove(val bpm: Int, val kicks: List<Float>, val snares: List<Float>)

    /** Mirrors tools/build_presence_loops.py, which draws the static badges. */
    val GROOVES: Map<String, Groove> = mapOf(
        "four" to Groove(128, listOf(0f, 1f, 2f, 3f), listOf(1f, 3f)),
        "hard" to Groove(150, listOf(0f, 1f, 2f, 3f), listOf(1f, 3f)),
        "dnb" to Groove(174, listOf(0f, 2.5f), listOf(1f, 3f)),
        "boombap" to Groove(90, listOf(0f, 0.75f, 2.5f), listOf(1f, 3f)),
        "trap" to Groove(140, listOf(0f, 1.75f, 2.5f), listOf(2f)),
        "backbeat" to Groove(120, listOf(0f, 2f), listOf(1f, 3f)),
        "halftime" to Groove(70, listOf(0f), listOf(2f)),
        "dembow" to Groove(96, listOf(0f, 1.5f, 2f, 3.5f), listOf(0.75f, 1.75f, 2.75f, 3.75f)),
    )

    /** Beats per revolution. One bar per turn, so the spin is on the music. */
    private const val BEATS = 4f

    /**
     * How a cover is shrunk to the canvas. Tag artwork runs to a few thousand
     * pixels, and a plain bilinear pass over a 10:1 reduction aliases; the mip
     * chain, which Skia builds on demand for a raster image, does not.
     */
    private val SCALE_SAMPLING: SamplingMode = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    /**
     * @return an animated WebP, or null if a frame failed to encode.
     */
    fun render(cover: Bitmap, grooveName: String, tint: Int, bpm: Int? = null): ByteArray? {
        val groove = GROOVES[grooveName] ?: GROOVES.getValue(PresenceBadge.DEFAULT_GROOVE)
        // The track's own tempo when the graph knows it, the groove's canonical
        // one otherwise. A house groove turns at 128 because that is where house
        // lives, but a 140 BPM record spinning at 128 is visibly off the beat —
        // and the genre carries a real range worth using.
        val tempo = bpm?.takeIf { it in 40..300 } ?: groove.bpm

        val centre = SIZE / 2f
        val radius = centre * DISC

        // Everything Skia allocates natively, released in one place at the end
        // (Android's equivalent was the recycle() calls). The finalizers would
        // get there eventually, but this runs at every track change for as long
        // as the app is open.
        val natives = ArrayList<AutoCloseable>()
        fun <T : AutoCloseable> T.owned(): T = also { natives += it }
        val frameImages = ArrayList<Image>(FRAMES)

        try {
            val base = scaled(cover, SIZE).owned()

            // The sleeve, as a disc. Built once and turned by the canvas, rather
            // than re-clipped every frame.
            val discPaint = Paint().owned().apply {
                isAntiAlias = true
                shader = base.makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR,
                ).owned()
            }

            // Opaque background, not transparency. Discord's media proxy composites
            // alpha onto black, so a disc on a transparent field would arrive on
            // whatever black the proxy chose; picking the colour here means the
            // corners are a deliberate near-black of the cover's own tint instead.
            val backdrop = Color.makeRGB(
                (Color.getR(tint) * 0.12f).toInt(),
                (Color.getG(tint) * 0.12f).toInt(),
                (Color.getB(tint) * 0.12f).toInt(),
            )

            // Pressed-disc grooves: faint concentric shading that catches the light.
            // Static, because they belong to the disc's shape rather than to the
            // picture printed on it.
            val grooves = Paint().owned().apply {
                isAntiAlias = true
                shader = Shader.makeRadialGradient(
                    centre, centre, radius,
                    gradient(
                        intArrayOf(
                            Color.makeARGB(0, 0, 0, 0),
                            Color.makeARGB(26, 255, 255, 255),
                            Color.makeARGB(0, 0, 0, 0),
                            Color.makeARGB(20, 0, 0, 0),
                        ),
                        floatArrayOf(0.30f, 0.52f, 0.72f, 1f),
                    ),
                ).owned()
            }

            // The sheen, and the reason the spin is legible at all.
            //
            // A cover with no strong asymmetry — a plain field, a centred logo —
            // turns without appearing to, because every frame looks like the last.
            // A highlight that stays put while the art moves under it is what gives
            // the eye something to measure the rotation against.
            val sheen = Paint().owned().apply {
                isAntiAlias = true
                shader = Shader.makeSweepGradient(
                    centre, centre,
                    gradient(
                        intArrayOf(
                            Color.makeARGB(0, 255, 255, 255),
                            Color.makeARGB(54, 255, 255, 255),
                            Color.makeARGB(0, 255, 255, 255),
                            Color.makeARGB(0, 255, 255, 255),
                            Color.makeARGB(38, 255, 255, 255),
                            Color.makeARGB(0, 255, 255, 255),
                            Color.makeARGB(0, 255, 255, 255),
                        ),
                        floatArrayOf(0f, 0.08f, 0.20f, 0.46f, 0.55f, 0.68f, 1f),
                    ),
                ).owned()
            }

            // A soft edge so the disc sits on the backdrop rather than being cut out
            // of it, and the hub the spindle goes through.
            val rim = Paint().owned().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = SIZE * 0.008f
                shader = Shader.makeLinearGradient(
                    0f, 0f, 0f, SIZE.toFloat(),
                    gradient(
                        intArrayOf(
                            Color.makeARGB(90, 255, 255, 255),
                            Color.makeARGB(40, 0, 0, 0),
                        ),
                        null,
                    ),
                ).owned()
            }
            val hub = Paint().owned().apply {
                isAntiAlias = true
                color = backdrop
            }
            val hubRing = Paint().owned().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = SIZE * 0.006f
                color = Color.makeARGB(70, 255, 255, 255)
            }

            // Draw every frame once, as an image. Encoding is separate and may be
            // repeated at a lower quality to fit the budget, and re-drawing the
            // rotation each time would be wasted work — so the pixels are held and
            // only re-compressed. One surface serves every frame: a snapshot is
            // copy-on-write, so drawing the next frame leaves the last intact.
            val surface = Surface.makeRasterN32Premul(SIZE, SIZE).owned()
            val canvas = surface.canvas
            for (f in 0 until FRAMES) {
                val angle = 360f * f / FRAMES
                canvas.clear(backdrop)

                // Only the artwork turns. Everything after this — grooves, sheen,
                // rim, hub — belongs to the disc as an object sitting still under a
                // fixed light, which is what makes the turning readable.
                canvas.save()
                canvas.rotate(angle, centre, centre)
                canvas.drawCircle(centre, centre, radius, discPaint)
                canvas.restore()

                canvas.drawCircle(centre, centre, radius, grooves)
                canvas.drawCircle(centre, centre, radius, sheen)
                canvas.drawCircle(centre, centre, radius, rim)
                canvas.drawCircle(centre, centre, radius * HUB, hub)
                canvas.drawCircle(centre, centre, radius * HUB, hubRing)
                frameImages += surface.makeImageSnapshot()
            }

            val loopMs = (BEATS * 60_000f / tempo).toInt()

            // Encode under the cap, on two levers. First quality: a busy cover at
            // full quality can be over the budget, so it steps down until the whole
            // animation fits, and a simple cover keeps its crispness because it was
            // already small. If quality bottoms out and it still won't fit, frames
            // are dropped — every second one — which halves the bytes and only
            // widens the step the disc turns in. Both are better than a pristine
            // animation Discord freezes on its first frame.
            //
            // Frame counts are strides of the full set, so the angles stay evenly
            // spaced and one bar still turns the disc exactly once.
            var best: ByteArray? = null
            for (stride in intArrayOf(1, 2)) {
                val frames = frameImages.filterIndexed { i, _ -> i % stride == 0 }
                val count = frames.size
                val perFrame = (loopMs / count).coerceAtLeast(1)
                val durations = List(count) { perFrame }

                var quality = QUALITY_MAX
                var fitAtThisStride: ByteArray? = null
                while (quality >= QUALITY_MIN) {
                    val encoded = frames.map { image -> encodeWebp(image, quality) }
                    if (encoded.any { it == null }) break
                    val webp = AnimatedWebP.assemble(
                        encoded.filterNotNull(), durations, SIZE, SIZE,
                    )
                    // Keep the smallest thing seen, so even the pathological
                    // case returns something rather than nothing.
                    val smallest = best
                    if (smallest == null || webp.size < smallest.size) best = webp
                    if (webp.size <= MAX_BYTES) {
                        fitAtThisStride = webp
                        break
                    }
                    quality -= 8
                }
                if (fitAtThisStride != null) {
                    best = fitAtThisStride
                    break
                }
            }
            return best
        } catch (_: Throwable) {
            return null
        } finally {
            frameImages.forEach { runCatching { it.close() } }
            natives.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /**
     * The cover as a plain still: square, [SIZE] on a side, WebP at [QUALITY].
     *
     * What a local cover is uploaded as when it has no URL Discord can fetch
     * (DiscordPresenceManager.hostedAsset). Square, and no larger than the card
     * ever renders: tag artwork runs to a few thousand pixels either way, and
     * posting that costs the listener's data to produce something drawn at a
     * couple of hundred.
     *
     * Desktop: on Android this was `bitmap.scale(SIZE, SIZE)` and
     * `compress(WEBP, QUALITY)` inline in the manager; it sits here so every
     * Skia call is in one file.
     *
     * @return the WebP bytes, or null if the cover could not be drawn or encoded.
     */
    fun still(cover: Bitmap): ByteArray? = runCatching {
        scaled(cover, SIZE).use { square -> encodeWebp(square, QUALITY) }
    }.getOrNull()

    /**
     * [cover] stretched to [size] × [size], as Android's `Bitmap.scale` does —
     * not cropped, so a non-square sleeve keeps all of its picture.
     */
    private fun scaled(cover: Bitmap, size: Int): Image =
        Image.makeFromBitmap(cover).use { source ->
            Surface.makeRasterN32Premul(size, size).use { surface ->
                surface.canvas.drawImageRect(
                    source,
                    Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(size.toFloat(), size.toFloat()),
                    SCALE_SAMPLING,
                    null,
                    true,
                )
                surface.makeImageSnapshot()
            }
        }

    /**
     * One still WebP, lossy at [quality]: a RIFF around a single `VP8 `
     * bitstream, which is what [AnimatedWebP.codecChunk] reads. Every frame here
     * is opaque (the backdrop fills the canvas first), so libwebp writes no
     * alpha chunk, as on Android.
     */
    private fun encodeWebp(image: Image, quality: Int): ByteArray? =
        image.encodeToData(EncodedImageFormat.WEBP, quality)?.use { it.bytes }

    /** Android's (colours, positions, CLAMP) gradient arguments, in Skia's shape. */
    private fun gradient(colors: IntArray, positions: FloatArray?): Gradient =
        Gradient(
            Gradient.Colors(
                colors = Array(colors.size) { Color4f(colors[it]) },
                positions = positions,
                tileMode = FilterTileMode.CLAMP,
            ),
        )
}
