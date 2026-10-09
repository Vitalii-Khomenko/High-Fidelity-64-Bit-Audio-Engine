package com.aiproject.musicplayer.library

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaServer(
    val friendlyName: String,
    val location: String,
    val controlUrl: String,
)

data class DlnaContainer(val id: String, val title: String, val childCount: Int = -1)

data class DlnaTrack(val title: String, val url: String, val durationMs: Long = 0L)

data class DlnaPage(
    val containers: List<DlnaContainer>,
    val tracks: List<DlnaTrack>,
    val numberReturned: Int,
    val totalMatches: Int,
)

/** UPnP ContentDirectory SOAP building and DIDL-Lite parsing (pure JVM code). */
internal object DlnaProtocol {
    const val PAGE_SIZE = 200

    fun buildBrowseEnvelope(objectId: String, startingIndex: Int = 0, requestedCount: Int = PAGE_SIZE): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>${xmlEscape(objectId)}</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>$startingIndex</StartingIndex>
      <RequestedCount>$requestedCount</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>"""

    private fun parseXml(xml: String): Element? = try {
        // No DTDs at all: rules out entity expansion and external entity reads.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.parse(InputSource(StringReader(xml))).documentElement
    } catch (_: Exception) {
        null
    }

    private fun Element.descendants(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.directChildren(name: String): List<Element> {
        val result = mutableListOf<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element && (node.localName ?: node.nodeName.substringAfter(':')) == name) result += node
            node = node.nextSibling
        }
        return result
    }

    fun friendlyName(xml: String): String? =
        parseXml(xml)?.descendants("friendlyName")?.firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    fun resolveControlUrl(location: String, xml: String): String? {
        val root = parseXml(xml) ?: return null
        val service = root.descendants("service").firstOrNull { entry ->
            entry.descendants("serviceType").firstOrNull()?.textContent?.contains(":ContentDirectory:") == true
        } ?: return null
        val path = service.descendants("controlURL").firstOrNull()?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val base = root.descendants("URLBase").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
            URL(URL(base), path).takeIf { it.protocol == "http" || it.protocol == "https" }?.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun parseBrowsePage(soapXml: String): DlnaPage {
        val empty = DlnaPage(emptyList(), emptyList(), 0, 0)
        val root = parseXml(soapXml) ?: return empty
        val returned = root.descendants("NumberReturned").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: -1
        val total = root.descendants("TotalMatches").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: -1
        val result = root.descendants("Result").firstOrNull() ?: return empty
        val didl = parseXml(result.textContent) ?: return empty
        val containers = didl.directChildren("container").mapNotNull { c ->
            val id = c.getAttribute("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = c.descendants("title").firstOrNull()?.textContent?.trim().orEmpty().ifEmpty { id }
            DlnaContainer(id, title, c.getAttribute("childCount").toIntOrNull() ?: -1)
        }
        val tracks = didl.directChildren("item").mapNotNull { item ->
            val upnpClass = item.descendants("class").firstOrNull()?.textContent.orEmpty()
            if (upnpClass.isNotEmpty() && !upnpClass.startsWith("object.item.audioItem")) return@mapNotNull null
            val title = item.descendants("title").firstOrNull()?.textContent?.trim() ?: return@mapNotNull null
            val resource = pickResource(item.directChildren("res")) ?: return@mapNotNull null
            DlnaTrack(title, resource.textContent.trim(), parseDurationMs(resource.getAttribute("duration")))
        }
        val count = if (returned >= 0) returned else containers.size + tracks.size
        return DlnaPage(containers, tracks, count, if (total >= 0) total else count)
    }

    fun parseBrowse(soapXml: String): List<DlnaTrack> = parseBrowsePage(soapXml).tracks

    /**
     * The first HTTP(S) resource the engine can decode, judged by the file
     * extension or, without one, by the MIME type in protocolInfo
     * ("http-get:*:audio/flac:*"). A resource with neither is accepted only if
     * nothing better exists. Items with only unsupported resources are skipped.
     */
    private fun pickResource(resources: List<Element>): Element? {
        var unknown: Element? = null
        for (res in resources) {
            val url = runCatching { URL(res.textContent.trim()) }.getOrNull() ?: continue
            if (url.protocol != "http" && url.protocol != "https") continue
            val name = url.path.substringAfterLast('/')
            val mime = res.getAttribute("protocolInfo").split(':').getOrNull(2)?.takeIf { it.isNotBlank() && it != "*" }
            when {
                name.contains('.') -> if (SupportedFormats.isSupportedName(name)) return res
                mime != null -> if (SupportedFormats.isSupported(name, mime)) return res
                else -> if (unknown == null) unknown = res
            }
        }
        return unknown
    }

    fun parseDurationMs(duration: String): Long {
        val parts = duration.trim().split(':')
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: return 0L
        val minutes = parts[1].toLongOrNull() ?: return 0L
        val seconds = parts[2].toDoubleOrNull() ?: return 0L
        if (hours < 0 || minutes !in 0..59 || !seconds.isFinite() || seconds < 0 || seconds >= 60) return 0L
        val millis = (hours.toDouble() * 3600 + minutes * 60 + seconds) * 1000
        return if (millis < Long.MAX_VALUE.toDouble()) Math.round(millis) else 0L
    }

    private fun xmlEscape(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
