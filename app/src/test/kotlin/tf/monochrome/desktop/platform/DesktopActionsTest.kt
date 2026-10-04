package tf.monochrome.desktop.platform

import android.content.ActivityNotFoundException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shim's promise: a launch the OS refuses reaches the caller as
 * ActivityNotFoundException, the one exception the ported callers catch.
 * Desktop.browse's IOException used to go past them, and out of the About
 * tab's Ko-fi and Patreon buttons it closed the window.
 */
class DesktopActionsTest {

    @Test
    fun `an IOException from the OS becomes ActivityNotFoundException`() {
        val failure = IOException("Failed to open https://ko-fi.com/trypt")
        val thrown = thrownBy { DesktopActions.launching("open link") { throw failure } }
        assertTrue(thrown is ActivityNotFoundException)
        assertSame(failure, thrown.cause)
    }

    @Test
    fun `a link java net URI rejects becomes ActivityNotFoundException`() {
        // A station homepage the directory cut short in the middle of an escape.
        val thrown = thrownBy { DesktopActions.launching("open link") { URI("http://example.th/%E0%B8%A") } }
        assertTrue(thrown is ActivityNotFoundException)
        assertTrue(thrown.cause is URISyntaxException)
    }

    @Test
    fun `ActivityNotFoundException passes through as it is`() {
        val original = ActivityNotFoundException("no browser")
        assertSame(original, thrownBy { DesktopActions.launching("open link") { throw original } })
    }

    @Test
    fun `a launch that works returns its value`() {
        assertEquals(7, DesktopActions.launching("count") { 7 })
    }

    private fun thrownBy(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("expected an exception")
        throw AssertionError()
    }
}
