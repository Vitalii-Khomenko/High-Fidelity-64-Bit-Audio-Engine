package com.aiproject.musicplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class DlnaServer(
    val friendlyName: String,
    val location: String,
    val controlUrl: String
)

data class DlnaTrack(
    val title: String,
    val url: String,
    val durationMs: Long = 0L
)

object DlnaDiscovery {

    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /**
     * Send SSDP M-SEARCH and collect UPnP MediaServer responses.
     * Blocks for [timeoutSec] seconds on the IO dispatcher.
     */
    suspend fun discoverServers(timeoutSec: Int = 4): List<DlnaServer> =
        withContext(Dispatchers.IO) {
            val msearch = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"

            val locations = linkedSetOf<String>()
            try {
                DatagramSocket().use { sock ->
                    sock.soTimeout = timeoutSec * 1000
                    val addr = InetAddress.getByName(SSDP_ADDR)
                    val bytes = msearch.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, addr, SSDP_PORT))

                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + timeoutSec * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                                .lineSequence()
                                .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                                ?.substringAfter(':')?.trim()
                                ?.also { locations += it }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            locations.mapNotNull { loc ->
                try {
                    val xml = fetchText(loc)
                    val name = xmlTag(xml, "friendlyName") ?: "Unknown Server"
                    val ctrl = resolveControlUrl(loc, xml) ?: return@mapNotNull null
                    DlnaServer(name, loc, ctrl)
                } catch (_: Exception) {
                    null
                }
            }
        }

    /**
     * Browse a ContentDirectory container (default: root "0").
     * Returns audio items found in this container (up to 500).
     */
    suspend fun browse(server: DlnaServer, objectId: String = "0"): List<DlnaTrack> =
        withContext(Dispatchers.IO) {
            val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>$objectId</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>dc:title,res,res@duration,upnp:class</Filter>
      <StartingIndex>0</StartingIndex>
      <RequestedCount>500</RequestedCount>
      <SortCriteria/>
    </u:Browse>
  </s:Body>
</s:Envelope>"""
            try {
                val conn = URL(server.controlUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                conn.setRequestProperty(
                    "SOAPAction",
                    "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
                )
                conn.connectTimeout = 5_000
                conn.readTimeout   = 15_000
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(soap) }
                parseBrowse(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000; conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    /** Extract first occurrence of <tag>content</tag>. */
    private fun xmlTag(xml: String, tag: String): String? {
        val s = xml.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
        val e = xml.indexOf("</$tag>", s).takeIf { it >= 0 } ?: return null
        return xml.substring(s + tag.length + 2, e).trim()
    }

    /**
     * Find the ContentDirectory:1 controlURL in device description XML
     * and resolve it against the [location] base URL.
     */
    private fun resolveControlUrl(location: String, xml: String): String? {
        val cdIdx = xml.indexOf("ContentDirectory").takeIf { it >= 0 } ?: return null
        val s = xml.indexOf("<controlURL>", cdIdx).takeIf { it >= 0 } ?: return null
        val e = xml.indexOf("</controlURL>", s).takeIf { it >= 0 } ?: return null
        val path = xml.substring(s + 12, e).trim()
        if (path.startsWith("http", ignoreCase = true)) return path
        val u = URL(location)
        val base = "${u.protocol}://${u.host}:${u.port}"
        return if (path.startsWith("/")) "$base$path"
        else "$base/${u.path.substringBeforeLast('/')}/$path"
    }

    /**
     * Parse ContentDirectory Browse SOAP response.
     * Extracts <item> elements from the (XML-escaped) DIDL-Lite <Result>.
     */
    private fun parseBrowse(soapXml: String): List<DlnaTrack> {
        val rs = soapXml.indexOf("<Result>").takeIf { it >= 0 } ?: return emptyList()
        val re = soapXml.indexOf("</Result>", rs).takeIf { it >= 0 } ?: return emptyList()
        val didl = soapXml.substring(rs + 8, re)
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

        val tracks = mutableListOf<DlnaTrack>()
        var pos = 0
        while (true) {
            val itemStart = didl.indexOf("<item", pos).takeIf { it >= 0 } ?: break
            val itemEnd   = didl.indexOf("</item>", itemStart).takeIf { it >= 0 } ?: break
            val item      = didl.substring(itemStart, itemEnd + 7)
            pos = itemEnd + 7

            val title = xmlTag(item, "dc:title") ?: xmlTag(item, "title") ?: continue

            // Find first <res ...>URL</res> with an http URL
            var resSearch = 0
            var foundUrl: String? = null
            while (foundUrl == null) {
                val resOpen = item.indexOf("<res", resSearch).takeIf { it >= 0 } ?: break
                val resTagEnd = item.indexOf('>', resOpen).takeIf { it >= 0 } ?: break
                val resClose  = item.indexOf("</res>", resTagEnd).takeIf { it >= 0 } ?: break
                val candidate = item.substring(resTagEnd + 1, resClose).trim()
                if (candidate.startsWith("http", ignoreCase = true)) foundUrl = candidate
                resSearch = resClose + 6
            }
            val url = foundUrl ?: continue

            // Parse duration attribute: duration="H:MM:SS.mmm"
            val durMs = Regex("""duration="(\d+):(\d+):(\d+)""")
                .find(item)?.let { mr ->
                    val (h, m, s) = mr.destructured
                    (h.toLong() * 3_600 + m.toLong() * 60 + s.toLong()) * 1_000L
                } ?: 0L

            tracks += DlnaTrack(title, url, durMs)
        }
        return tracks
    }
}
