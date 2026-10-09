package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.StreamFormat

/** Everything the UI renders, published by PlaybackService as a StateFlow. */
data class PlayerState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val shuffle: Boolean = false,
    val settings: PlayerSettings = PlayerSettings(),
    val format: StreamFormat? = null,
    val playedUris: Set<String> = emptySet(),
    /** SystemClock.elapsedRealtime() at which the sleep timer pauses, 0 when off. */
    val sleepTimerEndsAt: Long = 0L,
) {
    val current: Track? get() = tracks.getOrNull(currentIndex)
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
}

/** Updated several times per second; kept apart so the queue does not recompose. */
data class PlaybackPosition(val positionMs: Long = 0L, val durationMs: Long = 0L)
