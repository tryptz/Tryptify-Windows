package tf.monochrome.desktop.ui.input

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListKeysTest {

    @Test
    fun `home goes to the first item and end to the last`() {
        assertEquals(0, homeEndTarget(Key.MoveHome, 40))
        assertEquals(39, homeEndTarget(Key.MoveEnd, 40))
    }

    @Test
    fun `an empty list still gets a valid index`() {
        assertEquals(0, homeEndTarget(Key.MoveHome, 0))
        assertEquals(0, homeEndTarget(Key.MoveEnd, 0))
    }

    @Test
    fun `other keys are left to the list`() {
        assertNull(homeEndTarget(Key.PageDown, 40))
        assertNull(homeEndTarget(Key.A, 40))
    }
}
