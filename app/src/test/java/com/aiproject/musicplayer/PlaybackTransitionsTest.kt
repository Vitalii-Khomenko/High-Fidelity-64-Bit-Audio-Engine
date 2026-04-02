package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionsTest {

    @Test fun `user pause fade is shorter than stop and normal focus fade`() {
        assertTrue(PlaybackTransitions.USER_PAUSE_FADE_MS < PlaybackTransitions.STOP_FADE_MS)
        assertTrue(PlaybackTransitions.USER_PAUSE_FADE_MS < PlaybackTransitions.FOCUS_FADE_MS)
    }

    @Test fun `fast focus fade is shorter than normal focus fade`() {
        assertTrue(PlaybackTransitions.FAST_FOCUS_FADE_MS < PlaybackTransitions.FOCUS_FADE_MS)
        assertEquals(
            PlaybackTransitions.FAST_FOCUS_FADE_MS,
            PlaybackTransitions.focusPauseFadeMs(fast = true)
        )
        assertEquals(
            PlaybackTransitions.FOCUS_FADE_MS,
            PlaybackTransitions.focusPauseFadeMs(fast = false)
        )
    }

    @Test fun `switch fade is at least as long as stop fade`() {
        assertTrue(PlaybackTransitions.SWITCH_FADE_MS >= PlaybackTransitions.STOP_FADE_MS)
    }

    @Test fun `start and resume preroll stay positive and small`() {
        assertTrue(PlaybackTransitions.START_PREROLL_MS in 1L..100L)
        assertTrue(PlaybackTransitions.RESUME_PREROLL_MS in 1L..100L)
    }

    @Test fun `sleep timer final fade stays minimal because the long fade already happened upstream`() {
        assertTrue(PlaybackTransitions.SLEEP_TIMER_FINAL_FADE_MS in 0L..40L)
        assertTrue(PlaybackTransitions.SLEEP_TIMER_FINAL_FADE_MS < PlaybackTransitions.USER_PAUSE_FADE_MS)
    }
}