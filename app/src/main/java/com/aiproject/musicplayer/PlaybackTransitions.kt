package com.aiproject.musicplayer

object PlaybackTransitions {
    const val USER_PAUSE_FADE_MS = 24L
    const val STOP_FADE_MS = 70L
    const val SWITCH_FADE_MS = 210L
    const val FOCUS_FADE_MS = 140L
    const val FAST_FOCUS_FADE_MS = 70L
    const val SLEEP_TIMER_FINAL_FADE_MS = 20L
    const val RESUME_PREROLL_MS = 32L
    const val START_PREROLL_MS = 32L

    fun focusPauseFadeMs(fast: Boolean): Long = if (fast) FAST_FOCUS_FADE_MS else FOCUS_FADE_MS
}