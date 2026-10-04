package tf.monochrome.desktop.data.presence

import android.util.Log
import androidx.compose.ui.graphics.Color
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.kmpalette.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The two cover colours the presence card reads: the vibrant accent that tints
 * the spinning disc's backdrop and picks the badge's hue bucket, and the
 * dominant colour each of those falls back to.
 *
 * Desktop: on Android both come from `ui.theme.DynamicColorExtractor`, the
 * player's own palette pass, so the card costs no analysis of its own. That
 * file is not in the desktop build yet (it belongs to the theme, and half of it
 * is composables), so this reproduces exactly the part the presence code reads:
 * the same kmpalette port of androidx.palette, the same 256-pixel analysis
 * decode through the app's image loader, the same 24-colour quantiser and the
 * same swatch fallback chains for `vibrant` and `dominant`. A cover therefore
 * lands in the same hue bucket and gets the same tint either way.
 *
 * Once DynamicColorExtractor is ported, DiscordPresenceManager should call it
 * again (one cache, one pass per cover for the whole app) and this file goes.
 * [CoverColours] has the field names and types of its `CoverPalette` so that
 * swap is the call and nothing else.
 */
internal object PresencePalette {

    /** The `dominant` and `vibrant` fields of DynamicColorExtractor's CoverPalette. */
    data class CoverColours(val dominant: Color?, val vibrant: Color?)

    /** Bounded, so a long session cycling through covers cannot grow it without limit. */
    private val cache = object : LinkedHashMap<String, CoverColours>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverColours>?) =
            size > CACHE_SIZE
    }

    suspend fun extract(coverUrl: String?): CoverColours? {
        if (coverUrl.isNullOrBlank()) return null
        synchronized(cache) { cache[coverUrl] }?.let { return it }

        val bitmap = loadBitmap(coverUrl) ?: return null
        val palette = withContext(Dispatchers.Default) {
            val pixels = argbPixels(bitmap) ?: return@withContext null
            Palette.from(pixels, bitmap.width, bitmap.height)
                .maximumColorCount(MAX_COLOR_COUNT)
                .generate()
        } ?: return null

        // The player's wash leads with the *dominant* colour — the most-present
        // one reads as "this album" rather than the loudest accent in it.
        val dominant = (palette.dominantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch)
            ?.let { Color(it.rgb) }
        val vibrant = (palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch)
            ?.let { Color(it.rgb) }
        if (dominant == null && vibrant == null) return null

        val result = CoverColours(dominant = dominant, vibrant = vibrant)
        synchronized(cache) { cache[coverUrl] = result }
        return result
    }

    private suspend fun loadBitmap(url: String): Bitmap? = try {
        // The app-wide loader (MonochromeApp installs it), so a cover the
        // player has already drawn comes out of its caches.
        val context = PlatformContext.INSTANCE
        val request = ImageRequest.Builder(context)
            .data(url)
            // Big enough that the palette has real pixels to quantise (a
            // thumbnail-sized decode returns empty swatches and everything
            // falls back to defaults), small enough to decode cheaply.
            .size(ANALYSIS_SIZE, ANALYSIS_SIZE)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        if (result is SuccessResult) result.image.toBitmap() else null
    } catch (e: Exception) {
        Log.w(TAG, "palette cover load failed", e)
        null
    }

    /**
     * The bitmap as unpremultiplied ARGB ints, which is what Android's
     * `Bitmap.getPixels` hands the palette and what kmpalette takes.
     */
    private fun argbPixels(bitmap: Bitmap): IntArray? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        // BGRA in memory is ARGB read as a little-endian int, byte by byte.
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bytes = bitmap.readPixels(info, w * 4, 0, 0) ?: return null
        return IntArray(w * h) { i ->
            val o = i * 4
            ((bytes[o + 3].toInt() and 0xFF) shl 24) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                (bytes[o].toInt() and 0xFF)
        }
    }

    private const val TAG = "DiscordPresence"
    private const val MAX_COLOR_COUNT = 24
    private const val ANALYSIS_SIZE = 256
    private const val CACHE_SIZE = 128
}
