package com.aiproject.musicplayer.library

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP discovery and ContentDirectory browsing over plain HTTP on the local network. */
object DlnaDiscovery {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val MAX_ITEMS = 5000
    private const val MAX_PAGES = 100
    private const val MAX_DESCRIPTION_BYTES = 1L shl 20    // device description XML
    private const val MAX_SOAP_BYTES = 8L shl 20           // one Browse response

    suspend fun discoverServers(context: Context, timeoutMs: Int = 4000): List<DlnaServer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // Some devices drop multicast replies unless a multicast lock is held.
        val lock = wifi?.createMulticastLock("hifi-ssdp")?.apply { setReferenceCounted(false) }
        val locations = linkedSetOf<String>()
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val request = ("M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray(Charsets.UTF_8)
                val address = InetAddress.getByName(SSDP_ADDR)
                repeat(2) { socket.send(DatagramPacket(request, request.size, address, SSDP_PORT)) }
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    ensureActive()
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        String(packet.data, 0, packet.length, Charsets.UTF_8).lineSequence()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()
                            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            ?.let { locations += it }
                    } catch (_: SocketTimeoutException) {
                        // keep listening until the deadline
                    }
                }
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
        locations.mapNotNull { location ->
            runCatching {
                val xml = fetchText(location)
                val control = DlnaProtocol.resolveControlUrl(location, xml) ?: return@runCatching null
                DlnaServer(DlnaProtocol.friendlyName(xml) ?: location, location, control)
            }.getOrNull()
        }.distinctBy { it.controlUrl }
    }

    /** All children of a container, following pagination. Throws on network errors. */
    suspend fun browse(server: DlnaServer, objectId: String = "0"): DlnaPage = withContext(Dispatchers.IO) {
        val containers = mutableListOf<DlnaContainer>()
        val tracks = mutableListOf<DlnaTrack>()
        var start = 0
        var total = Int.MAX_VALUE
        var pages = 0
        while (start < total && start < MAX_ITEMS && pages++ < MAX_PAGES) {
            ensureActive()
            val page = DlnaProtocol.parseBrowsePage(post(server.controlUrl, DlnaProtocol.buildBrowseEnvelope(objectId, start)))
            containers += page.containers
            tracks += page.tracks
            total = page.totalMatches
            if (page.numberReturned <= 0) break
            start += page.numberReturned
        }
        DlnaPage(containers, tracks, containers.size + tracks.size, containers.size + tracks.size)
    }

    private fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            connectTimeout = 5_000
            readTimeout = 15_000
            doOutput = true
        }
        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Server replied HTTP $code")
            readLimited(connection, MAX_SOAP_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads at most [limit] bytes; a larger (or larger-announced) body is an
     * error, so a broken or hostile server cannot exhaust the heap.
     */
    private fun readLimited(connection: HttpURLConnection, limit: Long): String {
        val announced = connection.contentLengthLong
        if (announced > limit) throw IOException("Response too large ($announced bytes)")
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) throw IOException("Response larger than $limit bytes")
                out.write(buffer, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
        }
        return try {
            readLimited(connection, MAX_DESCRIPTION_BYTES)
        } finally {
            connection.disconnect()
        }
    }
}
