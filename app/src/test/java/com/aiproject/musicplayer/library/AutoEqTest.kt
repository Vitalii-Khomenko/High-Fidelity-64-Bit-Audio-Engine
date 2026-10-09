package com.aiproject.musicplayer.library

import com.aiproject.musicplayer.playback.EqFilter
import com.aiproject.musicplayer.playback.EqMode
import com.aiproject.musicplayer.playback.EqProfile
import com.aiproject.musicplayer.playback.EqSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqTest {
    private val hd600 = """
        Preamp: -6.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
        Filter 2: ON PK Fc 125 Hz Gain -2.7 dB Q 0.55
        Filter 3: ON PK Fc 8445 Hz Gain 3.3 dB Q 1.61
        Filter 4: OFF PK Fc 522 Hz Gain 0.7 dB Q 1.02
        Filter 5: ON HSC Fc 10000 Hz Gain -3.1 dB Q 0.70
        Filter 6: ON HP Fc 20 Hz
        Filter 7: ON XX Fc 300 Hz Gain 1 dB Q 1
    """.trimIndent()

    @Test fun `autoeq parametric profile parses, disabled and unknown filters skipped`() {
        val p = AutoEqParser.parse(hd600, "Sennheiser HD 600")!!
        assertEquals("Sennheiser HD 600", p.name)
        assertEquals(-6.3, p.preampDb, 1e-9)
        assertEquals(5, p.bands.size)
        assertEquals(EqFilter.LOW_SHELF, p.bands[0].filter)
        assertEquals(105.0, p.bands[0].frequency, 1e-9)
        assertEquals(6.5, p.bands[0].gainDb, 1e-9)
        assertEquals(EqFilter.HIGH_SHELF, p.bands[3].filter)
        assertEquals(EqFilter.HIGH_PASS, p.bands[4].filter)
        assertEquals(0.707, p.bands[4].q, 1e-9)
    }

    @Test fun `equalizer apo variants with kHz and commas`() {
        val p = AutoEqParser.parse("Filter: ON PK Fc 2,5 kHz Gain -1,5 dB Q 2", "x")!!
        assertEquals(2500.0, p.bands.single().frequency, 1e-9)
        assertEquals(-1.5, p.bands.single().gainDb, 1e-9)
        assertNull(AutoEqParser.parse("GraphicEQ: 20 -1.2; 21 -1.3", "x"))
    }

    @Test fun `profiles survive storage`() {
        val p = AutoEqParser.parse(hd600, "HD 600\nwith newline")!!
        val back = EqProfile.deserialize(p.serialize())!!
        assertEquals("HD 600 with newline", back.name)
        assertEquals(p.bands.size, back.bands.size)
        assertEquals(p.bands[2].frequency, back.bands[2].frequency, 1e-3)
        assertNull(EqProfile.deserialize("garbage"))
    }

    @Test fun `engine bands follow the mode`() {
        val p = AutoEqParser.parse(hd600, "HD 600")!!
        val graphic = EqSettings(enabled = true, bandGainsDb = listOf(1f, 2f, 3f, 4f, 5f), mode = EqMode.GRAPHIC, profile = p)
        assertEquals(5, graphic.engineBands().size)
        assertEquals(3.0, graphic.engineBands()[2].gainDb, 1e-9)
        assertEquals(0.0, graphic.enginePreampDb(), 0.0)
        val parametric = graphic.copy(mode = EqMode.PARAMETRIC)
        assertEquals(p.bands, parametric.engineBands())
        assertEquals(-6.3, parametric.enginePreampDb(), 1e-9)
    }

    @Test fun `catalog index, search and profile urls`() {
        val index = """
            # Index
            - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
            - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
        """.trimIndent()
        val entries = AutoEqCatalog.parseIndex(index)
        assertEquals(3, entries.size)
        assertEquals("1MORE Aero (ANC Off)", entries[0].name)
        assertEquals("HypetheSonics on GRAS RA0045", entries[0].source)
        assertEquals(listOf("Sennheiser HD 600"), AutoEqCatalog.search(entries, "hd 600").map { it.name })
        assertEquals(2, AutoEqCatalog.search(entries, "sennheiser").size)
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/over-ear/Sennheiser%20HD%20600/Sennheiser%20HD%20600%20ParametricEQ.txt",
            AutoEqCatalog.profileUrl(entries[1]),
        )
        assertEquals(
            "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)/1MORE%20Aero%20%28ANC%20Off%29%20ParametricEQ.txt",
            AutoEqCatalog.profileUrl(entries[0]),
        )
    }
}
