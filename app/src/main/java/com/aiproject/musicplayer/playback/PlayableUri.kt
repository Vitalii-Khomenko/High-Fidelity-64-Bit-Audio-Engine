package com.aiproject.musicplayer.playback

/**
 * A track of a CUE sheet is a time range of a bigger audio file. Its URI is the
 * file's URI plus a fragment "#hifi-cue=<startUs>-<endUs>" (endUs 0 = to the
 * end of the file). Content and file URIs never contain a raw '#', so the
 * fragment is unambiguous, and every CUE track keeps a distinct URI for the
 * queue, bookmarks and playlists.
 */
object PlayableUri {
    private const val MARK = "#hifi-cue="

    data class Parts(val fileUri: String, val startUs: Long = 0L, val endUs: Long = 0L) {
        val isRange: Boolean get() = startUs > 0L || endUs > 0L
        /** Length of the range, 0 when it runs to the (unknown) end of the file. */
        val durationMs: Long get() = if (endUs > startUs) (endUs - startUs) / 1000L else 0L
    }

    fun withRange(fileUri: String, startUs: Long, endUs: Long): String =
        "$fileUri$MARK${startUs.coerceAtLeast(0L)}-${endUs.coerceAtLeast(0L)}"

    fun split(uri: String): Parts {
        val at = uri.lastIndexOf(MARK)
        if (at < 0) return Parts(uri)
        val range = uri.substring(at + MARK.length)
        val start = range.substringBefore('-').toLongOrNull() ?: return Parts(uri)
        val end = range.substringAfter('-', "").toLongOrNull() ?: 0L
        return Parts(uri.substring(0, at), start.coerceAtLeast(0L), end.coerceAtLeast(0L))
    }

    fun isRange(uri: String): Boolean = uri.contains(MARK)
}
