package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueFlowTest {

    @Test fun `repeat-off advances to next track before end`() {
        assertEquals(2, PlaybackQueueFlow.nextIndex(currentIndex = 1, playlistSize = 5, repeatMode = 0))
    }

    @Test fun `gapless repeat-all wraps last track to first`() {
        assertEquals(0, PlaybackQueueFlow.nextIndex(currentIndex = 4, playlistSize = 5, repeatMode = 2))
    }

    @Test fun `gapless repeat-one stays on current track`() {
        assertEquals(2, PlaybackQueueFlow.nextIndex(currentIndex = 2, playlistSize = 5, repeatMode = 1))
    }

    @Test fun `completion repeat-off stops at end`() {
        assertEquals(-1, PlaybackQueueFlow.nextIndex(currentIndex = 2, playlistSize = 3, repeatMode = 0))
    }

    @Test fun `invalid current index returns minus one`() {
        assertEquals(-1, PlaybackQueueFlow.nextIndex(currentIndex = -1, playlistSize = 3, repeatMode = 2))
    }

    @Test fun `single track repeat-all stays on same track`() {
        assertEquals(0, PlaybackQueueFlow.nextIndex(currentIndex = 0, playlistSize = 1, repeatMode = 2))
    }

    @Test fun `completion decision for repeat one replays current track`() {
        assertEquals(
            PlaybackCompletionDecision(nextIndex = 2, replayCurrent = true, shouldStop = false),
            PlaybackQueueFlow.completionDecision(currentIndex = 2, playlistSize = 5, repeatMode = 1)
        )
    }

    @Test fun `completion decision for repeat off at end stops playback`() {
        assertEquals(
            PlaybackCompletionDecision(nextIndex = -1, replayCurrent = false, shouldStop = true),
            PlaybackQueueFlow.completionDecision(currentIndex = 2, playlistSize = 3, repeatMode = 0)
        )
    }

    @Test fun `completion decision for repeat all advances to first track at end`() {
        assertEquals(
            PlaybackCompletionDecision(nextIndex = 0, replayCurrent = false, shouldStop = false),
            PlaybackQueueFlow.completionDecision(currentIndex = 2, playlistSize = 3, repeatMode = 2)
        )
    }
}