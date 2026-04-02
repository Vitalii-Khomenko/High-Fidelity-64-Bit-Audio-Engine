package com.aiproject.musicplayer

enum class PlaybackSpeedMode(val id: Int, val label: String) {
    MUSIC(0, "Music"),
    SPEECH(1, "Speech");

    companion object {
        fun fromId(id: Int): PlaybackSpeedMode = entries.firstOrNull { it.id == id } ?: MUSIC
    }
}