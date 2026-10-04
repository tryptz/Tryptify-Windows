package androidx.compose.ui.text.font

import android.content.res.AssetManager
import java.io.File
import androidx.compose.ui.text.platform.Font as PlatformFont
import tf.monochrome.desktop.res.FontKey

/*
 * Android's three Font factories — by resource id, by asset path and by
 * file — under their Android package, so the theme code keeps its imports.
 * Each one resolves to Compose for Desktop's platform Font.
 */

/** `Font(R.font.inter_regular, FontWeight.Normal)`: a font bundled under resources/fonts. */
fun Font(resId: FontKey, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    tf.monochrome.desktop.res.Font(resId, weight, style)

/** `Font("fonts/x.ttf", context.assets, weight)`: an asset path, read from the classpath. */
@Suppress("UNUSED_PARAMETER")
fun Font(path: String, assetManager: AssetManager, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
    val bytes = assetManager.open(path).use { it.readBytes() }
    return PlatformFont(identity = "asset:$path-${weight.weight}-$style", data = bytes, weight = weight, style = style)
}

/** `Font(file, weight)`: a user-imported font file on disk. */
fun Font(file: File, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    PlatformFont(file = file, weight = weight, style = style)
