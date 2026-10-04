package androidx.compose.ui.text.font

import android.content.res.AssetManager
import java.io.File
import java.io.FileNotFoundException
import androidx.compose.ui.text.platform.Font as PlatformFont
import tf.monochrome.desktop.res.FontKey
import tf.monochrome.desktop.res.classpathFontData

/*
 * Android's three Font factories (by resource id, by asset path and by file)
 * under their Android package, so the theme code keeps its imports. Each one
 * resolves to Compose for Desktop's platform Font, and each passes the weight
 * and style as variation settings, which is Android's default for all three:
 * without it a variable font draws every weight at its default instance.
 */

/** `Font(R.font.inter_regular, FontWeight.Normal)`: a font bundled under resources/fonts. */
fun Font(resId: FontKey, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    tf.monochrome.desktop.res.Font(resId, weight, style)

/**
 * `Font("fonts/x.ttf", context.assets, weight)`. Android kept these fonts in
 * the APK's assets; the desktop build bundles the same files once, under
 * resources/fonts, so an asset path that is not under assets/ is looked up
 * at the classpath root too.
 */
@Suppress("UNUSED_PARAMETER")
fun Font(path: String, assetManager: AssetManager, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font {
    val bytes = classpathFontData("assets/$path") ?: classpathFontData(path)
        ?: throw FileNotFoundException("font asset $path is neither under assets/ nor on the classpath")
    return PlatformFont(
        identity = "asset:$path-${weight.weight}-$style",
        data = bytes,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(weight, style),
    )
}

/** `Font(file, weight)`: a user-imported font file on disk. */
fun Font(file: File, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    PlatformFont(
        identity = "file:${file.absolutePath}:${file.lastModified()}-${weight.weight}-$style",
        data = file.readBytes(),
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(weight, style),
    )
