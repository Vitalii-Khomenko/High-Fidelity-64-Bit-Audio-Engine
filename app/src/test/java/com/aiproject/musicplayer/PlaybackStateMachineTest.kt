package com.aiproject.musicplayer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PlaybackStateMachine.
 *
 * Each test is named after the bug it prevents — if you break the logic,
 * the test name tells you exactly which real-world bug you reintroduced.
 */
class PlaybackStateMachineTest {

    // ── Test doubles ──────────────────────────────────────────────────────────

    private var pauseCount   = 0
    private var playCount    = 0
    private var duckLevel    = 1.0
    private var abandonCount = 0
    private var enginePlaying = true
    private var hasTrack      = true

    @Before fun resetCounters() {
        pauseCount = 0; playCount = 0; duckLevel = 1.0; abandonCount = 0
        enginePlaying = true; hasTrack = true
    }

    private fun machine() = PlaybackStateMachine(
        doFadeOutAndPause  = { pauseCount++ },
        doFadeInAndPlay    = { playCount++ },
        doDuck             = { duckLevel = it },
        doRestoreVolume    = { duckLevel = 1.0 },
        doAbandonFocus     = { abandonCount++ },
        isEnginePlayingNow = { enginePlaying },
        hasCurrentTrack    = { hasTrack }
    )

    // ── Bug regressions ───────────────────────────────────────────────────────

