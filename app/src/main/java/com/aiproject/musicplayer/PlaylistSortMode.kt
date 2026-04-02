package com.aiproject.musicplayer

enum class PlaylistSortMode(val id: Int, val label: String) {
    NAME(0, "Sort: Name"),
    NUMBER(1, "Sort: Number");

    companion object {
        fun fromId(id: Int): PlaylistSortMode = entries.firstOrNull { it.id == id } ?: NAME
    }
}