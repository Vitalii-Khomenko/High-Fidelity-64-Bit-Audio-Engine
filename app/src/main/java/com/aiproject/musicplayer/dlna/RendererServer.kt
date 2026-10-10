package com.aiproject.musicplayer.dlna

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.RejectedExecutionException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the renderer asks of the player. Called on server threads. */
interface RendererHost {
    fun status(): RendererStatus
    fun setUri(uri: String, metadata: String)
    fun setNextUri(uri: String, metadata: String)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun setVolume(percent: Int)
    fun setMute(muted: Boolean)
}

/**
 * UPnP / DLNA media renderer: this phone appears as a player that control
 * points (BubbleUPnP, foobar2000, Windows "Cast to device", Kodi, …) can send
 * music to. HTTP serves the descriptions and SOAP control; GENA notifies
 * subscribers of state changes; SSDP answers searches and announces the
 * device. Only clients on the local network are served.
 */
class RendererServer(
    private val host: RendererHost,
    private val friendlyName: String,
    /** Stable device id, so control points remember this renderer. */
    private val udn: String,
    /** Address to serve on; null picks the Wi-Fi (site-local IPv4) address. */
    private val bindAddress: InetAddress? = null,
    /** SSDP discovery (off in tests). */
    private val discovery: Boolean = true,
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dlna-renderer").apply { isDaemon = true } }
    private var server: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var address: InetAddress? = null
    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private val controller = RendererControl(host)
    @Volatile private var lastAvt: List<Pair<String, String>> = emptyList()
    @Volatile private var lastRcs: List<Pair<String, String>> = emptyList()

    private data class Subscriber(val service: String, val callbacks: List<String>, var expires: Long, var seq: Long = 0)

    val isRunning: Boolean get() = running.get()

    /** Starts serving on the Wi-Fi address. False when there is no local network. */
    fun start(): Boolean {
        if (running.get()) return true
        if (pool.isShutdown) return false   // stopped instances are not reused
        val ip = bindAddress ?: localAddress() ?: return false
        address = ip
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(ip, 0))
        server = socket
        running.set(true)
        submit { acceptLoop(socket) }
        if (discovery) {
            submit { ssdpLoop() }
            submit { announceLoop() }
        }
        return true
    }

    /** Safe from any thread and more than once; the instance cannot be started again. */
    fun stop() {
        if (!running.getAndSet(false)) return
        if (discovery) runCatching { announce("ssdp:byebye") }
        runCatching { server?.close() }
        runCatching { ssdp?.close() }
        subscribers.clear()
        pool.shutdownNow()
    }

    /**
     * Runs work on the server's threads. After stop() the pool refuses work;
     * that must never surface as an exception (an uncaught one on a server
     * thread would kill the app).
     */
    private fun submit(task: () -> Unit): Boolean = try {
        pool.execute {
            try {
                task()
            } catch (_: InterruptedException) {
                // stop() interrupts the pool: the task just ends.
            } catch (_: Exception) {
                // A broken client or network must not take the app down.
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /** Tells subscribers what changed (call after transport, track or volume changes). */
    fun notifyChanged() {
        if (!running.get() || subscribers.isEmpty()) return
        val status = runCatching { host.status() }.getOrNull() ?: return
        val avt = RendererProtocol.avtState(status)
        val rcs = RendererProtocol.rcsState(status)
        if (avt != lastAvt) {
            lastAvt = avt
            submit { publish("AVTransport", avt) }
        }
        if (rcs != lastRcs) {
            lastRcs = rcs
            submit { publish("RenderingControl", rcs) }
        }
    }

    /** The device description URL (for tests and logs). */
    fun location(): String = "http://${address?.hostAddress}:${server?.localPort}/description.xml"

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            if (!submit { client.use { handle(it) } }) runCatching { client.close() }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (head.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val request = lines.firstOrNull()?.split(' ') ?: return null
        if (request.size < 2) return null
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return Request(request[0].uppercase(), request[1].substringBefore('?'), headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun handle(client: Socket) {
        val peer = client.inetAddress
        if (!(peer.isSiteLocalAddress || peer.isLinkLocalAddress || peer.isLoopbackAddress)) return
        client.soTimeout = 10_000
        val request = readRequest(BufferedInputStream(client.getInputStream())) ?: return
        val out = client.getOutputStream()
        when {
            request.method == "GET" && request.path == "/description.xml" ->
                reply(out, 200, RendererProtocol.deviceDescription(udn, friendlyName))
            request.method == "GET" && request.path.endsWith(".xml") ->
                RendererProtocol.scpd(request.path.removePrefix("/").removeSuffix(".xml"))?.let { reply(out, 200, it) } ?: reply(out, 404, "")
            request.method == "POST" && request.path.startsWith("/control/") ->
                control(out, request.path.removePrefix("/control/"), request.body)
            request.method == "SUBSCRIBE" && request.path.startsWith("/event/") ->
                subscribe(out, request.path.removePrefix("/event/"), request.headers)
            request.method == "UNSUBSCRIBE" -> {
                request.headers["sid"]?.let { subscribers.remove(it) }
                reply(out, 200, "", contentType = null)
            }
            else -> reply(out, 405, "")
        }
    }

    private fun reply(out: OutputStream, code: Int, body: String, contentType: String? = "text/xml; charset=\"utf-8\"", extra: List<String> = emptyList()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 412 -> "Precondition Failed"; else -> "Internal Server Error" }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Server: Android/1.0 UPnP/1.0 HiFiPlayer/1.0\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun control(out: OutputStream, service: String, body: String) {
        val response = controller.control(service, body)
        reply(out, response.code, response.body)
        if (response.code == 200) notifyChanged()
    }

    // ── GENA ────────────────────────────────────────────────────────────────

    private fun subscribe(out: OutputStream, service: String, headers: Map<String, String>) {
        if (RendererProtocol.SERVICES.none { it.first == service }) return reply(out, 404, "")
        val timeout = headers["timeout"]?.substringAfter("Second-", "")?.toLongOrNull()?.coerceIn(60, 3600) ?: 1800
        val existing = headers["sid"]
        if (existing != null) {
            val sub = subscribers[existing] ?: return reply(out, 412, "")
            sub.expires = System.currentTimeMillis() + timeout * 1000
            return reply(out, 200, "", contentType = null, extra = listOf("SID: $existing", "TIMEOUT: Second-$timeout"))
        }
        val callbacks = Regex("<([^>]+)>").findAll(headers["callback"].orEmpty()).map { it.groupValues[1] }
            .filter { it.startsWith("http://") }.toList()
        if (callbacks.isEmpty()) return reply(out, 412, "")
        val sid = "uuid:" + UUID.randomUUID()
        subscribers[sid] = Subscriber(service, callbacks, System.currentTimeMillis() + timeout * 1000)
        reply(out, 200, "", contentType = null, extra = listOf("SID: $sid", "TIMEOUT: Second-$timeout"))
        // Initial event with the full state.
        val status = runCatching { host.status() }.getOrNull() ?: return
        val values = when (service) {
            "AVTransport" -> RendererProtocol.avtState(status)
            "RenderingControl" -> RendererProtocol.rcsState(status)
            else -> return
        }
        submit { send(sid, RendererProtocol.lastChange(service, values)) }
    }

    private fun publish(service: String, values: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        subscribers.entries.removeIf { it.value.expires < now }
        val body = RendererProtocol.lastChange(service, values)
        subscribers.filterValues { it.service == service }.keys.forEach { send(it, body) }
    }

    /** NOTIFY over a raw socket (HttpURLConnection does not allow the method). */
    private fun send(sid: String, body: String) {
        val sub = subscribers[sid] ?: return
        val seq = synchronized(sub) { sub.seq++ }
        for (callback in sub.callbacks) {
            val ok = runCatching {
                val url = URL(callback)
                Socket().use { s ->
                    s.connect(InetSocketAddress(url.host, if (url.port > 0) url.port else 80), 3000)
                    s.soTimeout = 3000
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "NOTIFY ${url.file.ifEmpty { "/" }} HTTP/1.1\r\nHOST: ${url.host}:${if (url.port > 0) url.port else 80}\r\n" +
                        "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${bytes.size}\r\nNT: upnp:event\r\n" +
                        "NTS: upnp:propchange\r\nSID: $sid\r\nSEQ: $seq\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
                    s.getInputStream().read()   // wait for the status line
                }
            }.isSuccess
            if (ok) return
        }
    }

    // ── SSDP ────────────────────────────────────────────────────────────────

    private fun ssdpLoop() {
        try {
            val group = InetAddress.getByName(SSDP_ADDR)
            val socket = MulticastSocket(SSDP_PORT).apply {
                reuseAddress = true
                soTimeout = 1000
                NetworkInterface.getByInetAddress(address)?.let { networkInterface = it }
                joinGroup(group)
            }
            ssdp = socket
            val buffer = ByteArray(4096)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
                val st = text.lineSequence().firstOrNull { it.startsWith("ST:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: continue
                for (target in RendererProtocol.searchTargets(st, udn)) {
                    val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: ${location()}\r\n" +
                        "SERVER: Android/1.0 UPnP/1.0 HiFiPlayer/1.0\r\nST: $target\r\nUSN: ${RendererProtocol.usn(target, udn)}\r\n\r\n"
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
                }
            }
        } catch (_: Exception) {
            // The network went away; announcements stop with it.
        }
    }

    private fun announceLoop() {
        var round = 0
        while (running.get()) {
            runCatching { announce("ssdp:alive") }
            // Three quick rounds at start (UDP is lossy), then every 10 minutes.
            val wait = if (round++ < 3) 2_000L else 600_000L
            var waited = 0L
            while (running.get() && waited < wait) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return   // stop() interrupts the pool
                }
                waited += 500
            }
        }
    }

    private fun announce(nts: String) {
        val group = InetAddress.getByName(SSDP_ADDR)
        MulticastSocket().use { socket ->
            NetworkInterface.getByInetAddress(address)?.let { socket.networkInterface = it }
            for (nt in RendererProtocol.searchTargets("ssdp:all", udn)) {
                val msg = "NOTIFY * HTTP/1.1\r\nHOST: $SSDP_ADDR:$SSDP_PORT\r\nCACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${location()}\r\nNT: $nt\r\nNTS: $nts\r\nSERVER: Android/1.0 UPnP/1.0 HiFiPlayer/1.0\r\n" +
                    "USN: ${RendererProtocol.usn(nt, udn)}\r\n\r\n"
                val bytes = msg.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            }
        }
    }

    private fun localAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_BODY = 256 * 1024
    }
}
