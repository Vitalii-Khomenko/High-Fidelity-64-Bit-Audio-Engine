package com.aiproject.musicplayer

data class LibraryBrowseLocation(
    val documentId: String,
    val label: String,
)

data class LibraryBrowserEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val track: AudioTrack? = null,
)