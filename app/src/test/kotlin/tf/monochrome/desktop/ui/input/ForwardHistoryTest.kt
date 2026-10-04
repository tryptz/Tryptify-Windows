package tf.monochrome.desktop.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardHistoryTest {

    @Test
    fun `forward reopens what back closed, most recent first`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("album/1"))
        history.wentBack(listOf("artist/2"))

        assertEquals("artist/2", history.forward())
        history.arrived("artist/2")
        assertEquals("album/1", history.forward())
        history.arrived("album/1")
        assertNull(history.forward())
        assertFalse(history.canGoForward)
    }

    @Test
    fun `a back over several screens comes forward in the order they were opened`() {
        val history = ForwardHistory<String>()
        // home > a > b > c, then straight back to home.
        history.wentBack(listOf("a", "b", "c"))

        assertEquals("a", history.forward())
        history.arrived("a")
        assertEquals("b", history.forward())
        history.arrived("b")
        assertEquals("c", history.forward())
    }

    @Test
    fun `arriving where forward went keeps the rest`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("a"))
        history.wentBack(listOf("b"))

        history.arrived(history.forward()!!)

        assertTrue(history.canGoForward)
        assertEquals("a", history.forward())
    }

    @Test
    fun `a fresh navigation clears the history`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("a"))
        history.wentBack(listOf("b"))

        history.arrived("somewhere/else")

        assertFalse(history.canGoForward)
        assertNull(history.forward())
    }

    @Test
    fun `opening the forward target by hand is the same step forward`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("a"))
        history.wentBack(listOf("b"))

        history.arrived("b")

        assertEquals("a", history.forward())
    }

    @Test
    fun `a landing reported after the back is not a fresh navigation`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("page:downloads"), landing = "page:home")

        history.arrived("page:home")

        assertEquals("page:downloads", history.forward())
    }

    @Test
    fun `the landing is only excused once`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("page:downloads"), landing = "page:home")
        history.arrived("page:home")

        history.arrived("page:radio")

        assertFalse(history.canGoForward)
    }

    @Test
    fun `clear forgets everything`() {
        val history = ForwardHistory<String>()
        history.wentBack(listOf("a", "b"))
        history.clear()
        assertNull(history.forward())
    }

    @Test
    fun `follow records a pop and forgets on an unnamed one`() {
        val history = ForwardHistory<String>()
        history.follow(StackMove.Popped(listOf("a")))
        assertTrue(history.canGoForward)

        history.follow(StackMove.Popped(listOf("b", null)))
        assertFalse(history.canGoForward)
    }

    @Test
    fun `follow treats an unnamed push as a fresh navigation`() {
        val history = ForwardHistory<String>()
        history.follow(StackMove.Popped(listOf("a")))
        history.follow(StackMove.Pushed(null))
        assertFalse(history.canGoForward)
    }

    @Test
    fun `follow ignores a stack that did not move`() {
        val history = ForwardHistory<String>()
        history.follow(StackMove.Popped(listOf("a")))
        history.follow(StackMove.Unchanged)
        assertTrue(history.canGoForward)
    }
}

class BackStackMirrorTest {

    @Test
    fun `the first look is a push`() {
        val mirror = BackStackMirror<String>()
        assertEquals(StackMove.Pushed("home"), mirror.moved("1", "home", null))
    }

    @Test
    fun `a new entry on top is a push`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        assertEquals(StackMove.Pushed("album/5"), mirror.moved("2", "album/5", "1"))
    }

    @Test
    fun `an older entry back on top is a pop of everything above it`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        mirror.moved("2", "album/5", "1")
        mirror.moved("3", "artist/9", "2")

        assertEquals(StackMove.Popped(listOf("album/5", "artist/9")), mirror.moved("1", "home", null))
    }

    @Test
    fun `the same top seen again is no move`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        mirror.moved("2", "album/5", "1")
        assertEquals(StackMove.Unchanged, mirror.moved("2", "album/5", "1"))
    }

    @Test
    fun `the same entry with new arguments is a push`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        mirror.moved("2", "settings?tab=0", "1")
        assertEquals(StackMove.Pushed("settings?tab=4"), mirror.moved("2", "settings?tab=4", "1"))
    }

    @Test
    fun `a push that popped others first drops them from the mirror`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        mirror.moved("2", "settings?tab=0", "1")
        mirror.moved("3", "equalizer", "2")
        // popUpTo(settings, inclusive) then navigate: the new entry sits on home.
        assertEquals(StackMove.Pushed("settings?tab=4"), mirror.moved("4", "settings?tab=4", "1"))

        // Back from it pops only that entry, not the two popUpTo already took.
        assertEquals(StackMove.Popped(listOf("settings?tab=4")), mirror.moved("1", "home", null))
    }

    @Test
    fun `a pop keeps unnamed entries as nulls`() {
        val mirror = BackStackMirror<String>()
        mirror.moved("1", "home", null)
        mirror.moved("2", null, "1")
        assertEquals(StackMove.Popped<String>(listOf(null)), mirror.moved("1", "home", null))
    }
}

class FillRoutePatternTest {

    @Test
    fun `a route without placeholders comes back as it is`() {
        assertEquals("now_playing", fillRoutePattern("now_playing", emptyMap()))
    }

    @Test
    fun `path placeholders are filled`() {
        assertEquals(
            "local_facet/genre/Jazz%20Rock",
            fillRoutePattern("local_facet/{facet}/{value}", mapOf("facet" to "genre", "value" to "Jazz%20Rock")),
        )
    }

    @Test
    fun `query placeholders are filled`() {
        assertEquals(
            "artist/42?name=Nina%20Simone",
            fillRoutePattern("artist/{artistId}?name={name}", mapOf("artistId" to "42", "name" to "Nina%20Simone")),
        )
        assertEquals("settings?tab=4", fillRoutePattern("settings?tab={tab}", mapOf("tab" to "4")))
    }

    @Test
    fun `an empty query value is kept`() {
        assertEquals("artist/7?name=", fillRoutePattern("artist/{artistId}?name={name}", mapOf("artistId" to "7", "name" to "")))
    }

    @Test
    fun `a missing query value is left out`() {
        assertEquals("settings", fillRoutePattern("settings?tab={tab}", emptyMap()))
        assertEquals(
            "discover/chart/pop",
            fillRoutePattern("discover/chart/{genreId}?name={name}", mapOf("genreId" to "pop")),
        )
    }

    @Test
    fun `a missing path value means the route cannot be built`() {
        assertNull(fillRoutePattern("album/{albumId}", emptyMap()))
    }

    @Test
    fun `an empty path value means the route cannot be built`() {
        assertNull(fillRoutePattern("local_facet/{facet}/{value}", mapOf("facet" to "genre", "value" to "")))
    }

    @Test
    fun `values are inserted literally`() {
        assertEquals("folder/%24HOME%2Fmusic", fillRoutePattern("folder/{folderPath}", mapOf("folderPath" to "%24HOME%2Fmusic")))
        assertEquals("playlist/a\$1", fillRoutePattern("playlist/{playlistId}", mapOf("playlistId" to "a\$1")))
    }
}
