package com.aiproject.musicplayer.library

import com.aiproject.musicplayer.playback.PlayableUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueParserTest {
    private val sheet = """
        REM GENRE Rock
        REM DATE 1988
        PERFORMER "Кино"
        TITLE "Группа крови"
        FILE "Kino - Gruppa krovi.wav" WAVE
          TRACK 01 AUDIO
            TITLE "Группа крови"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Закрой за мной дверь"
            PERFORMER "Виктор Цой"
            INDEX 00 04:44:50
            INDEX 01 04:46:00
          TRACK 03 AUDIO
            INDEX 01 08:59:74
    """.trimIndent()

    @Test fun `tracks run from index 01 to the next index 01 of the same file`() {
        val cue = CueParser.parse(sheet)!!
        assertEquals("Группа крови", cue.title)
        assertEquals("Кино", cue.performer)
        assertEquals("Rock", cue.genre)
        assertEquals("1988", cue.date)
        assertEquals(3, cue.tracks.size)
        val (a, b, c) = cue.tracks
        assertEquals(0L, a.startUs)
        assertEquals(b.startUs, a.endUs)               // the pregap stays with track 1: no gap
        assertEquals(286_000_000L, b.startUs)
        assertEquals("Виктор Цой", b.performer)
        assertEquals("Кино", a.performer)
        assertEquals("Track 03", c.title)
        assertEquals(0L, c.endUs)                      // last track: to the end of the file
        assertEquals(setOf("Kino - Gruppa krovi.wav"), cue.fileNames)
    }

    @Test fun `cue frames convert to microseconds exactly enough for every sample rate`() {
        assertEquals(13_333L, CueParser.parseTime("00:00:01"))        // 1/75 s, rounded
        assertEquals(539_986_667L, CueParser.parseTime("08:59:74"))
        assertNull(CueParser.parseTime("00:61:00"))
        assertNull(CueParser.parseTime("00:00:75"))
        // 44.1 kHz: 1 CD frame = 588 samples exactly.
        assertEquals(588L, Math.round(CueParser.parseTime("00:00:01")!! * 44_100 / 1e6))
    }

    @Test fun `one file per track and unquoted names`() {
        val cue = CueParser.parse(
            """
            FILE 01 Intro.flac WAVE
            TRACK 1 AUDIO
            TITLE Intro
            INDEX 01 00:00:00
            FILE "02 Song.flac" WAVE
            TRACK 2 AUDIO
            INDEX 01 00:00:00
            """.trimIndent(),
        )!!
        assertEquals(listOf("01 Intro.flac", "02 Song.flac"), cue.tracks.map { it.fileName })
        assertEquals("Intro", cue.tracks[0].title)
        assertTrue(cue.tracks.all { it.endUs == 0L })
    }

    @Test fun `data tracks and sheets without audio are ignored`() {
        assertNull(CueParser.parse("FILE \"x.bin\" BINARY\nTRACK 01 MODE1/2352\nINDEX 01 00:00:00"))
        assertNull(CueParser.parse("garbage"))
    }

    @Test fun `legacy code pages are detected`() {
        val utf8 = "TITLE \"Ёлка\"".toByteArray(Charsets.UTF_8)
        assertEquals("TITLE \"Ёлка\"", CueParser.decode(utf8))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
        assertEquals("TITLE \"Ёлка\"", CueParser.decode(bom))
        val cp1251 = "TITLE \"Группа крови\"".toByteArray(charset("windows-1251"))
        assertEquals("TITLE \"Группа крови\"", CueParser.decode(cp1251))
        val cp1252 = "TITLE \"Café Ünïcode\"".toByteArray(charset("windows-1252"))
        assertEquals("TITLE \"Café Ünïcode\"", CueParser.decode(cp1252))
    }

    @Test fun `range uris round-trip and keep plain uris intact`() {
        val uri = PlayableUri.withRange("content://a/tree/x/document/y.flac", 1_000_000L, 2_500_000L)
        val parts = PlayableUri.split(uri)
        assertEquals("content://a/tree/x/document/y.flac", parts.fileUri)
        assertEquals(1_000_000L, parts.startUs)
        assertEquals(2_500_000L, parts.endUs)
        assertEquals(1_500L, parts.durationMs)
        assertTrue(parts.isRange)
        val plain = PlayableUri.split("content://a/b%23c")
        assertEquals("content://a/b%23c", plain.fileUri)
        assertEquals(false, plain.isRange)
        assertEquals(0L, PlayableUri.split(PlayableUri.withRange("f", 5L, 0L)).durationMs)
    }
}
