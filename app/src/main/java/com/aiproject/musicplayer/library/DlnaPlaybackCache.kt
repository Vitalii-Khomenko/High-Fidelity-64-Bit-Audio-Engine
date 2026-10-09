package com.aiproject.musicplayer.library

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object DlnaPlaybackCache {
    private val downloadMutex = Mutex()
    private const val MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L
    private const val CACHE_PREFIX = "dlna_"
    private const val CACHE_EXT = ".audio"
    private const val TEMP_EXT = ".tmp"
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val MAX_CACHE_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MAX_TEMP_AGE_MS = 24L * 60L * 60L * 1000L

    suspend fun resolvePlaybackUri(trackUri: Uri, cacheDir: File): Uri {
        if (!isRemoteTrackUri(trackUri)) return trackUri

        return downloadMutex.withLock { withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val cacheFile = cacheFile(cacheDir, trackUri)
            // Check for a hit before pruning, so a large cached track is not
            // deleted and downloaded again.
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                cacheFile.setLastModified(System.currentTimeMillis())
                pruneCache(cacheDir, keep = cacheFile)
                return@withContext Uri.fromFile(cacheFile)
            }
            pruneCache(cacheDir, keep = null)

            val tmpFile = tempFile(cacheDir, trackUri)
            tmpFile.delete()

            val connection = (URL(trackUri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("DLNA download failed: HTTP $code")
                }
                val expectedBytes = connection.contentLengthLong
                require(expectedBytes <= MAX_DOWNLOAD_BYTES) { "DLNA track exceeds 2 GiB download limit" }
                // Make room up front when the size is known.
                if (expectedBytes > 0) pruneCache(cacheDir, keep = null, budget = MAX_CACHE_BYTES - expectedBytes)
                var receivedBytes = 0L
                connection.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            receivedBytes += count
                            check(receivedBytes <= MAX_DOWNLOAD_BYTES) { "DLNA track exceeds 2 GiB download limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(receivedBytes > 0 && (expectedBytes < 0 || expectedBytes == receivedBytes)) {
                    "DLNA download is empty or truncated"
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
                // Back within budget; the new track itself is always kept (even
                // a single file larger than the budget is needed for playback).
                pruneCache(cacheDir, keep = cacheFile)
                Uri.fromFile(cacheFile)
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        } }
    }

    private fun isRemoteTrackUri(trackUri: Uri): Boolean =
        trackUri.scheme.equals("http", ignoreCase = true) || trackUri.scheme.equals("https", ignoreCase = true)

    private fun cacheFile(cacheDir: File, trackUri: Uri): File =
        File(cacheDir, "${CACHE_PREFIX}${sha256(trackUri.toString())}$CACHE_EXT")

    private fun tempFile(cacheDir: File, trackUri: Uri): File =
        File(cacheDir, "${CACHE_PREFIX}${sha256(trackUri.toString())}$TEMP_EXT")

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
