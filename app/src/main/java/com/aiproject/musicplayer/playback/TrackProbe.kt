package com.aiproject.musicplayer.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aiproject.musicplayer.AudioTags
import com.aiproject.musicplayer.NativeTags
import com.aiproject.musicplayer.library.LibraryIndex

/**
 * Fills in what a queued track is missing: tags (title, artist, album,
 * numbers) and duration. The library index is asked first; otherwise the file
 * is read (tags natively, length from headers, Android's retriever for the
 * formats only MediaCodec knows). Blocking: run on an I/O thread.
 */
object TrackProbe {

    /** Whether [track] still lacks something this probe can find. */
    fun needs(track: Track): Boolean {
        val local = track.uri.startsWith("content:") || track.uri.startsWith("file:")
        return local && (track.durationMs <= 0L || (track.artist.isEmpty() && track.album.isEmpty()))
    }

    /**
     * The completed track, or null when nothing new was found. In Books mode
     * the title stays the file name: audiobook files often share one title tag.
     */
    suspend fun complete(context: Context, library: LibraryIndex, track: Track, keepTitle: Boolean): Track? {
        library.entry(track.uri)?.let { e ->
            val fromIndex = LibraryIndex.toTrack(e).let { if (keepTitle) it.copy(title = track.title) else it }
            return fromIndex.copy(durationMs = if (fromIndex.durationMs > 0) fromIndex.durationMs else track.durationMs)
                .takeIf { it != track }
        }
        val parts = PlayableUri.split(track.uri)
        val file = Uri.parse(parts.fileUri)
        var tags = AudioTags()
        var fileMs = 0L
        try {
            context.contentResolver.openFileDescriptor(file, "r")?.use { pfd ->
                tags = NativeTags.tags(pfd.fd) ?: AudioTags()
                fileMs = NativeTags.durationMs(pfd.fd)
            }
        } catch (_: Exception) {
            return null
        }
        if (fileMs <= 0L) fileMs = retrieverDuration(context, file)
        val duration = when {
            track.durationMs > 0 -> track.durationMs
            parts.durationMs > 0 -> parts.durationMs
            parts.isRange -> (fileMs - parts.startUs / 1000L).coerceAtLeast(0L)
            else -> fileMs
        }
        // A CUE track already has the sheet's title and performer; the file's tags fill the gaps.
        val updated = track.copy(
            title = if (keepTitle || parts.isRange) track.title else tags.title.ifBlank { track.title },
            artist = track.artist.ifBlank { tags.artist },
            album = track.album.ifBlank { tags.album },
            trackNumber = if (track.trackNumber > 0) track.trackNumber else tags.track,
            discNumber = if (track.discNumber > 0) track.discNumber else tags.disc,
            durationMs = duration,
        )
        return updated.takeIf { it != track }
    }

    private fun retrieverDuration(context: Context, uri: Uri): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    } catch (_: Exception) {
        0L
    }
}
