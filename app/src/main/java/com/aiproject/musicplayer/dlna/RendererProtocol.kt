package com.aiproject.musicplayer.dlna

import com.aiproject.musicplayer.library.DlnaProtocol
import com.aiproject.musicplayer.library.DlnaProtocol.descendants
import com.aiproject.musicplayer.library.SupportedFormats
import java.util.Locale

/** What a control point sees of the player. */
data class RendererStatus(
    val transport: TransportState,
    val uri: String,
    val metadata: String,
    val nextUri: String,
    val nextMetadata: String,
    val positionMs: Long,
    val durationMs: Long,
    val volume: Int,
    val muted: Boolean,
)

enum class TransportState(val upnp: String) {
    NO_MEDIA("NO_MEDIA_PRESENT"), STOPPED("STOPPED"), PLAYING("PLAYING"), PAUSED("PAUSED_PLAYBACK"), TRANSITIONING("TRANSITIONING")
}

/** Title / artist / album / cover from DIDL-Lite metadata sent with a URI. */
data class DidlInfo(val title: String = "", val artist: String = "", val album: String = "", val albumArtUri: String = "")

/** A SOAP call: service ("AVTransport", …), action and its arguments. */
data class SoapCall(val action: String, val args: Map<String, String>)

/**
 * UPnP MediaRenderer:1 descriptions, SOAP and GENA payloads (pure JVM code).
 * The device offers AVTransport, RenderingControl and ConnectionManager.
 */
object RendererProtocol {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    val SERVICES = listOf("AVTransport" to AVT, "RenderingControl" to RCS, "ConnectionManager" to CMS)

    private val MIME = listOf(
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/L16", "audio/aiff", "audio/x-aiff",
        "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/x-ogg", "audio/opus",
        "audio/x-wavpack", "audio/x-ape", "audio/x-dsf", "audio/x-dff",
    )

    fun sinkProtocolInfo(): String = MIME.joinToString(",") { "http-get:*:$it:*" }

