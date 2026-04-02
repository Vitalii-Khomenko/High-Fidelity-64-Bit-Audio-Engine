package com.aiproject.musicplayer

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object DlnaPlaybackCache {
    suspend fun resolvePlaybackUri(trackUri: Uri, cacheDir: File): Uri {
        if (!isRemoteTrackUri(trackUri)) return trackUri

        return withContext(Dispatchers.IO) {
            val cacheFile = cacheFile(cacheDir, trackUri)
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                return@withContext Uri.fromFile(cacheFile)
            }

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
                connection.inputStream.use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
                Uri.fromFile(cacheFile)
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun isRemoteTrackUri(trackUri: Uri): Boolean =
        trackUri.scheme?.startsWith("http", ignoreCase = true) == true

    private fun cacheFile(cacheDir: File, trackUri: Uri): File =
        File(cacheDir, "dlna_${trackUri.toString().hashCode()}.audio")

    private fun tempFile(cacheDir: File, trackUri: Uri): File =
        File(cacheDir, "dlna_${trackUri.toString().hashCode()}.tmp")
}