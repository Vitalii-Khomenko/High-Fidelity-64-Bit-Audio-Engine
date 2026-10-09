package com.aiproject.musicplayer.library

import org.junit.Assert.*
import org.junit.Test

class DlnaXmlRegressionTest {
    @Test fun `namespaces CDATA and nested XML entities are decoded once per layer`() {
        val soap = """<s:Envelope xmlns:s="urn:soap"><s:Body><u:Result xmlns:u="urn:upnp"><![CDATA[
            <DIDL-Lite xmlns:dc="urn:dc"><item><dc:title>Rock &amp; Roll</dc:title>
            <res duration="00:00:12.25">http://host/song?a=1&amp;b=2</res></item></DIDL-Lite>
            ]]></u:Result></s:Body></s:Envelope>"""
        val track = DlnaProtocol.parseBrowse(soap).single()
        assertEquals("Rock & Roll", track.title)
        assertEquals("http://host/song?a=1&b=2", track.url)
        assertEquals(12250L, track.durationMs)
    }
    @Test fun `device URLBase is used for relative control paths`() {
        val xml = """<root><URLBase>http://host:1234/base/</URLBase><service>
            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
            <controlURL>control?a=1&amp;b=2</controlURL></service></root>"""
        assertEquals("http://host:1234/base/control?a=1&b=2", DlnaProtocol.resolveControlUrl("http://host/device.xml", xml))
    }
    @Test fun `DTD documents cannot resolve local files`() {
        assertTrue(DlnaProtocol.parseBrowse("""<!DOCTYPE x [<!ENTITY a SYSTEM "file:///etc/passwd">]><x><Result>&a;</Result></x>""").isEmpty())
    }
}
