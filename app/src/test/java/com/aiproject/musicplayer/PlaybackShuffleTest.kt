package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackShuffleTest {

    @Test fun `build queue includes every other track once`() {
        val queue = PlaybackShuffle.buildQueue(currentIndex = 2, playlistSize = 5, random = Random(0))
        assertEquals(4, queue.size)
        assertEquals(setOf(0, 1, 3, 4), queue.toSet())
    }

    @Test fun `repeat one keeps current track`() {
        assertEquals(2, PlaybackShuffle.nextIndex(2, 5, repeatMode = 1))
    }

    @Test fun `queued next index is reused for consistency`() {
        assertEquals(4, PlaybackShuffle.nextIndex(1, 5, repeatMode = 0, queuedNextIndex = 4))
    }

    @Test fun `shuffle never returns current track when playlist has multiple items`() {
        val next = PlaybackShuffle.nextIndex(
            currentIndex = 1,
            playlistSize = 4,
            repeatMode = 0,
            random = Random(0)
        )
        assertTrue(next in 0 until 4)
        assertTrue(next != 1)
    }

    @Test fun `repeat off single track stops`() {
        assertEquals(-1, PlaybackShuffle.nextIndex(0, 1, repeatMode = 0))
    }

    @Test fun `repeat all single track stays on same track`() {
        assertEquals(0, PlaybackShuffle.nextIndex(0, 1, repeatMode = 2))
    }

    @Test fun `preview uses queued shuffle order first`() {
        assertEquals(
            4,
            PlaybackShuffle.previewNextIndex(
                currentIndex = 1,
                playlistSize = 5,
                repeatMode = 0,
                queue = listOf(4, 2, 3),
            )
        )
    }

    @Test fun `queue advance consumes first entry`() {
        val remaining = PlaybackShuffle.queueAfterAdvance(
            currentIndex = 1,
            nextIndex = 4,
            playlistSize = 5,
            repeatMode = 0,
            queue = listOf(4, 2, 3),
        )
        assertEquals(listOf(2, 3), remaining)
    }

    @Test fun `repeat all rebuilds queue after it is exhausted`() {
        val rebuilt = PlaybackShuffle.queueAfterAdvance(
            currentIndex = 1,
            nextIndex = 4,
            playlistSize = 5,
            repeatMode = 2,
            queue = listOf(4),
            random = Random(0),
        )
        assertEquals(4, rebuilt.size)
        assertTrue(rebuilt.toSet() == setOf(0, 1, 2, 3))
    }

    @Test fun `history push and pop behave like a stack`() {
        val pushed = PlaybackShuffle.pushHistory(listOf(1, 2), 3)
        assertEquals(listOf(1, 2, 3), pushed)
        val (prev, remaining) = PlaybackShuffle.popHistory(pushed)
        assertEquals(3, prev)
        assertEquals(listOf(1, 2), remaining)
    }
}