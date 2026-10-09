package com.aiproject.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaBrowsePageTest {
    private fun envelope(didl: String, returned: Int, total: Int) = """
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
        <Result>${didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</Result>
        <NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches>
        </u:BrowseResponse></s:Body></s:Envelope>
    """.trimIndent()

    @Test fun `containers and audio items are separated, other items skipped`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="64$1" childCount="12"><dc:title>Albums</dc:title><upnp:class>object.container</upnp:class></container>
            <item id="1"><dc:title>Song</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>
              <res duration="0:03:05.500">http://10.0.0.2:8200/MediaItems/1.flac</res></item>
            <item id="2"><dc:title>Cover</dc:title><upnp:class>object.item.imageItem.photo</upnp:class>
              <res>http://10.0.0.2:8200/2.jpg</res></item>
            </DIDL-Lite>"""
        val page = DlnaProtocol.parseBrowsePage(envelope(didl, 3, 40))
        assertEquals(listOf(DlnaContainer("64$1", "Albums", 12)), page.containers)
        assertEquals(1, page.tracks.size)
        assertEquals(185_500L, page.tracks.single().durationMs)
        assertEquals(3, page.numberReturned)
        assertEquals(40, page.totalMatches)
    }

    private fun item(vararg res: Pair<String, String?>): String {
        val resources = res.joinToString("") { (url, mime) ->
            val info = mime?.let { " protocolInfo=\"http-get:*:$it:*\"" }.orEmpty()
            "<res$info>$url</res>"
        }
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="1"><dc:title>Song</dc:title>$resources</item></DIDL-Lite>"""
    }

    private fun chosen(vararg res: Pair<String, String?>): String? =
        DlnaProtocol.parseBrowsePage(envelope(item(*res), 1, 1)).tracks.singleOrNull()?.url

    @Test fun `a playable resource is preferred over an earlier unsupported one`() {
        assertEquals("http://h/song.mp3", chosen("http://h/song.aac" to "audio/aac", "http://h/song.mp3" to "audio/mpeg"))
        assertEquals("http://h/a.mp3", chosen("http://h/a.mp3" to "audio/mpeg", "http://h/a.flac" to "audio/flac"))
    }

    @Test fun `without an extension the protocolInfo mime decides`() {
        assertEquals("http://h/stream/2", chosen("http://h/stream/1" to "audio/aac", "http://h/stream/2" to "audio/flac"))
    }

    @Test fun `items with only unsupported resources are skipped`() {
        assertEquals(null, chosen("http://h/a.aac" to "audio/aac", "http://h/a.wma" to "audio/x-ms-wma"))
    }

    @Test fun `an unknown resource is a last resort`() {
        assertEquals("http://h/media?id=7", chosen("http://h/a.ogg" to "audio/ogg", "http://h/media?id=7" to null))
    }

    @Test fun `browse envelope carries paging`() {
        val soap = DlnaProtocol.buildBrowseEnvelope("12", startingIndex = 200, requestedCount = 50)
        assert(soap.contains("<StartingIndex>200</StartingIndex>"))
        assert(soap.contains("<RequestedCount>50</RequestedCount>"))
    }
}
