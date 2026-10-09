package com.aiproject.musicplayer.playback

/**
 * Audio-focus decisions, kept free of Android types so they can be unit-tested.
 * Values follow AudioManager.AUDIOFOCUS_*.
 */
class AudioFocusPolicy {
    enum class Action { NONE, PAUSE, PAUSE_AND_FORGET, DUCK, UNDUCK, RESUME }

    companion object {
        const val GAIN = 1
        const val LOSS = -1
        const val LOSS_TRANSIENT = -2
        const val LOSS_TRANSIENT_CAN_DUCK = -3
        const val DUCK_FACTOR = 0.25
    }

    /** True when a transient loss paused playback that should resume on gain. */
    var resumeOnGain = false
        private set
    var ducked = false
        private set

    fun onFocusChange(change: Int, isPlaying: Boolean): Action = when (change) {
        LOSS -> {
            // Another player took over for good: stop and do not come back by itself.
            resumeOnGain = false
            ducked = false
            if (isPlaying) Action.PAUSE_AND_FORGET else Action.NONE
        }
        LOSS_TRANSIENT -> {
            if (isPlaying) {
                resumeOnGain = true
                Action.PAUSE
            } else {
                Action.NONE
            }
        }
        LOSS_TRANSIENT_CAN_DUCK -> {
            ducked = true
            Action.DUCK
        }
        GAIN -> when {
            resumeOnGain -> {
                resumeOnGain = false
                ducked = false
                Action.RESUME
            }
            ducked -> {
                ducked = false
                Action.UNDUCK
            }
            else -> Action.NONE
        }
        else -> Action.NONE
    }

    /** The user took control (pause/stop/new track): never auto-resume afterwards. */
    fun onUserAction() {
        resumeOnGain = false
    }
}
