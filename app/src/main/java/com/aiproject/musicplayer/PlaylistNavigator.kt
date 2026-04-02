package com.aiproject.musicplayer

/**
 * Pure-Kotlin playlist navigation logic with repeat-mode support.
 * No Android imports — fully unit-testable on the JVM.
 *
 * Repeat modes:
 *   0 = off   — play until end, then stop
 *   1 = one   — repeat current track forever
 *   2 = all   — wrap around to beginning after last track
 */
class PlaylistNavigator(
    private val getSize: () -> Int,
) {
    var currentIndex = 0
        private set

    var repeatMode = 0   // 0=off, 1=one, 2=all
        set(value) { field = value.coerceIn(0, 2) }

    // ── Navigation ────────────────────────────────────────────────────────────

    /**
     * Returns the index of the next track, or -1 if playback should stop.
     * Does NOT advance [currentIndex] — call [advance] for that.
     */
    fun nextIndex(): Int {
        val size = getSize()
        if (size == 0) return -1
        return when (repeatMode) {
            1    -> currentIndex                                          // repeat one
            2    -> (currentIndex + 1) % size                            // repeat all — wrap
            else -> if (currentIndex < size - 1) currentIndex + 1 else -1 // off — stop at end
        }
    }

    /**
     * Returns the index of the previous track.
     * Never goes below 0.
     */
    fun prevIndex(): Int {
        if (getSize() == 0) return -1
        return if (currentIndex > 0) currentIndex - 1 else 0
    }

    /**
     * Advances [currentIndex] to the next track.
     * @return true if there is a next track, false if playback should stop.
     */
    fun advance(): Boolean {
        val next = nextIndex()
        return if (next >= 0) {
            currentIndex = next
            true
        } else false
    }

    /** Moves to the previous track. */
    fun goBack() {
        val prev = prevIndex()
        if (prev >= 0) currentIndex = prev
    }

    /** Jump directly to [index] (e.g. user taps a playlist item). */
    fun jumpTo(index: Int) {
        val size = getSize()
        if (size > 0) currentIndex = index.coerceIn(0, size - 1)
    }
}
