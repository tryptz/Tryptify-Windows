package tf.monochrome.desktop.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import java.util.concurrent.ConcurrentHashMap

/**
 * Drop-in replacements for `androidx.compose.ui.res.stringResource`,
 * `pluralStringResource` and `Font(R.font.x, …)`, so the screens ported from
 * Android keep their call sites and change only an import.
 */
@Composable
fun stringResource(key: StringKey): String {
    val lang by Strings.language.collectAsState()
    return Strings.get(key.name, lang)
}

@Composable
fun stringResource(key: StringKey, vararg formatArgs: Any?): String {
    val lang by Strings.language.collectAsState()
    return Strings.get(key.name, lang, *formatArgs)
}

@Composable
fun pluralStringResource(key: PluralKey, count: Int): String {
    val lang by Strings.language.collectAsState()
    return Strings.plural(key.name, count, lang)
}

@Composable
fun pluralStringResource(key: PluralKey, count: Int, vararg formatArgs: Any?): String {
    val lang by Strings.language.collectAsState()
    return Strings.plural(key.name, count, lang, *formatArgs)
}

private val fontBytes = ConcurrentHashMap<String, ByteArray>()

private fun fontData(file: String): ByteArray = fontBytes.getOrPut(file) {
    val stream = Strings::class.java.getResourceAsStream("/fonts/$file")
        ?: error("missing bundled font resources/fonts/$file")
    stream.use { it.readBytes() }
}

/**
 * `Font(R.font.inter_regular, FontWeight.Normal)`: a bundled font file as a
 * Compose [Font]. The weight is also passed as a variation setting, as
 * Android's resource fonts do, so a variable font draws at that weight
 * rather than at its default instance.
 */
fun Font(key: FontKey, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    PlatformFont(
        identity = "${key.file}-${weight.weight}-$style",
        data = fontData(key.file),
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(weight, style),
    )

/** Bytes of a font on the classpath, or null; for the asset-path factory. */
internal fun classpathFontData(path: String): ByteArray? = fontBytes[path] ?: Strings::class.java
    .getResourceAsStream("/" + path.removePrefix("/"))?.use { it.readBytes() }?.also { fontBytes[path] = it }

/** `Font("fonts/x.ttf", assets, weight)` — the asset-path form the font picker uses. */
fun Font(assetPath: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    Font(FontKey(assetPath.removePrefix("fonts/")), weight, style)
