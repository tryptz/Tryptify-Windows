package tf.monochrome.desktop.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A station's homepage button opens the link through java.net.URI, which is
 * stricter than Android's Uri.parse. A homepage it rejects gets no button.
 */
class RadioBrowserStationTest {

    private fun station(homepage: String) = RadioBrowserStation(
        stationUuid = "8ed3ae14-1b53-4469-a8f8-be2be28af043",
        name = "Station",
        url = "https://stream.example/live",
        homepage = homepage,
        lastCheckOk = 1,
    )

    @Test
    fun `a homepage java net URI rejects is dropped`() {
        // Cut off by the directory's 200-character limit in the middle of "%E0%B8…".
        assertNull(station("http://www.example.th/%E0%B8%A").toDomain()?.homepage)
        assertNull(station("http://example.com/a page").toDomain()?.homepage)
        assertNull(station("http://example.com/{x}|y").toDomain()?.homepage)
    }

    @Test
    fun `a homepage that parses is kept, trimmed`() {
        assertEquals("https://radio.example/", station("  https://radio.example/ ").toDomain()?.homepage)
        assertEquals("http://example.th/%E0%B8%AA", station("http://example.th/%E0%B8%AA").toDomain()?.homepage)
    }

    @Test
    fun `a homepage that is not a web link is still dropped`() {
        assertNull(station("www.radio.example").toDomain()?.homepage)
        assertNull(station("").toDomain()?.homepage)
    }
}
