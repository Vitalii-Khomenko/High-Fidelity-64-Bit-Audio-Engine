package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test fun `clamp enforces minimum speed`() {
        assertEquals(0.75f, PlaybackSpeed.clamp(0.5f), 0.0001f)
    }

    @Test fun `clamp enforces maximum speed`() {
        assertEquals(2.0f, PlaybackSpeed.clamp(2.5f), 0.0001f)
    }

    @Test fun `snap keeps exact preset`() {
        assertEquals(1.25f, PlaybackSpeed.snapToPreset(1.25f), 0.0001f)
    }

    @Test fun `snap chooses nearest preset`() {
        assertEquals(1.1f, PlaybackSpeed.snapToPreset(1.08f), 0.0001f)
        assertEquals(1.25f, PlaybackSpeed.snapToPreset(1.18f), 0.0001f)
        assertEquals(1.75f, PlaybackSpeed.snapToPreset(1.82f), 0.0001f)
    }

    @Test fun `snap clamps before selecting preset`() {
        assertEquals(0.75f, PlaybackSpeed.snapToPreset(0.2f), 0.0001f)
        assertEquals(2.0f, PlaybackSpeed.snapToPreset(3.0f), 0.0001f)
    }
}