package com.aiproject.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaProtocolTest {

    @Test
    fun `resolveControlUrl keeps default http port valid`() {
        val xml = """
            <root>
              <serviceList>
                <service>
                  <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                  <controlURL>/MediaServer/ContentDirectory/Control</controlURL>
                </service>
              </serviceList>
            </root>
        """.trimIndent()

        val resolved = DlnaProtocol.resolveControlUrl("http://192.168.1.10/description.xml", xml)

        assertEquals("http://192.168.1.10/MediaServer/ContentDirectory/Control", resolved)
    }

    @Test
    fun `buildBrowseEnvelope escapes object id for xml safety`() {
        val envelope = DlnaProtocol.buildBrowseEnvelope("folder&1<test>")

        assertTrue(envelope.contains("<ObjectID>folder&amp;1&lt;test&gt;</ObjectID>"))
    }

    @Test
    fun `parseBrowse reads durations with milliseconds`() {
        val soap = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <Result>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;
                  &lt;item&gt;
                    &lt;dc:title&gt;Track&lt;/dc:title&gt;
                    &lt;res duration=&quot;0:01:02.345&quot;&gt;http://example.com/audio.flac&lt;/res&gt;
                  &lt;/item&gt;
                &lt;/DIDL-Lite&gt;</Result>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val tracks = DlnaProtocol.parseBrowse(soap)

        assertEquals(1, tracks.size)
        assertEquals(62_345L, tracks.first().durationMs)
        assertEquals("http://example.com/audio.flac", tracks.first().url)
    }
}
