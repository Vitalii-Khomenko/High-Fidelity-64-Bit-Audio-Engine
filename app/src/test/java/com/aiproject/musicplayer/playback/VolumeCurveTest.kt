package com.aiproject.musicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class VolumeCurveTest {
    private fun db(gain: Double) = 20 * log10(gain)

    @Test fun `top is exactly unity and bottom is silence`() {
        assertEquals(1.0, VolumeCurve.gain(1f), 0.0)   // bit-perfect needs exactly 1
        assertEquals(0.0, VolumeCurve.gain(0f), 0.0)
        assertEquals("0 dB", VolumeCurve.label(1f))
        assertEquals("Mute", VolumeCurve.label(0f))
    }

    @Test fun `equal travel is an equal change in loudness`() {
        assertEquals(-30.0, db(VolumeCurve.gain(0.5f)), 1e-6)
        assertEquals(-6.0, db(VolumeCurve.gain(0.9f)), 1e-4)
        val a = db(VolumeCurve.gain(0.2f)) - db(VolumeCurve.gain(0.3f))
        val b = db(VolumeCurve.gain(0.7f)) - db(VolumeCurve.gain(0.8f))
        assertEquals(a, b, 1e-6)
        var last = 0.0
        for (i in 1..100) {
            val g = VolumeCurve.gain(i / 100f)
            assertTrue(g > last)
            last = g
        }
    }

    @Test fun `volume keys move 2 dB`() {
        val one = VolumeCurve.gain(29f / VolumeCurve.STEPS)
        assertEquals(-2.0, db(one), 1e-4)
        assertEquals(30, VolumeCurve.step(1f))
        assertEquals(27, VolumeCurve.step(0.9f))
    }

    @Test fun `saved linear volumes keep their loudness`() {
        assertEquals(1f, VolumeCurve.positionOf(1.0), 0f)
        assertEquals(0f, VolumeCurve.positionOf(0.0), 0f)
        for (linear in listOf(0.5, 0.25, 0.1, 0.01)) {
            assertEquals(db(linear), db(VolumeCurve.gain(VolumeCurve.positionOf(linear))), 1e-3)
        }
        assertEquals(0f, VolumeCurve.positionOf(1e-6), 0f)   // below −60 dB: the bottom
        assertEquals("−6.0 dB", VolumeCurve.label(VolumeCurve.positionOf(0.5011872)))
    }
}
