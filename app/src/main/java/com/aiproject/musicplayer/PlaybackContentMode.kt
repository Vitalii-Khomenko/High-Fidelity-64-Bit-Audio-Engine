package com.aiproject.musicplayer

enum class PlaybackContentMode(
    val id: Int,
    val label: String,
    val remembersTrackProgress: Boolean,
    val showsPlayedState: Boolean,
) {
    MUSIC(
        id = 0,
        label = "Music",
        remembersTrackProgress = false,
        showsPlayedState = false,
    ),
    BOOKS(
        id = 1,
        label = "Books",
        remembersTrackProgress = true,
        showsPlayedState = true,
    );

    companion object {
        fun fromId(id: Int): PlaybackContentMode =
            entries.firstOrNull { it.id == id } ?: BOOKS
    }
}