package tf.monochrome.desktop.data.local.tags

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use

/**
 * Where extracted cover art lives, and what it is named. Two things about the
 * old store made the library rescan itself on almost every launch.
 *
 * It lived in `cacheDir`, which Android is entitled to empty whenever it wants
 * — and did. Every eviction left Room rows pointing at vanished JPEGs, which
 * the startup check answered with a *full library scan*. It lives in [filesDir]
 * now: app data, which the OS does not reclaim and "Clear cache" does not touch.
 *
 * And it was keyed by `MD5(filePath)`, so a 500-track album wrote the same
 * cover 500 times at whatever size it was embedded at — often 3000x3000. That
 * is what made it worth reclaiming. Art is keyed by the hash of its own encoded
 * bytes now, one file per cover, downscaled to [MAX_EDGE_PX] on the way in.
 *
 * Keys stay absolute paths: the column already holds paths to sidecar covers
 * and raw audio files, and every reader treats it as "a path to an image".
 *
 * Desktop: the root is `filesDir/artwork` through the Context shim, i.e.
 * `AppPaths.dataDir/artwork` (`%LOCALAPPDATA%\Tryptify\data\artwork`). Keys
 * are native absolute paths (`File.absolutePath`, backslashes on Windows), not
 * the forward-slash form library track paths use: Coil reads a string
 * `C:\…\x.jpg` as a file, but would read `C:/…/x.jpg` as a URI with scheme
 * "C". Decoding, downscaling and the JPEG encode go through Skia — the
 * decoder Compose and Coil already use on the desktop — in place of
 * `BitmapFactory` and `Bitmap.compress`.
 */
@Singleton
class ArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The durable store. Everything written from now on lands here. */
    val root: File by lazy { File(context.filesDir, ArtworkKeys.DIR_NAME).also { it.mkdirs() } }

    /**
     * Where [ArtworkStoreMigration] parks art carried over from the old
     * cache-keyed store. Same durability as [root]; the separate directory only
     * keeps the two naming schemes tellable apart, since both are `<hex>.jpg`.
     * `needsReRead()` re-reads a row still pointing here, so the next manual
     * rescan compacts it a row at a time.
     */
    val legacyRoot: File by lazy { File(root, ArtworkKeys.LEGACY_DIR_NAME) }

    /**
     * Store [artworkBytes] and return the absolute path for `artworkCacheKey`,
     * or [fallbackPath] if it could not be written. Identical covers collapse
     * onto one file: the name is the hash of the *encoded* bytes, so it
     * describes what is on disk rather than which track was read first.
     */
    fun put(artworkBytes: ByteArray, fallbackPath: String): String {
        return try {
            val encoded = encode(artworkBytes) ?: return fallbackPath
            val name = ArtworkKeys.nameFor(encoded)
            val file = File(root, name)
            if (!file.exists()) {
                // The lazy mkdirs runs once per process and the directory can
                // be gone by now ("Clear storage" mid-process); without this
                // the write fails silently and every track falls back to its
                // raw file path.
                root.mkdirs()
                // Write beside the target and rename in. A half-written JPEG
                // under a content hash is permanent: the name claims those
                // exact bytes, so nothing ever rewrites it and every track
                // sharing that cover renders torn for good.
                //
                // Desktop: a temp name of its own per writer. The scan reads an
                // album's tracks in parallel, so two workers routinely store the
                // same cover at once; sharing one "$name.tmp" let one rename the
                // file while the other still had it open, which Windows refuses.
                // Losing the rename to an identical copy is not a failure.
                val tmp = File.createTempFile("$name.", ".tmp", root)
                FileOutputStream(tmp).use { it.write(encoded) }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    if (!file.exists()) return fallbackPath
                }
            }
            file.absolutePath
        } catch (_: Exception) {
            fallbackPath
        }
    }

    /**
     * Decode, downscale to [MAX_EDGE_PX] on the longest edge, re-encode as JPEG.
     * Null when the bytes are not a decodable image — the one case the caller
     * must read as "no art" rather than a write failure.
     *
     * Android decoded at a power-of-two `inSampleSize` first and then landed
     * the size exactly; Skia decodes lazily and samples straight to the target
     * size in one draw, so there is one step where there were two.
     */
    private fun encode(artworkBytes: ByteArray): ByteArray? {
        val image = try {
            Image.makeFromEncoded(artworkBytes)
        } catch (_: Exception) {
            return null
        }
        try {
            val srcWidth = image.width
            val srcHeight = image.height
            if (srcWidth <= 0 || srcHeight <= 0) return null
            val (width, height) = scaledSize(srcWidth, srcHeight)

            val bitmap = Bitmap()
            try {
                if (!bitmap.allocN32Pixels(width, height)) return null
                Canvas(bitmap).use { canvas ->
                    canvas.drawImageRect(
                        image = image,
                        srcLeft = 0f,
                        srcTop = 0f,
                        srcRight = srcWidth.toFloat(),
                        srcBottom = srcHeight.toFloat(),
                        dstLeft = 0f,
                        dstTop = 0f,
                        dstRight = width.toFloat(),
                        dstBottom = height.toFloat(),
                        samplingMode = SamplingMode.MITCHELL,
                        paint = null,
                        strict = false,
                    )
                }
                // JPEG has no alpha; a transparent PNG cover comes out over
                // black, as Bitmap.compress(JPEG) wrote it on Android.
                val scaled = Image.makeFromBitmap(bitmap)
                try {
                    return scaled.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
                } finally {
                    scaled.close()
                }
            } finally {
                bitmap.close()
            }
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    /** [srcWidth] x [srcHeight] brought down to [MAX_EDGE_PX] on the longest edge; never up. */
    private fun scaledSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= MAX_EDGE_PX) return srcWidth to srcHeight
        val ratio = MAX_EDGE_PX.toFloat() / longest
        val width = (srcWidth * ratio).toInt().coerceAtLeast(1)
        val height = (srcHeight * ratio).toInt().coerceAtLeast(1)
        return width to height
    }

    /**
     * Delete stored art no row points at any more. Used to be Android's job —
     * the store was a cache, so eviction bounded it. It is app data now, so a
     * deleted album would otherwise leave its cover behind forever.
     *
     * [referencedKeys] is every `artworkCacheKey` in the database, sidecar and
     * raw-file paths included; those simply don't match a file in the store.
     * Returns the number of files deleted.
     */
    fun sweepOrphans(referencedKeys: Collection<String>): Int {
        val keep = referencedKeys.toHashSet()
        var deleted = 0
        for (dir in listOf(root, legacyRoot)) {
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) continue
                if (file.absolutePath in keep) continue
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    companion object {
        /**
         * Longest edge of a stored cover. The player hero is the largest
         * surface art is drawn on and remote art is fetched at 640 px for the
         * same surfaces, so this is headroom rather than a target.
         */
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 85
    }
}
