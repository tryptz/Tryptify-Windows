package tf.monochrome.desktop.ui

import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.decodeToImageVector
import org.jetbrains.compose.resources.decodeToSvgPainter
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.desktop.res.Res
import tf.monochrome.desktop.res.allDrawableResources

/**
 * Every bundled drawable decodes, through the decoders painterResource uses.
 *
 * A drawable is only parsed when something first draws it, and a parse error
 * there is not caught: it takes the whole app down. The Ko-fi and Patreon
 * logos did exactly that to the About tab. Porting them dropped Android's
 * `android:tint="?attr/…"` attribute together with the `>` that closed the
 * `<vector>` tag, and nothing read the files until the tab drew them.
 */
@OptIn(ExperimentalResourceApi::class)
class DrawableResourcesTest {

    @Test
    fun `every drawable decodes`() = runBlocking {
        val environment = getSystemResourceEnvironment()
        val density = Density(1f)
        val failures = Res.allDrawableResources.toSortedMap().mapNotNull { (name, resource) ->
            runCatching {
                val bytes = getDrawableResourceBytes(environment, resource)
                // The resource's path is internal to the library, so tell the
                // formats apart by their content, as the decoders would have to.
                val head = bytes.copyOf(minOf(bytes.size, 512)).decodeToString()
                when {
                    "<svg" in head -> bytes.decodeToSvgPainter(density)
                    head.trimStart().startsWith("<") -> bytes.decodeToImageVector(density)
                    else -> bytes.decodeToImageBitmap()
                }
            }.exceptionOrNull()?.let { "$name: $it" }
        }
        assertTrue("no drawables found", Res.allDrawableResources.isNotEmpty())
        assertTrue("drawables that fail to decode:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
