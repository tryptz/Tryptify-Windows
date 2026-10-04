package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.DrawableResource
import tf.monochrome.desktop.res.PluralKey
import tf.monochrome.desktop.res.StringKey

/*
 * Android's resource composables, under their Android package so the screens
 * keep `import androidx.compose.ui.res.stringResource` unchanged. Compose for
 * Desktop has no resource ids; R.string.x is a StringKey into the translation
 * tables (res/Strings.kt), and R.drawable.x is a Compose Multiplatform
 * DrawableResource. These overloads sit next to Compose's own desktop
 * painterResource(String) without clashing, because the parameter types differ.
 */

@Composable
fun stringResource(id: StringKey): String = tf.monochrome.desktop.res.stringResource(id)

@Composable
fun stringResource(id: StringKey, vararg formatArgs: Any?): String =
    tf.monochrome.desktop.res.stringResource(id, *formatArgs)

@Composable
fun pluralStringResource(id: PluralKey, count: Int): String =
    tf.monochrome.desktop.res.pluralStringResource(id, count)

@Composable
fun pluralStringResource(id: PluralKey, count: Int, vararg formatArgs: Any?): String =
    tf.monochrome.desktop.res.pluralStringResource(id, count, *formatArgs)

@Composable
fun painterResource(id: DrawableResource): Painter = org.jetbrains.compose.resources.painterResource(id)
