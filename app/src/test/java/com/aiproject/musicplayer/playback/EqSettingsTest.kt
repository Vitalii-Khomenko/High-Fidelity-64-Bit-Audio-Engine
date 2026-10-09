package com.aiproject.musicplayer.playback

import org.junit.Assert.*
import org.junit.Test

class EqSettingsTest {
    @Test fun `malformed saved gains do not poison DSP with NaN`() {
        val settings = EqSettings.deserialize(true, "NaN,Infinity,-Infinity,5,-3")
        assertEquals(listOf(0f, 0f, 0f, 5f, -3f), settings.bandGainsDb)
    }
    @Test fun `short gain list can be edited safely`() {
        val settings = EqSettings(bandGainsDb = emptyList()).withBandGain(4, 20f)
        assertEquals(5, settings.bandGainsDb.size)
        assertEquals(12f, settings.bandGainsDb[4])
    }
}
