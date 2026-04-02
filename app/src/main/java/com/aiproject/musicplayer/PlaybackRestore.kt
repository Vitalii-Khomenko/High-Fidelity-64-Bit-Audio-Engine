package com.aiproject.musicplayer

object PlaybackRestore {
    fun findTrackIndexByUri(playlistUris: List<String>, currentUri: String?): Int {
        if (currentUri.isNullOrBlank()) return -1
        return playlistUris.indexOf(currentUri)
    }
}