package com.aiproject.musicplayer.dlna

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The renderer over real sockets on 127.0.0.1 (no SSDP): HTTP, SOAP, GENA. */
class RendererServerTest {
    private val calls = LinkedBlockingQueue<String>()
    @Volatile private var playing = false

    private val host = object : RendererHost {
        override fun status() = RendererStatus(
            if (playing) TransportState.PLAYING else TransportState.STOPPED, "http://h/a.flac", "", "", "", 1000, 60_000, 70, false,
        )
        override fun setUri(uri: String, metadata: String) { calls += "uri $uri" }
        override fun setNextUri(uri: String, metadata: String) {}
        override fun play() { playing = true; calls += "play" }
        override fun pause() {}
        override fun stop() {}
        override fun seek(positionMs: Long) {}
        override fun next() {}
        override fun previous() {}
        override fun setVolume(percent: Int) {}
        override fun setMute(muted: Boolean) {}
    }

    private val server = RendererServer(host, "Test Renderer", "1234", InetAddress.getLoopbackAddress(), discovery = false)

    @After fun tearDown() = server.stop()

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = ""): Pair<Int, Map<String, String>> {
        val base = URL(server.location())
        Socket(base.host, base.port).use { s ->
            val bytes = body.toByteArray()
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${base.host}:${base.port}\r\nContent-Length: ${bytes.size}\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            s.getOutputStream().write(head.toByteArray() + bytes)
            val response = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = response.substringAfter(' ').substringBefore(' ').toInt()
            val hdrs = response.substringBefore("\r\n\r\n").lines().drop(1).filter { ':' in it }
                .associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() }
            return code to (hdrs + ("BODY" to response.substringAfter("\r\n\r\n")))
        }
    }

    @Test fun `stopping with discovery on kills no thread`() {
        // Any exception escaping a server thread closes the app on Android.
        val escaped = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped.add(e) }
        try {
            val withDiscovery = RendererServer(host, "Test Renderer", "5678", InetAddress.getLoopbackAddress(), discovery = true)
            assertTrue(withDiscovery.start())
            Thread.sleep(300)   // the announce loop is asleep between rounds
            withDiscovery.stop()
            Thread.sleep(700)
            assertEquals(emptyList<Throwable>(), escaped.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun `stop is safe while clients and events keep coming`() {
        assertTrue(server.start())
        val base = URL(server.location())
        // Clients connecting while it stops, events after it stopped: nothing may throw.
        val clients = (1..20).map { Thread { runCatching { Socket(base.host, base.port).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) } } } }
        clients.forEach { it.start() }
        server.stop()
        server.stop()
        server.notifyChanged()
        clients.forEach { it.join() }
        assertFalse(server.isRunning)
        assertFalse(server.start())   // a stopped server is not restarted
    }

    @Test fun `description, control and eventing work over http`() {
        assertTrue(server.start())
        val description = URL(server.location()).openConnection() as HttpURLConnection
        val xml = description.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(xml.contains("<friendlyName>Test Renderer</friendlyName>"))
        assertEquals(200, (URL(URL(server.location()), "/AVTransport.xml").openConnection() as HttpURLConnection).responseCode)

        // A subscriber with its own callback server.
        val events = LinkedBlockingQueue<String>()
        val callback = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val listener = Thread {
            while (!callback.isClosed) {
                val c = runCatching { callback.accept() }.getOrNull() ?: break
                c.use {
                    val text = StringBuilder()
                    val input = it.getInputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        text.append(String(buf, 0, n))
                        val headEnd = text.indexOf("\r\n\r\n")
                        if (headEnd >= 0) {
                            val len = Regex("CONTENT-LENGTH: (\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt() ?: 0
                            if (text.length >= headEnd + 4 + len) break
                        }
                    }
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    events += text.toString()
                }
            }
        }.apply { isDaemon = true; start() }
        val (code, headers) = request("SUBSCRIBE", "/event/AVTransport", mapOf(
            "CALLBACK" to "<http://127.0.0.1:${callback.localPort}/cb>", "NT" to "upnp:event", "TIMEOUT" to "Second-300",
        ))
        assertEquals(200, code)
        assertTrue(headers["SID"]!!.startsWith("uuid:"))
        val initial = events.poll(5, TimeUnit.SECONDS)!!
        assertTrue(initial.startsWith("NOTIFY /cb HTTP/1.1"))
        assertTrue(initial.contains("TransportState val=&quot;STOPPED&quot;"))

        // Play through SOAP: the call reaches the host and subscribers hear PLAYING.
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>"""
        val (playCode, playHeaders) = request("POST", "/control/AVTransport", mapOf(
            "SOAPACTION" to "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", "Content-Type" to "text/xml",
        ), soap)
        assertEquals(200, playCode)
        assertTrue(playHeaders["BODY"]!!.contains("PlayResponse"))
        assertEquals("play", calls.poll(2, TimeUnit.SECONDS))
        val changed = events.poll(5, TimeUnit.SECONDS)!!
        assertTrue(changed.contains("TransportState val=&quot;PLAYING&quot;"))
        assertTrue(changed.contains("SEQ: 1"))

        assertEquals(405, request("DELETE", "/x").first)
        assertEquals(412, request("SUBSCRIBE", "/event/AVTransport", mapOf("SID" to "uuid:nope")).first)
        callback.close()
        listener.join(1000)
    }
}
