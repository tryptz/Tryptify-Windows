package tf.monochrome.desktop.player.engine

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineDashManifestTest {
    private val mpd = """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"><Period/></MPD>"""

    @Test
    fun `the manifest StreamResolver inlines comes back byte for byte`() {
        // The exact form StreamResolver.inlineDashUri builds.
        val uri = "data:application/dash+xml;base64," + Base64.encodeToString(mpd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        assertEquals(mpd, PlaybackModule.inlineDashManifest(uri))
    }

    @Test
    fun `other data URIs are not mistaken for a manifest`() {
        assertNull(PlaybackModule.inlineDashManifest("data:text/plain;base64,aGVsbG8="))
        assertNull(PlaybackModule.inlineDashManifest("https://example.com/a.mpd"))
    }
}
