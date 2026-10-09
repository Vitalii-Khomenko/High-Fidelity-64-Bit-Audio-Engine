package com.aiproject.musicplayer.dlna

/**
 * SOAP control of the renderer (pure JVM code): turns an action of one of
 * the three services into calls on [host] and a SOAP response or fault.
 */
class RendererControl(private val host: RendererHost) {
    data class Response(val code: Int, val body: String)

    fun control(service: String, body: String): Response {
        val type = RendererProtocol.SERVICES.firstOrNull { it.first == service }?.second ?: return Response(404, "")
        val call = RendererProtocol.parseSoap(body) ?: return Response(500, RendererProtocol.soapFault(401, "Invalid Action"))
        val result: List<Pair<String, String>>? = try {
            when (service) {
                "AVTransport" -> avTransport(call)
                "RenderingControl" -> renderingControl(call)
                else -> connectionManager(call)
            }
        } catch (e: IllegalArgumentException) {
            return Response(500, RendererProtocol.soapFault(402, e.message ?: "Invalid Args"))
        }
        if (result == null) return Response(500, RendererProtocol.soapFault(401, "Invalid Action"))
        return Response(200, RendererProtocol.soapResponse(type, call.action, result))
    }

    fun avTransport(call: SoapCall): List<Pair<String, String>>? {
        val a = call.args
        return when (call.action) {
            "SetAVTransportURI" -> {
                val uri = a["CurrentURI"].orEmpty().trim()
                require(uri.startsWith("http://") || uri.startsWith("https://")) { "Only http(s) URLs can be played" }
                host.setUri(uri, a["CurrentURIMetaData"].orEmpty())
                emptyList()
            }
            "SetNextAVTransportURI" -> {
                val uri = a["NextURI"].orEmpty().trim()
                require(uri.isEmpty() || uri.startsWith("http://") || uri.startsWith("https://")) { "Only http(s) URLs can be played" }
                host.setNextUri(uri, a["NextURIMetaData"].orEmpty())
                emptyList()
            }
            "Play" -> { host.play(); emptyList() }
            "Pause" -> { host.pause(); emptyList() }
            "Stop" -> { host.stop(); emptyList() }
            "Next" -> { host.next(); emptyList() }
            "Previous" -> { host.previous(); emptyList() }
            "Seek" -> {
                val unit = a["Unit"].orEmpty()
                require(unit == "REL_TIME" || unit == "ABS_TIME") { "Seek mode not supported" }
                host.seek(RendererProtocol.parseTime(a["Target"].orEmpty()) ?: throw IllegalArgumentException("Illegal seek target"))
                emptyList()
            }
            "GetTransportInfo" -> {
                val s = host.status()
                listOf("CurrentTransportState" to s.transport.upnp, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            }
            "GetPositionInfo" -> {
                val s = host.status()
                listOf(
                    "Track" to if (s.uri.isEmpty()) "0" else "1",
                    "TrackDuration" to RendererProtocol.time(s.durationMs),
                    "TrackMetaData" to s.metadata,
                    "TrackURI" to s.uri,
                    "RelTime" to RendererProtocol.time(s.positionMs),
                    "AbsTime" to RendererProtocol.time(s.positionMs),
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            }
            "GetMediaInfo" -> {
                val s = host.status()
                listOf(
                    "NrTracks" to if (s.uri.isEmpty()) "0" else "1",
                    "MediaDuration" to RendererProtocol.time(s.durationMs),
                    "CurrentURI" to s.uri, "CurrentURIMetaData" to s.metadata,
                    "NextURI" to s.nextUri, "NextURIMetaData" to s.nextMetadata,
                    "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
                )
            }
            "GetDeviceCapabilities" -> listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to "Play,Pause,Stop,Seek,Next,Previous")
            else -> null
        }
    }

    fun renderingControl(call: SoapCall): List<Pair<String, String>>? = when (call.action) {
        "GetVolume" -> listOf("CurrentVolume" to host.status().volume.toString())
        "SetVolume" -> {
            val v = call.args["DesiredVolume"]?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("Invalid volume")
            host.setVolume(v.coerceIn(0, 100))
            emptyList()
        }
        "GetMute" -> listOf("CurrentMute" to if (host.status().muted) "1" else "0")
        "SetMute" -> {
            val v = call.args["DesiredMute"]?.trim().orEmpty()
            host.setMute(v == "1" || v.equals("true", ignoreCase = true))
            emptyList()
        }
        "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
        "SelectPreset" -> emptyList()
        else -> null
    }

    fun connectionManager(call: SoapCall): List<Pair<String, String>>? = when (call.action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to RendererProtocol.sinkProtocolInfo())
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
        )
        else -> null
    }
}
