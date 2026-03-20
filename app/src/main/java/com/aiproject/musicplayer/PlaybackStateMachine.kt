package com.aiproject.musicplayer

/**
 * Pure-Kotlin state machine for audio focus and track completion logic.
 * No Android imports — fully unit-testable on the JVM.
 *
 * AudioManager integer constants (compile-time, safe to use without Android runtime):
 *   AUDIOFOCUS_GAIN                    =  1
 *   AUDIOFOCUS_LOSS                    = -1
 *   AUDIOFOCUS_LOSS_TRANSIENT          = -2
 *   AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
 */
class PlaybackStateMachine(
    /** Trigger a smooth fade-out and then pause the engine. */
    private val doFadeOutAndPause: () -> Unit,
    /** Trigger a smooth fade-in and then start the engine. Restart completionJob. */
    private val doFadeInAndPlay: () -> Unit,
    /** Lower engine volume to [level] (0.0–1.0). */
    private val doDuck: (Double) -> Unit,
    /** Restore engine volume to full. */
    private val doRestoreVolume: () -> Unit,
    /** Abandon the current AudioFocusRequest. */
    private val doAbandonFocus: () -> Unit,
    /** Returns true if the audio engine is currently playing. */
    private val isEnginePlayingNow: () -> Boolean,
    /** Returns true if a track is loaded (currentTitle not empty). */
    private val hasCurrentTrack: () -> Boolean,
) {
    companion object {
        const val FOCUS_GAIN           =  1
        const val FOCUS_LOSS           = -1
        const val FOCUS_LOSS_TRANSIENT = -2
        const val FOCUS_CAN_DUCK       = -3
    }

    /** True → engine was paused by focus loss, will auto-resume on AUDIOFOCUS_GAIN. */
    var pausedByFocus = false
        private set

    /**
     * True → playback was stopped intentionally (user pause/stop, or focus loss).
     * When true, the completionJob must NOT fire onTrackCompleted.
     */
    var isManualStop = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Call at the start of every new track. Resets all state. */
    fun onPlayTrackStarted() {
        isManualStop  = false
        pausedByFocus = false
    }

    /** Call before constructing a new AudioFocusRequest. Clears the old one. */
    fun beforeRequestFocus() {
        doAbandonFocus()
    }

    /** User pressed Pause. */
    fun onUserPause() {
        isManualStop  = true
        pausedByFocus = false   // do NOT auto-resume after user-pause
        doFadeOutAndPause()
        doAbandonFocus()
    }

    /** User pressed Stop or swiped away the app. */
    fun onUserStop() {
        isManualStop  = true
        pausedByFocus = false
        doAbandonFocus()
    }

    // ── Audio focus ───────────────────────────────────────────────────────────

    /**
     * Handle the integer value delivered by [AudioManager.OnAudioFocusChangeListener].
     * Call this from the focus listener in PlaybackService.
     */
    fun onAudioFocusChange(change: Int) {
        when (change) {
            FOCUS_LOSS -> {
                // Permanent loss (another media app, voice recorder, call).
                // Keep the focus request alive → expect AUDIOFOCUS_GAIN when they finish.
                pausedByFocus = true
                isManualStop  = true
                doFadeOutAndPause()
                // intentionally NOT calling doAbandonFocus()
            }

            FOCUS_LOSS_TRANSIENT -> {
                // Transient loss (microphone press, navigation voice, etc.)
                if (isEnginePlayingNow()) {
                    pausedByFocus = true
                    isManualStop  = true
                    doFadeOutAndPause()
                    // intentionally NOT calling doAbandonFocus()
                }
            }

            FOCUS_CAN_DUCK -> {
                doDuck(0.2)
            }

            FOCUS_GAIN -> when {
                pausedByFocus -> {
                    // Normal auto-resume after transient/permanent loss
                    pausedByFocus = false
                    isManualStop  = false
                    doFadeInAndPlay()
                }
                !isEnginePlayingNow() && !isManualStop && hasCurrentTrack() -> {
                    // Safety net: engine unexpectedly stopped (race condition / duck edge case)
                    doFadeInAndPlay()
                }
                else -> doRestoreVolume()   // restore from duck
            }
        }
    }

    // ── Completion guard ──────────────────────────────────────────────────────

    /**
     * Returns true if the completionJob should fire onTrackCompleted.
     * False when playback was stopped manually or by focus loss.
     */
    fun shouldFireCompletion(): Boolean = !isManualStop
}
