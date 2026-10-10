package com.aiproject.musicplayer.playback

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.aiproject.musicplayer.library.SupportedFormats

/**
 * Audio handed over by other apps: "Open with" (ACTION_VIEW) from a file
 * manager, or "Share" (ACTION_SEND / SEND_MULTIPLE). Tags and durations are
 * filled in afterwards by the service like for any new queue.
 */
object ExternalAudio {

    /** The audio URIs an intent carries; empty for any other intent. */
    @Suppress("DEPRECATION")
    fun uris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ).orEmpty().filterNotNull()
        else -> emptyList()
    }.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }

    /**
     * Tracks for the playable ones (playlists such as .m3u come with an audio MIME type too).
     * Keeps the read grant across restarts where the sending app allows it.
     */
    fun tracks(resolver: ContentResolver, uris: List<Uri>): List<Track> = uris.mapNotNull { uri ->
        val name = displayName(resolver, uri)
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        if (!SupportedFormats.isSupported(name, mime)) return@mapNotNull null
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Track(uri.toString(), SupportedFormats.displayTitle(name).ifBlank { name })
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
}
