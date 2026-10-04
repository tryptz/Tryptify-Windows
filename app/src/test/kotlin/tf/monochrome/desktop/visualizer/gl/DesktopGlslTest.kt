package tf.monochrome.desktop.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.desktop.visualizer.AMBIENT_FRAGMENT_SHADER
import tf.monochrome.desktop.visualizer.AMBIENT_VERTEX_SHADER

/**
 * The ambient shaders as the desktop compiles them.
 *
 * docs/ui-invariants.md binds the GLSL and its Kotlin twin in
 * `AmbientVisualizer.kt` together; these pin that the desktop's GLSL 3.30 is
 * the Android app's GLSL ES 3.00 with nothing changed but the version line, so
 * the twin still describes the shader that actually runs.
 */
class DesktopGlslTest {

    @Test
    fun `the version line becomes 330 core`() {
        assertTrue(desktopGlsl(AMBIENT_VERTEX_SHADER).startsWith("#version 330 core\n"))
        assertTrue(desktopGlsl(AMBIENT_FRAGMENT_SHADER).startsWith("#version 330 core\n"))
    }

    @Test
    fun `everything after the version line is the Android shader verbatim`() {
        for (source in listOf(AMBIENT_VERTEX_SHADER, AMBIENT_FRAGMENT_SHADER)) {
            assertEquals(source.substringAfter('\n'), desktopGlsl(source).substringAfter('\n'))
        }
    }

    @Test
    fun `nothing ES-only is left behind`() {
        for (source in listOf(AMBIENT_VERTEX_SHADER, AMBIENT_FRAGMENT_SHADER)) {
            assertTrue(" es" !in desktopGlsl(source).lineSequence().first())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a shader that is not GLSL ES 3_00 is refused rather than half-converted`() {
        desktopGlsl("#version 100\nvoid main() {}\n")
    }
}
