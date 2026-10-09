package com.aiproject.musicplayer.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererProtocolTest {
    private class FakeHost : RendererHost {
        val calls = mutableListOf<String>()
        var status = RendererStatus(TransportState.PLAYING, "http://h/a.flac", "", "", "", 65_500, 200_000, 40, false)
        override fun status() = status
        override fun setUri(uri: String, metadata: String) { calls += "uri $uri ${RendererProtocol.parseDidl(metadata).title}" }
        override fun setNextUri(uri: String, metadata: String) { calls += "next $uri" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun setVolume(percent: Int) { calls += "volume $percent" }
        override fun setMute(muted: Boolean) { calls += "mute $muted" }
    }

    private fun soap(service: String, action: String, args: String) = """<?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
        <u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">$args</u:$action>
        </s:Body></s:Envelope>"""

    private val didl = "&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; " +
        "xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;&lt;item&gt;&lt;dc:title&gt;Song&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"

    @Test fun `a control point sets a uri with metadata and plays it`() {
        val host = FakeHost()
        val control = RendererControl(host)
        val r = control.control("AVTransport", soap("AVTransport", "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>http://192.168.1.5:8200/x.flac</CurrentURI><CurrentURIMetaData>$didl</CurrentURIMetaData>"))
        assertEquals(200, r.code)
        assertTrue(r.body.contains("SetAVTransportURIResponse"))
        assertEquals(200, control.control("AVTransport", soap("AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")).code)
        assertEquals(200, control.control("AVTransport", soap("AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:05.250</Target>")).code)
        assertEquals(listOf("uri http://192.168.1.5:8200/x.flac Song", "play", "seek 65250"), host.calls)
    }

    @Test fun `local files and bad arguments are refused with UPnP faults`() {
        val control = RendererControl(FakeHost())
        val file = control.control("AVTransport", soap("AVTransport", "SetAVTransportURI", "<CurrentURI>file:///sdcard/x.flac</CurrentURI>"))
        assertEquals(500, file.code)
        assertTrue(file.body.contains("<errorCode>402</errorCode>"))
        val seek = control.control("AVTransport", soap("AVTransport", "Seek", "<Unit>TRACK_NR</Unit><Target>2</Target>"))
        assertTrue(seek.body.contains("<errorCode>402</errorCode>"))
        assertTrue(control.control("AVTransport", soap("AVTransport", "Record", "")).body.contains("<errorCode>401</errorCode>"))
        assertEquals(404, control.control("Nope", soap("AVTransport", "Play", "")).code)
        assertTrue(control.control("AVTransport", "<html>").body.contains("401"))
    }

    @Test fun `state queries report the player`() {
        val control = RendererControl(FakeHost())
        val info = control.control("AVTransport", soap("AVTransport", "GetTransportInfo", "<InstanceID>0</InstanceID>")).body
        assertTrue(info.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        val pos = control.control("AVTransport", soap("AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>")).body
        assertTrue(pos.contains("<RelTime>0:01:05</RelTime>"))
        assertTrue(pos.contains("<TrackDuration>0:03:20</TrackDuration>"))
        val vol = control.control("RenderingControl", soap("RenderingControl", "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")).body
        assertTrue(vol.contains("<CurrentVolume>40</CurrentVolume>"))
        val sink = control.control("ConnectionManager", soap("ConnectionManager", "GetProtocolInfo", "")).body
        assertTrue(sink.contains("http-get:*:audio/flac:*"))
    }

    @Test fun `volume and mute`() {
        val host = FakeHost()
        val control = RendererControl(host)
        control.control("RenderingControl", soap("RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>150</DesiredVolume>"))
        control.control("RenderingControl", soap("RenderingControl", "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>"))
        assertEquals(listOf("volume 100", "mute true"), host.calls)
    }

    @Test fun `times, didl, search targets and events`() {
        assertEquals(3_723_000L, RendererProtocol.parseTime("1:02:03"))
        assertEquals(185_500L, RendererProtocol.parseTime("0:03:05.500"))
        assertEquals(42_000L, RendererProtocol.parseTime("42"))
        assertNull(RendererProtocol.parseTime("x:1"))
        assertEquals("1:02:03", RendererProtocol.time(3_723_999L))
        val info = RendererProtocol.parseDidl("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item><dc:title>T</dc:title><upnp:artist>A</upnp:artist><upnp:album>B</upnp:album></item></DIDL-Lite>""")
        assertEquals(DidlInfo("T", "A", "B", ""), info)
        assertEquals(DidlInfo(), RendererProtocol.parseDidl("<!DOCTYPE x><x/>"))   // no DTDs
        assertEquals(6, RendererProtocol.searchTargets("ssdp:all", "u").size)
        assertEquals(listOf(RendererProtocol.AVT), RendererProtocol.searchTargets(RendererProtocol.AVT, "u"))
        assertTrue(RendererProtocol.searchTargets("urn:schemas-upnp-org:device:MediaServer:1", "u").isEmpty())
        assertEquals("uuid:u", RendererProtocol.usn("uuid:u", "u"))
        assertEquals("uuid:u::upnp:rootdevice", RendererProtocol.usn("upnp:rootdevice", "u"))
        val event = RendererProtocol.lastChange("AVTransport", listOf("TransportState" to "PLAYING", "AVTransportURI" to "http://h/a?b=1&c=2"))
        assertTrue(event.contains("&lt;TransportState val=&quot;PLAYING&quot;/&gt;"))
        assertTrue(event.contains("b=1&amp;amp;c=2"))   // escaped inside the event, then the event itself escaped
        val description = RendererProtocol.deviceDescription("u", "Phone & Co")
        assertTrue(description.contains("<friendlyName>Phone &amp; Co</friendlyName>"))
        assertTrue(description.contains("<controlURL>/control/AVTransport</controlURL>"))
    }
}
