package com.aiproject.musicplayer

object PlaylistMerge {
    fun <T> appendDistinctBy(
        existing: List<T>,
        incoming: List<T>,
        keySelector: (T) -> String
    ): List<T> {
        if (incoming.isEmpty()) return existing
        val seenKeys = existing.asSequence().map(keySelector).toHashSet()
        val appended = incoming.filter { track -> seenKeys.add(keySelector(track)) }
        return if (appended.isEmpty()) existing else existing + appended
    }
}