    fun deviceDescription(udn: String, friendlyName: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>$DEVICE_TYPE</deviceType>
    <friendlyName>${x(friendlyName)}</friendlyName>
    <manufacturer>HiFi Player</manufacturer>
    <modelName>HiFi Player</modelName>
    <modelDescription>Android music player, 64-bit engine</modelDescription>
    <UDN>uuid:$udn</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
${SERVICES.joinToString("\n") { (name, type) -> """      <service>
        <serviceType>$type</serviceType>
        <serviceId>urn:upnp-org:serviceId:$name</serviceId>
        <SCPDURL>/$name.xml</SCPDURL>
        <controlURL>/control/$name</controlURL>
        <eventSubURL>/event/$name</eventSubURL>
      </service>""" }}
    </serviceList>
  </device>
</root>"""

    private fun action(name: String, vararg args: Pair<String, String>): String {
        val list = args.joinToString("") { (arg, related) ->
            val dir = if (arg.startsWith(">")) "out" else "in"
            "<argument><name>${arg.removePrefix(">")}</name><direction>$dir</direction><relatedStateVariable>$related</relatedStateVariable></argument>"
        }
        return "<action><name>$name</name><argumentList>$list</argumentList></action>"
    }

    private fun variable(name: String, type: String, events: Boolean = false, allowed: List<String> = emptyList(), range: IntRange? = null): String {
        val values = if (allowed.isEmpty()) "" else allowed.joinToString("", "<allowedValueList>", "</allowedValueList>") { "<allowedValue>$it</allowedValue>" }
        val r = range?.let { "<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>" }.orEmpty()
        return "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>$values$r</stateVariable>"
    }

    private fun scpd(actions: List<String>, variables: List<String>) = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>${actions.joinToString("")}</actionList>
<serviceStateTable>${variables.joinToString("")}</serviceStateTable></scpd>"""

    private const val IID = "A_ARG_TYPE_InstanceID"

    val avTransportScpd: String = scpd(
        listOf(
            action("SetAVTransportURI", "InstanceID" to IID, "CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData"),
            action("SetNextAVTransportURI", "InstanceID" to IID, "NextURI" to "NextAVTransportURI", "NextURIMetaData" to "NextAVTransportURIMetaData"),
            action("GetMediaInfo", "InstanceID" to IID, ">NrTracks" to "NumberOfTracks", ">MediaDuration" to "CurrentMediaDuration",
                ">CurrentURI" to "AVTransportURI", ">CurrentURIMetaData" to "AVTransportURIMetaData", ">NextURI" to "NextAVTransportURI",
                ">NextURIMetaData" to "NextAVTransportURIMetaData", ">PlayMedium" to "PlaybackStorageMedium",
                ">RecordMedium" to "RecordStorageMedium", ">WriteStatus" to "RecordMediumWriteStatus"),
            action("GetTransportInfo", "InstanceID" to IID, ">CurrentTransportState" to "TransportState",
                ">CurrentTransportStatus" to "TransportStatus", ">CurrentSpeed" to "TransportPlaySpeed"),
            action("GetPositionInfo", "InstanceID" to IID, ">Track" to "CurrentTrack", ">TrackDuration" to "CurrentTrackDuration",
                ">TrackMetaData" to "CurrentTrackMetaData", ">TrackURI" to "CurrentTrackURI", ">RelTime" to "RelativeTimePosition",
                ">AbsTime" to "AbsoluteTimePosition", ">RelCount" to "RelativeCounterPosition", ">AbsCount" to "AbsoluteCounterPosition"),
            action("GetDeviceCapabilities", "InstanceID" to IID, ">PlayMedia" to "PossiblePlaybackStorageMedia",
                ">RecMedia" to "PossibleRecordStorageMedia", ">RecQualityModes" to "PossibleRecordQualityModes"),
            action("GetTransportSettings", "InstanceID" to IID, ">PlayMode" to "CurrentPlayMode", ">RecQualityMode" to "CurrentRecordQualityMode"),
            action("GetCurrentTransportActions", "InstanceID" to IID, ">Actions" to "CurrentTransportActions"),
            action("Stop", "InstanceID" to IID),
            action("Play", "InstanceID" to IID, "Speed" to "TransportPlaySpeed"),
            action("Pause", "InstanceID" to IID),
            action("Seek", "InstanceID" to IID, "Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget"),
            action("Next", "InstanceID" to IID),
            action("Previous", "InstanceID" to IID),
        ),
        listOf(
            variable("TransportState", "string", allowed = TransportState.entries.map { it.upnp }),
            variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
            variable("PlaybackStorageMedium", "string", allowed = listOf("NETWORK", "NONE")),
            variable("RecordStorageMedium", "string", allowed = listOf("NOT_IMPLEMENTED")),
            variable("PossiblePlaybackStorageMedia", "string"), variable("PossibleRecordStorageMedia", "string"),
            variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
            variable("TransportPlaySpeed", "string", allowed = listOf("1")),
            variable("RecordMediumWriteStatus", "string"), variable("CurrentRecordQualityMode", "string"),
            variable("PossibleRecordQualityModes", "string"), variable("NumberOfTracks", "ui4"), variable("CurrentTrack", "ui4"),
            variable("CurrentTrackDuration", "string"), variable("CurrentMediaDuration", "string"), variable("CurrentTrackMetaData", "string"),
            variable("CurrentTrackURI", "string"), variable("AVTransportURI", "string"), variable("AVTransportURIMetaData", "string"),
            variable("NextAVTransportURI", "string"), variable("NextAVTransportURIMetaData", "string"),
            variable("RelativeTimePosition", "string"), variable("AbsoluteTimePosition", "string"),
            variable("RelativeCounterPosition", "i4"), variable("AbsoluteCounterPosition", "i4"),
            variable("CurrentTransportActions", "string"), variable("LastChange", "string", events = true),
            variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
            variable("A_ARG_TYPE_SeekTarget", "string"), variable(IID, "ui4"),
        ),
    )

    val renderingControlScpd: String = scpd(
        listOf(
            action("GetVolume", "InstanceID" to IID, "Channel" to "A_ARG_TYPE_Channel", ">CurrentVolume" to "Volume"),
            action("SetVolume", "InstanceID" to IID, "Channel" to "A_ARG_TYPE_Channel", "DesiredVolume" to "Volume"),
            action("GetMute", "InstanceID" to IID, "Channel" to "A_ARG_TYPE_Channel", ">CurrentMute" to "Mute"),
            action("SetMute", "InstanceID" to IID, "Channel" to "A_ARG_TYPE_Channel", "DesiredMute" to "Mute"),
            action("ListPresets", "InstanceID" to IID, ">CurrentPresetNameList" to "PresetNameList"),
            action("SelectPreset", "InstanceID" to IID, "PresetName" to "A_ARG_TYPE_PresetName"),
        ),
        listOf(
            variable("Volume", "ui2", range = 0..100), variable("Mute", "boolean"), variable("PresetNameList", "string"),
            variable("LastChange", "string", events = true),
            variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
            variable("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults")), variable(IID, "ui4"),
        ),
    )

