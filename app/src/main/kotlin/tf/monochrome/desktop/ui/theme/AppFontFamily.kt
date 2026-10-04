package tf.monochrome.desktop.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import java.io.File

/**
 * Builds the [FontFamily] for a stored font id, or null to mean "use the
 * built-in default" (Inter).
 *
 * Two id shapes, because there are two kinds of font:
 *  - `asset:fonts/x.ttf` — one Tryptify ships, read out of the APK
 *    (Desktop: out of the app's own resources, under `fonts/`).
 *  - an absolute path    — one the user imported into `filesDir/custom_fonts`.
 *
 * Both are declared as five weights over a single file. That reads oddly but is
 * correct for variable fonts, which every bundled font is: Compose derives the
 * `wght` variation axis from the declared [FontWeight], so one file genuinely
 * renders Light through Bold. For a static font the axis is ignored and the
 * platform synthesises the missing weights, which is the behaviour imported
 * fonts had before any of this existed.
 *
 * Returns null rather than throwing on a missing or unreadable font, so a
 * deleted file or a bad import falls back to the default instead of taking the
 * whole interface down.
 */
@Suppress("UNUSED_PARAMETER") // Desktop: the bundled fonts are not read through context.assets; see bundledFont.
fun loadAppFontFamily(context: Context, fontId: String?): FontFamily? {
    if (fontId.isNullOrBlank()) return null
    return runCatching {
        if (BundledFonts.isBundled(fontId)) {
            val path = BundledFonts.assetPathOf(fontId)
            // Desktop: checked up front, because a resource font is opened at its
            // first draw, where a missing one would throw instead of falling back.
            checkNotNull(BundledFonts::class.java.classLoader.getResource(path)) { "not bundled: $path" }
            FontFamily(
                bundledFont(path, FontWeight.Light),
                bundledFont(path, FontWeight.Normal),
                bundledFont(path, FontWeight.Medium),
                bundledFont(path, FontWeight.SemiBold),
                bundledFont(path, FontWeight.Bold),
            )
        } else {
            val file = File(fontId)
            if (!file.exists()) return null
            FontFamily(
                importedFont(file, FontWeight.Light),
                importedFont(file, FontWeight.Normal),
                importedFont(file, FontWeight.Medium),
                importedFont(file, FontWeight.SemiBold),
                importedFont(file, FontWeight.Bold),
            )
        }
    }.getOrNull()
}

// Desktop: Android's Font(path, assets, weight) and Font(file, weight) set a
// variable font's `wght` axis from the declared weight by default. The desktop
// factories leave the axis at the font's default instance unless told, which
// would draw Light through Bold at one weight, so these two tell them. The
// bundled fonts are classpath resources under fonts/ (app/src/main/resources),
// which is the path their ids already carry, not an assets/ folder.
private fun bundledFont(path: String, weight: FontWeight): Font =
    PlatformFont(path, weight, FontStyle.Normal, FontVariation.Settings(weight, FontStyle.Normal))

private fun importedFont(file: File, weight: FontWeight): Font =
    PlatformFont(file, weight, FontStyle.Normal, FontVariation.Settings(weight, FontStyle.Normal))
