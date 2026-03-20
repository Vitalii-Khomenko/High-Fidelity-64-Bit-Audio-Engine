package com.aiproject.musicplayer

import org.junit.Assert.*
import org.junit.Test

class VolumeRampTest {

    private val EPS = 1e-9

    // ── Fade-in ───────────────────────────────────────────────────────────────

    @Test fun `fadeIn ends exactly at targetVolume`() {
        val steps = VolumeRamp.fadeIn(15, 1.0)
        assertEquals(1.0, steps.last(), EPS)
    }

    @Test fun `fadeIn starts above zero`() {
        val steps = VolumeRamp.fadeIn(15, 1.0)
        assertTrue("First step must be > 0", steps.first() > 0.0)
    }

    @Test fun `fadeIn is strictly increasing`() {
        val steps = VolumeRamp.fadeIn(10, 1.0)
        for (i in 1 until steps.size) {
            assertTrue("Step $i must be > step ${i-1}", steps[i] > steps[i - 1])
        }
    }

    @Test fun `fadeIn respects targetVolume below 1`() {
        val steps = VolumeRamp.fadeIn(10, 0.8)
        assertEquals(0.8, steps.last(), EPS)
    }

    // ── Fade-out ──────────────────────────────────────────────────────────────

    @Test fun `fadeOut ends at exactly 0`() {
        val steps = VolumeRamp.fadeOut(10, 1.0)
        assertEquals(0.0, steps.last(), EPS)
    }

    @Test fun `fadeOut starts below currentVolume`() {
        val steps = VolumeRamp.fadeOut(10, 1.0)
        assertTrue("First step must be < 1.0", steps.first() < 1.0)
    }

    @Test fun `fadeOut is strictly decreasing`() {
        val steps = VolumeRamp.fadeOut(10, 1.0)
        for (i in 1 until steps.size) {
            assertTrue("Step $i must be < step ${i-1}", steps[i] < steps[i - 1])
        }
    }

    @Test fun `fadeOut from partial volume ends at 0`() {
        val steps = VolumeRamp.fadeOut(10, 0.6)
        assertEquals(0.0, steps.last(), EPS)
        assertTrue("Must start near 0.6", steps.first() < 0.6)
    }

    // ── Duck ──────────────────────────────────────────────────────────────────

    @Test fun `duck reduces to 20 percent of base`() {
        val level = VolumeRamp.duckLevel(1.0, 0.2)
        assertEquals(0.2, level, EPS)
    }

    @Test fun `duck scales with baseVolume`() {
        val level = VolumeRamp.duckLevel(0.8, 0.2)
        assertEquals(0.16, level, EPS)
    }

    @Test fun `duck clamps to 0-1 range`() {
        assertEquals(0.0, VolumeRamp.duckLevel(0.0), EPS)
        assertEquals(1.0, VolumeRamp.duckLevel(1.0, 1.5), EPS)
    }

    // ── Step count ────────────────────────────────────────────────────────────

    @Test fun `fadeIn produces exactly N steps`() {
        assertEquals(15, VolumeRamp.fadeIn(15, 1.0).size)
    }

    @Test fun `fadeOut produces exactly N steps`() {
        assertEquals(10, VolumeRamp.fadeOut(10, 1.0).size)
    }

    @Test fun `single step fadeIn equals target`() {
        val steps = VolumeRamp.fadeIn(1, 0.9)
        assertEquals(0.9, steps[0], EPS)
    }

    @Test fun `single step fadeOut equals 0`() {
        val steps = VolumeRamp.fadeOut(1, 0.9)
        assertEquals(0.0, steps[0], EPS)
    }

    // ── Crossfade transition (documents PlaybackService.playTrack() contract) ─

    /**
     * Verifies the full crossfade sequence used when switching tracks:
     *   fadeOut(10 steps, currentVolume)  →  last value must be 0.0   (engine.pause() safe)
     *   fadeIn (15 steps, currentVolume)  →  last value must be target (full volume restored)
     *
     * If either assertion fails, the track-switch implementation has drifted
     * from the documented sequence and clicks may reappear.
     */
    @Test fun `crossfade sequence - fadeOut ends at 0 then fadeIn ends at target`() {
        val vol = 1.0
        val out = VolumeRamp.fadeOut(10, vol)
        val inn = VolumeRamp.fadeIn(15, vol)

        assertEquals("Fade-out last step must be 0.0 before pause()", 0.0, out.last(), EPS)
        assertEquals("Fade-in last step must restore full volume", vol, inn.last(), EPS)
    }

    @Test fun `crossfade - no volume value exceeds currentVolume in either ramp`() {
        val vol = 0.85
        val out = VolumeRamp.fadeOut(10, vol)
        val inn = VolumeRamp.fadeIn(15, vol)

        out.forEachIndexed { i, v ->
            assertTrue("fadeOut step $i ($v) must not exceed $vol", v <= vol + EPS)
        }
        inn.forEachIndexed { i, v ->
            assertTrue("fadeIn step $i ($v) must not exceed $vol", v <= vol + EPS)
        }
    }

    @Test fun `crossfade - gap between fadeOut end and fadeIn start is zero volume`() {
        // At the moment audioEngine.pause() is called, volume must be 0.
        // At the moment the new decoder starts, volume must also start from near-0.
        val out = VolumeRamp.fadeOut(10, 1.0)
        val inn = VolumeRamp.fadeIn(15, 1.0)
        assertEquals("Last fade-out value must be 0 (silence at pause)", 0.0, out.last(), EPS)
        assertTrue("First fade-in value must be near 0 (new track starts silent)",
            inn.first() < 0.1)
    }
}
