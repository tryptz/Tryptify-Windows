package tf.monochrome.desktop.platform.windows

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowChromeTest {
    @Test
    fun `DWM colours are COLORREF, blue in the high byte and no alpha`() {
        assertEquals(0x00332211, WindowChrome.colorRef(0xFF112233.toInt()))
        assertEquals(0x000000FF, WindowChrome.colorRef(0x80FF0000.toInt()))
    }

    @Test
    fun `apply is a no-op off Windows and for a null window`() {
        WindowChrome.apply(0L, dark = true, captionArgb = 0xFF000000.toInt())
    }
}