    /**
     * BUG (fixed): Switching tracks caused the new track to stop after ~1 second.
     * Root cause: requestAudioFocus() created a new request WITHOUT abandoning
     * the old one first. Android then fired AUDIOFOCUS_LOSS on the OLD listener,
     * setting isManualStop=true on the NEW track.
     */
    @Test fun `BUG track-stops-after-1sec - beforeRequestFocus must abandon old focus`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        assertEquals("No abandon calls yet", 0, abandonCount)
        sm.beforeRequestFocus()
        assertEquals("Must abandon old request before creating new one", 1, abandonCount)
    }

    /**
     * BUG (fixed): Pressing microphone in a chat app triggered the next song.
     * Root cause: AUDIOFOCUS_LOSS_TRANSIENT called engine.pause() directly,
     * leaving isManualStop=false → completionJob saw engine stopped without
     * manual stop → fired onTrackCompleted → next song.
     */
    @Test fun `BUG mic-triggers-next-song - TRANSIENT must set isManualStop true`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS_TRANSIENT)
        assertTrue("isManualStop must be true to block completionJob", sm.isManualStop)
        assertTrue("pausedByFocus must be true for auto-resume", sm.pausedByFocus)
        assertEquals("Engine must be paused", 1, pauseCount)
    }

    /**
     * BUG (fixed): Sending a voice message fully stopped the song instead of pausing.
     * Root cause: AUDIOFOCUS_LOSS set pausedByFocusLoss=false AND called abandonAudioFocus().
     * So when the voice message finished, AUDIOFOCUS_GAIN never arrived → no auto-resume.
     */
    @Test fun `BUG voice-msg-stops-song - LOSS must set pausedByFocus true`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS)
        assertTrue("pausedByFocus must be true for auto-resume when they finish", sm.pausedByFocus)
        assertTrue("isManualStop must be true to block completionJob", sm.isManualStop)
        assertEquals("Must pause", 1, pauseCount)
        assertEquals("Must NOT abandon focus — need AUDIOFOCUS_GAIN callback", 0, abandonCount)
    }

    /**
     * BUG (fixed): After finishing a voice message, the player UI showed 'playing'
     * but there was no sound.
     * Root cause: AUDIOFOCUS_GAIN with pausedByFocusLoss=false only called setVolume(),
     * never play(). Engine stayed paused, UI state showed playing.
     */
    @Test fun `BUG ui-playing-no-sound - GAIN must resume when pausedByFocus is true`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS_TRANSIENT)
        enginePlaying = false  // engine is now paused
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertFalse("pausedByFocus must be cleared after resume", sm.pausedByFocus)
        assertFalse("isManualStop must be false after resume", sm.isManualStop)
        assertEquals("Engine must be started again", 1, playCount)
    }

    // ── Correct-behaviour tests ───────────────────────────────────────────────

    @Test fun `user pause sets isManualStop and abandons focus`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onUserPause()
        assertTrue("isManualStop must be true", sm.isManualStop)
        assertFalse("pausedByFocus must be false (user pause ≠ focus loss)", sm.pausedByFocus)
        assertEquals("Must abandon focus on user pause", 1, abandonCount)
        assertEquals("Must fade out", 1, pauseCount)
    }

    @Test fun `user pause must NOT auto-resume on AUDIOFOCUS_GAIN`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onUserPause()
        enginePlaying = false
        // Simulate: another app had focus briefly and now releases it
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertEquals("Must NOT resume after explicit user pause", 0, playCount)
    }

    @Test fun `shouldFireCompletion is true during normal playback`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        assertTrue("Completion should fire normally", sm.shouldFireCompletion())
    }

    @Test fun `shouldFireCompletion is false after user pause`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onUserPause()
        assertFalse("Completion must NOT fire after pause", sm.shouldFireCompletion())
    }

    @Test fun `shouldFireCompletion is false after focus loss`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS_TRANSIENT)
        assertFalse("Completion must NOT fire when paused by focus loss", sm.shouldFireCompletion())
    }

    @Test fun `FOCUS_CAN_DUCK reduces volume below 1`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_CAN_DUCK)
        assertTrue("Volume must be ducked below 1.0", duckLevel < 1.0)
        assertTrue("Volume must be above 0", duckLevel > 0.0)
    }

    @Test fun `FOCUS_GAIN after duck restores volume without resume`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_CAN_DUCK)
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertEquals("Should NOT call play() — engine is already playing", 0, playCount)
        assertEquals("Volume must be restored to 1.0", 1.0, duckLevel, 0.001)
    }

    @Test fun `safety net resumes when engine unexpectedly stopped`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        enginePlaying = false  // engine stopped for unknown reason
        // isManualStop is still false, hasTrack is true → safety net should kick in
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertEquals("Safety net must resume playback", 1, playCount)
    }

    @Test fun `safety net does NOT resume when user manually stopped`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onUserStop()
        enginePlaying = false
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertEquals("Must NOT resume after explicit user stop", 0, playCount)
    }

    @Test fun `TRANSIENT does not pause when engine is already stopped`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        enginePlaying = false
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS_TRANSIENT)
        assertEquals("Must not call pause when already stopped", 0, pauseCount)
    }

    @Test fun `onPlayTrackStarted resets state from previous session`() {
        val sm = machine()
        sm.onPlayTrackStarted()
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS)  // sets isManualStop, pausedByFocus
        assertTrue(sm.isManualStop)
        assertTrue(sm.pausedByFocus)
        sm.onPlayTrackStarted()  // new track starts
        assertFalse("isManualStop must reset", sm.isManualStop)
        assertFalse("pausedByFocus must reset", sm.pausedByFocus)
    }

    @Test fun `full round-trip - play, focus loss, focus gain`() {
        val sm = machine()
        // Track starts
        sm.onPlayTrackStarted()
        assertFalse(sm.isManualStop)
        assertTrue(sm.shouldFireCompletion())
        // Voice message takes focus
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_LOSS)
        assertTrue(sm.isManualStop)
        assertFalse(sm.shouldFireCompletion())
        // Voice message ends, focus returns
        enginePlaying = false
        sm.onAudioFocusChange(PlaybackStateMachine.FOCUS_GAIN)
        assertFalse(sm.isManualStop)
        assertTrue(sm.shouldFireCompletion())
        assertEquals("Music must resume", 1, playCount)
    }
}
