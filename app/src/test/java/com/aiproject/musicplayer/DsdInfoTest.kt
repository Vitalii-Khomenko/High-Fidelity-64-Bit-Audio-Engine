package com.aiproject.musicplayer

import org.junit.Assert.*
import org.junit.Test

class DsdInfoTest {

    // ── Correct label for each standard DSD rate ──────────────────────────────

    @Test fun `DSD64 rate 2822400 maps to DSD64`() =
        assertEquals("DSD64", DsdInfo.label(2_822_400))

    @Test fun `DSD128 rate 5644800 maps to DSD128`() =
        assertEquals("DSD128", DsdInfo.label(5_644_800))

    @Test fun `DSD256 rate 11289600 maps to DSD256`() =
        assertEquals("DSD256", DsdInfo.label(11_289_600))

    @Test fun `DSD512 rate 22579200 maps to DSD512`() =
        assertEquals("DSD512", DsdInfo.label(22_579_200))

    // ── Boundary values ───────────────────────────────────────────────────────

    @Test fun `rate below DSD64 returns null`() =
        assertNull(DsdInfo.label(2_000_000))

    @Test fun `zero rate returns null`() =
        assertNull(DsdInfo.label(0))

    @Test fun `negative rate returns null`() =
        assertNull(DsdInfo.label(-1))

    @Test fun `rate above DSD512 still returns DSD512`() =
        assertEquals("DSD512", DsdInfo.label(45_158_400))

    // ── isDsd helper ──────────────────────────────────────────────────────────

    @Test fun `isDsd true for DSD64 rate`() =
        assertTrue(DsdInfo.isDsd(2_822_400))

    @Test fun `isDsd false for PCM rate 44100`() =
        assertFalse(DsdInfo.isDsd(44_100))

    @Test fun `isDsd false for PCM rate 192000`() =
        assertFalse(DsdInfo.isDsd(192_000))

    // ── BUG regression: old code had wrong thresholds ─────────────────────────

    @Test fun `BUG - DSD128 must NOT be labelled DSD256`() {
        val label = DsdInfo.label(5_644_800)
        assertNotEquals("DSD128 rate was incorrectly labelled DSD256 in old code", "DSD256", label)
        assertEquals("DSD128", label)
    }

    @Test fun `BUG - DSD256 must NOT be labelled DSD512`() {
        val label = DsdInfo.label(11_289_600)
        assertNotEquals("DSD256 rate was incorrectly labelled DSD512 in old code", "DSD512", label)
        assertEquals("DSD256", label)
    }
}
