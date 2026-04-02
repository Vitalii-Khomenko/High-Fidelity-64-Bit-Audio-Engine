package com.aiproject.musicplayer

data class PlaybackCompletionDecision(
    val nextIndex: Int,
    val replayCurrent: Boolean,
    val shouldStop: Boolean
)

object PlaybackQueueFlow {
    fun nextIndex(currentIndex: Int, playlistSize: Int, repeatMode: Int): Int {
        if (currentIndex !in 0 until playlistSize) return -1
        val navigator = PlaylistNavigator { playlistSize }.also {
            it.jumpTo(currentIndex)
            it.repeatMode = repeatMode
        }
        return navigator.nextIndex()
    }

    fun completionDecision(currentIndex: Int, playlistSize: Int, repeatMode: Int): PlaybackCompletionDecision {
        if (currentIndex !in 0 until playlistSize) {
            return PlaybackCompletionDecision(nextIndex = -1, replayCurrent = false, shouldStop = true)
        }
        if (repeatMode == 1) {
            return PlaybackCompletionDecision(nextIndex = currentIndex, replayCurrent = true, shouldStop = false)
        }
        val next = nextIndex(currentIndex = currentIndex, playlistSize = playlistSize, repeatMode = repeatMode)
        return PlaybackCompletionDecision(nextIndex = next, replayCurrent = false, shouldStop = next !in 0 until playlistSize)
    }
}