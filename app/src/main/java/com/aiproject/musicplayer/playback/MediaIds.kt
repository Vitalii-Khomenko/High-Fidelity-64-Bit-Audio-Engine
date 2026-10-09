package com.aiproject.musicplayer.playback

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Media IDs of the browse tree shown by Android Auto (and other media
 * browsers). Fields are Base64url-encoded, so URIs and labels survive intact.
 */
sealed interface MediaId {
    data object Root : MediaId
    data object Empty : MediaId                   // root for callers we do not serve
    data object Queue : MediaId
    data object Playlists : MediaId
    data object Folders : MediaId
    data class QueueTrack(val index: Int, val uri: String) : MediaId
    data class Playlist(val id: Int) : MediaId
    data class PlaylistTrack(val playlistId: Int, val index: Int) : MediaId
    data class Folder(val treeUri: String, val documentId: String, val label: String) : MediaId
    data class FolderTrack(val treeUri: String, val documentId: String, val label: String, val index: Int) : MediaId

    fun encode(): String = when (this) {
        Root -> "root"
        Empty -> "empty"
        Queue -> "queue"
        Playlists -> "playlists"
        Folders -> "folders"
        is QueueTrack -> join("qt", index.toString(), uri)
        is Playlist -> join("pl", id.toString())
        is PlaylistTrack -> join("pt", playlistId.toString(), index.toString())
        is Folder -> join("fo", treeUri, documentId, label)
        is FolderTrack -> join("ft", treeUri, documentId, label, index.toString())
    }

    companion object {
        private const val SEPARATOR = '|'

        @OptIn(ExperimentalEncodingApi::class)
        private fun join(kind: String, vararg fields: String): String =
            kind + fields.joinToString("") { SEPARATOR + Base64.UrlSafe.encode(it.toByteArray(Charsets.UTF_8)) }

        @OptIn(ExperimentalEncodingApi::class)
        fun parse(value: String?): MediaId? {
            if (value == null) return null
            when (value) {
                "root" -> return Root
                "empty" -> return Empty
                "queue" -> return Queue
                "playlists" -> return Playlists
                "folders" -> return Folders
            }
            val parts = value.split(SEPARATOR)
            val fields = try {
                parts.drop(1).map { String(Base64.UrlSafe.decode(it), Charsets.UTF_8) }
            } catch (_: IllegalArgumentException) {
                return null
            }
            return try {
                when (parts.first()) {
                    "qt" -> QueueTrack(fields[0].toInt(), fields[1])
                    "pl" -> Playlist(fields[0].toInt())
                    "pt" -> PlaylistTrack(fields[0].toInt(), fields[1].toInt())
                    "fo" -> Folder(fields[0], fields[1], fields[2])
                    "ft" -> FolderTrack(fields[0], fields[1], fields[2], fields[3].toInt())
                    else -> null
                }
            } catch (_: RuntimeException) {
                null  // wrong field count or number format: an ID we did not issue
            }
        }
    }
}

/** Plain-text matching for voice search ("play <query>"). */
object MediaSearch {
    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

    /** Index of the best match: exact title, then title starting with, then containing the query. */
    fun bestMatch(query: String, titles: List<String>): Int {
        val q = normalize(query)
        if (q.isEmpty()) return -1
        val normalized = titles.map(::normalize)
        normalized.indexOfFirst { it == q }.takeIf { it >= 0 }?.let { return it }
        normalized.indexOfFirst { it.startsWith(q) }.takeIf { it >= 0 }?.let { return it }
        return normalized.indexOfFirst { it.contains(q) }
    }
}
