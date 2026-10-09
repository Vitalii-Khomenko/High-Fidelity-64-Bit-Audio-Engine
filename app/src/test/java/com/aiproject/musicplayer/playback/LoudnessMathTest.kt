package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.db.LoudnessEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessMathTest {
    private fun e(lufs: Double?, peak: Double?, s: Double) = LoudnessEntity("u$lufs$s", lufs, peak, s, 0)

    @Test fun `gain is the distance to -18 LUFS`() {
        assertEquals(-5.0, LoudnessMath.gainDb(-13.0), 1e-12)
        assertEquals(5.0, LoudnessMath.gainDb(-23.0), 1e-12)
        assertEquals(1.0, LoudnessMath.peakLinear(0.0), 1e-12)
    }

    @Test fun `album loudness is the duration weighted energy mean`() {
        assertEquals(-10.0, LoudnessMath.albumLufs(listOf(-10.0 to 100.0, -10.0 to 50.0))!!, 1e-9)
        // Equal durations, 10 LU apart: 10*log10((1 + 0.1) / 2) above the quieter.
        assertEquals(-10.0 + 10 * kotlin.math.log10(0.55), LoudnessMath.albumLufs(listOf(-10.0 to 1.0, -20.0 to 1.0))!!, 1e-9)
        assertNull(LoudnessMath.albumLufs(emptyList()))
    }

    @Test fun `fallback array for the engine`() {
        val track = e(-12.0, -0.5, 200.0)
        val out = LoudnessMath.fallback(track, listOf(track, e(-14.0, -2.0, 200.0)))
        assertEquals(-6.0, out[0], 1e-9)
        assertEquals(LoudnessMath.peakLinear(-0.5), out[1], 1e-12)
        assertTrue(out[2] < -4.0 && out[2] > -6.0)
        assertEquals(LoudnessMath.peakLinear(-0.5), out[3], 1e-12)
        val none = LoudnessMath.fallback(null, emptyList())
        assertTrue(none.all { it.isNaN() })
        // A tagged file (seconds -1, no measurement) gives nothing.
        assertTrue(LoudnessMath.fallback(e(null, null, -1.0), emptyList()).all { it.isNaN() })
    }
}
