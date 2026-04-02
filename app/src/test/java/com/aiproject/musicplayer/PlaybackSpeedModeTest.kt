package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedModeTest {

    @Test fun `fromId returns matching mode`() {
        assertEquals(PlaybackSpeedMode.MUSIC, PlaybackSpeedMode.fromId(0))
        assertEquals(PlaybackSpeedMode.SPEECH, PlaybackSpeedMode.fromId(1))
    }

    @Test fun `fromId falls back to music`() {
        assertEquals(PlaybackSpeedMode.MUSIC, PlaybackSpeedMode.fromId(99))
    }
}