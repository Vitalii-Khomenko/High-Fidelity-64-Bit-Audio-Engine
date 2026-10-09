package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.playback.AudioFocusPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFocusPolicyTest {
    @Test fun `transient loss pauses and gain resumes`() {
        val policy = AudioFocusPolicy()
        assertEquals(Action.PAUSE, policy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, isPlaying = true))
        assertEquals(Action.RESUME, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = false))
        assertEquals(Action.NONE, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = true))
    }

    @Test fun `permanent loss never resumes by itself`() {
        val policy = AudioFocusPolicy()
        policy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, isPlaying = true)
        assertEquals(Action.PAUSE_AND_FORGET, policy.onFocusChange(AudioFocusPolicy.LOSS, isPlaying = true))
        assertEquals(Action.NONE, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = false))
    }

    @Test fun `transient loss while paused does not schedule a resume`() {
        val policy = AudioFocusPolicy()
        assertEquals(Action.NONE, policy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, isPlaying = false))
        assertEquals(Action.NONE, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = false))
    }

    @Test fun `user action cancels a pending resume`() {
        val policy = AudioFocusPolicy()
        policy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, isPlaying = true)
        policy.onUserAction()
        assertEquals(Action.NONE, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = false))
    }

    @Test fun `duck and restore`() {
        val policy = AudioFocusPolicy()
        assertEquals(Action.DUCK, policy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK, isPlaying = true))
        assertEquals(Action.UNDUCK, policy.onFocusChange(AudioFocusPolicy.GAIN, isPlaying = true))
    }
}
