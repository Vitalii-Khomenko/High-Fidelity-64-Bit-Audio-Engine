package com.aiproject.musicplayer.library

import android.net.Uri
import com.aiproject.musicplayer.NativeStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * What the engine opens: a local URI, and for a file still being downloaded
 * the native stream id (reads wait for the missing bytes) plus the final path
 * the file will have when the download is done.
 */
data class Playable(val uri: Uri, val streamId: Long = 0L, val completeUri: Uri? = null)

/**
 * DLNA tracks are HTTP URLs. They are downloaded into a cache (512 MiB,
 * 14 days, least recently used first). Formats whose decoders can follow a
 * growing file (FLAC, WAV / AIFF, WavPack, TTA) start playing after the first
 * 512 KiB; the others start once the download is complete. One download per
 * URL is shared by playback and the gapless pre-load.
 */
object DlnaPlaybackCache {
    private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L
    private const val CACHE_PREFIX = "dlna_"
    private const val CACHE_EXT = ".audio"
    private const val TEMP_EXT = ".tmp"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val MAX_CACHE_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MAX_TEMP_AGE_MS = 24L * 60L * 60L * 1000L
    private const val STREAM_START_BYTES = 512L * 1024L
    private const val STREAM_RELEASE_DELAY_MS = 5L * 60L * 1000L

    private val STREAMABLE = setOf("flac", "wav", "wave", "aif", "aiff", "aifc", "w64", "rf64", "wv", "tta")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<String, Download>()

    private class Download(val url: String, val temp: File, val target: File) {
        val received = MutableStateFlow(0L)
        var total = -1L
        var streamId = 0L
        val headers = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Boolean>()
        lateinit var job: Job
    }

    fun isRemote(trackUri: Uri): Boolean =
        trackUri.scheme.equals("http", ignoreCase = true) || trackUri.scheme.equals("https", ignoreCase = true)

    fun isStreamable(url: String): Boolean =
        SupportedFormats.extension(Uri.parse(url).path.orEmpty().substringAfterLast('/')) in STREAMABLE

    /** Old API: a local URI once the whole file is there. */
    suspend fun resolvePlaybackUri(trackUri: Uri, cacheDir: File): Uri = resolve(trackUri, cacheDir, allowStream = false).uri

    /** The URI to open for [trackUri]; for streamable formats possibly while still downloading. */
    suspend fun resolve(trackUri: Uri, cacheDir: File, allowStream: Boolean = true): Playable {
        if (!isRemote(trackUri)) return Playable(trackUri)
        val url = trackUri.toString()
        val target = cacheFile(cacheDir, url)
        if (target.exists() && target.length() > 0L && downloads[url] == null) {
            target.setLastModified(System.currentTimeMillis())
            withContext(Dispatchers.IO) { pruneCache(cacheDir, keep = target) }
            return Playable(Uri.fromFile(target))
        }
        val download = downloads.computeIfAbsent(url) { start(url, cacheDir) }
        download.headers.await()
        val stream = allowStream && isStreamable(url) && download.total > 0 && download.streamId != 0L
        if (stream) {
            val threshold = minOf(download.total, STREAM_START_BYTES)
            download.received.first { it >= threshold || download.done.isCompleted }
            if (!download.done.isCompleted) {
                return Playable(Uri.fromFile(download.temp), download.streamId, Uri.fromFile(download.target))
            }
        }
        check(download.done.await()) { "DLNA download failed" }
        return Playable(Uri.fromFile(download.target))
    }

    /** Stops downloads that are no longer the current or next track. */
    fun retainOnly(urls: Set<String>) {
        for ((url, download) in downloads) if (url !in urls && !download.done.isCompleted) download.job.cancel()
    }

    private fun start(url: String, cacheDir: File): Download {
        cacheDir.mkdirs()
        val download = Download(url, File(cacheDir, "${CACHE_PREFIX}${sha256(url)}$TEMP_EXT"), cacheFile(cacheDir, url))
        download.job = scope.launch {
            var ok = false
            try {
                ok = fetch(download, cacheDir)
            } catch (_: Exception) {
                ok = false
            } finally {
                if (!download.headers.isCompleted) download.headers.complete(Unit)
                if (download.streamId != 0L) {
                    NativeStreams.progress(download.streamId, download.received.value, if (ok) 1 else 2)
                }
                if (!ok) download.temp.delete()
                download.done.complete(ok)
                downloads.remove(url, download)
                val id = download.streamId
                if (id != 0L) {
                    // Decoders hold their own reference; keep the id resolvable for late opens a while.
                    scope.launch { delay(STREAM_RELEASE_DELAY_MS); NativeStreams.release(id) }
                }
            }
        }
        return download
    }

    private suspend fun fetch(download: Download, cacheDir: File): Boolean {
        pruneCache(cacheDir, keep = null)
        download.temp.delete()
        val connection = (URL(download.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) return false
            val expected = connection.contentLengthLong
            if (expected > MAX_DOWNLOAD_BYTES) return false
            download.total = expected
            if (expected > 0) {
                pruneCache(cacheDir, keep = null, budget = MAX_CACHE_BYTES - expected)
                download.streamId = NativeStreams.create(expected)
            }
            download.headers.complete(Unit)
            var received = 0L
            connection.inputStream.use { input ->
                download.temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_DOWNLOAD_BYTES) return false
                        output.write(buffer, 0, count)
                        output.flush()
                        download.received.value = received
                        if (download.streamId != 0L) NativeStreams.progress(download.streamId, received, 0)
                    }
                }
            }
            if (received <= 0 || (expected >= 0 && expected != received)) return false
            // The temp file may be open in the engine: a rename keeps that descriptor valid.
            if (!download.temp.renameTo(download.target)) {
                download.temp.copyTo(download.target, overwrite = true)
                download.temp.delete()
            }
            pruneCache(cacheDir, keep = download.target)
            return true
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheFile(cacheDir: File, url: String): File =
        File(cacheDir, "${CACHE_PREFIX}${sha256(url)}$CACHE_EXT")

    /** Removes stale temp files, expired entries and least-recently used tracks over [budget], never [keep]. */
    private fun pruneCache(cacheDir: File, keep: File?, budget: Long = MAX_CACHE_BYTES) {
        val now = System.currentTimeMillis()
        val allFiles = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(CACHE_PREFIX)
        }?.toList().orEmpty()

        allFiles
            .filter { it.name.endsWith(TEMP_EXT) && now - it.lastModified() > MAX_TEMP_AGE_MS }
            .forEach { it.delete() }

        allFiles
            .filter { it.name.endsWith(CACHE_EXT) && it != keep && now - it.lastModified() > MAX_CACHE_AGE_MS }
            .forEach { it.delete() }

        val audioFiles = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(CACHE_PREFIX) && file.name.endsWith(CACHE_EXT)
        }?.sortedBy { it.lastModified() }.orEmpty()   // oldest first

        var totalBytes = audioFiles.sumOf { it.length() }
        for (file in audioFiles) {
            if (totalBytes <= budget) return
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) totalBytes -= size  // only count what was really freed
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte)) }
        }
    }
}