    val connectionManagerScpd: String = scpd(
        listOf(
            action("GetProtocolInfo", ">Source" to "SourceProtocolInfo", ">Sink" to "SinkProtocolInfo"),
            action("GetCurrentConnectionIDs", ">ConnectionIDs" to "CurrentConnectionIDs"),
            action("GetCurrentConnectionInfo", "ConnectionID" to "A_ARG_TYPE_ConnectionID", ">RcsID" to "A_ARG_TYPE_RcsID",
                ">AVTransportID" to "A_ARG_TYPE_AVTransportID", ">ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo",
                ">PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager", ">PeerConnectionID" to "A_ARG_TYPE_ConnectionID",
                ">Direction" to "A_ARG_TYPE_Direction", ">Status" to "A_ARG_TYPE_ConnectionStatus"),
        ),
        listOf(
            variable("SourceProtocolInfo", "string", events = true), variable("SinkProtocolInfo", "string", events = true),
            variable("CurrentConnectionIDs", "string", events = true), variable("A_ARG_TYPE_ConnectionID", "i4"),
            variable("A_ARG_TYPE_RcsID", "i4"), variable("A_ARG_TYPE_AVTransportID", "i4"), variable("A_ARG_TYPE_ProtocolInfo", "string"),
            variable("A_ARG_TYPE_ConnectionManager", "string"),
            variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
            variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "Unknown")),
        ),
    )

    fun scpd(service: String): String? = when (service) {
        "AVTransport" -> avTransportScpd
        "RenderingControl" -> renderingControlScpd
        "ConnectionManager" -> connectionManagerScpd
        else -> null
    }

    /** The action in a SOAP body; arguments by element name. */
    fun parseSoap(body: String): SoapCall? {
        val root = DlnaProtocol.parseXml(body) ?: return null
        val bodyElement = root.descendants("Body").firstOrNull() ?: return null
        var node = bodyElement.firstChild
        while (node != null && node !is org.w3c.dom.Element) node = node.nextSibling
        val action = node as? org.w3c.dom.Element ?: return null
        val args = LinkedHashMap<String, String>()
        var child = action.firstChild
        while (child != null) {
            if (child is org.w3c.dom.Element) args[child.localName ?: child.nodeName.substringAfter(':')] = child.textContent.orEmpty()
            child = child.nextSibling
        }
        return SoapCall(action.localName ?: action.nodeName.substringAfter(':'), args)
    }

    fun soapResponse(serviceType: String, action: String, values: List<Pair<String, String>> = emptyList()): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="$serviceType">${values.joinToString("") { (k, v) -> "<$k>${x(v)}</$k>" }}</u:${action}Response></s:Body></s:Envelope>"""

    fun soapFault(code: Int, description: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${x(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

    /** GENA property set carrying a LastChange event. */
    fun lastChange(service: String, values: List<Pair<String, String>>): String {
        val ns = if (service == "RenderingControl") "urn:schemas-upnp-org:metadata-1-0/RCS/" else "urn:schemas-upnp-org:metadata-1-0/AVT/"
        val event = "<Event xmlns=\"$ns\"><InstanceID val=\"0\">" +
            values.joinToString("") { (k, v) -> if (k == "Volume" || k == "Mute") "<$k channel=\"Master\" val=\"${x(v)}\"/>" else "<$k val=\"${x(v)}\"/>" } +
            "</InstanceID></Event>"
        return """<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>${x(event)}</LastChange></e:property></e:propertyset>"""
    }

    fun avtState(s: RendererStatus): List<Pair<String, String>> = listOf(
        "TransportState" to s.transport.upnp,
        "TransportStatus" to "OK",
        "CurrentTrackURI" to s.uri,
        "AVTransportURI" to s.uri,
        "CurrentTrackMetaData" to s.metadata,
        "AVTransportURIMetaData" to s.metadata,
        "NextAVTransportURI" to s.nextUri,
        "CurrentTrackDuration" to time(s.durationMs),
        "CurrentMediaDuration" to time(s.durationMs),
        "NumberOfTracks" to if (s.uri.isEmpty()) "0" else "1",
        "CurrentTransportActions" to "Play,Pause,Stop,Seek,Next,Previous",
    )

    fun rcsState(s: RendererStatus): List<Pair<String, String>> =
        listOf("Volume" to s.volume.toString(), "Mute" to if (s.muted) "1" else "0")

    /** "H:MM:SS" (UPnP time), "0:00:00" for unknown. */
    fun time(ms: Long): String {
        val t = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d:%02d".format(Locale.US, t / 3600, (t / 60) % 60, t % 60)
    }

    /** "1:02:03", "0:03:05.500", "185" → milliseconds, or null. */
    fun parseTime(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0 || !v.isFinite()) return null
            seconds = seconds * 60 + v
        }
        return Math.round(seconds * 1000)
    }

    fun parseDidl(metadata: String): DidlInfo {
        if (metadata.isBlank()) return DidlInfo()
        val root = DlnaProtocol.parseXml(metadata) ?: return DidlInfo()
        fun first(name: String) = root.descendants(name).firstOrNull()?.textContent?.trim().orEmpty()
        return DidlInfo(
            title = first("title"),
            artist = first("artist").ifEmpty { first("creator") },
            album = first("album"),
            albumArtUri = first("albumArtURI"),
        )
    }

    /** DIDL-Lite for the current item, as reported back to control points. */
    fun didl(uri: String, title: String, artist: String, album: String, durationMs: Long): String {
        if (uri.isEmpty()) return ""
        val mime = mimeFor(uri)
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${x(title)}</dc:title><dc:creator>${x(artist)}</dc:creator><upnp:artist>${x(artist)}</upnp:artist>" +
            "<upnp:album>${x(album)}</upnp:album><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\" duration=\"${time(durationMs)}\">${x(uri)}</res></item></DIDL-Lite>"
    }

    private fun mimeFor(uri: String): String = when (SupportedFormats.extension(uri.substringBefore('?').substringAfterLast('/'))) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        else -> "audio/*"
    }

    /** Whether an M-SEARCH target is answered by this device, and the ST values to answer with. */
    fun searchTargets(st: String, udn: String): List<String> {
        val all = listOf("upnp:rootdevice", "uuid:$udn", DEVICE_TYPE) + SERVICES.map { it.second }
        return when (st.trim()) {
            "ssdp:all" -> all
            in all -> listOf(st.trim())
            else -> emptyList()
        }
    }

    fun usn(st: String, udn: String): String = if (st == "uuid:$udn") st else "uuid:$udn::$st"

    private fun x(text: String) = DlnaProtocol.xmlEscape(text)
}
