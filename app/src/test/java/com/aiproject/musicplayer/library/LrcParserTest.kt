package com.aiproject.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test fun `timed lines, repeated tags, offset and word timings`() {
        val lrc = """
            [ar:Кино]
            [ti:Группа крови]
            [offset:+500]
            [00:12.30]Тёплое место, но улицы ждут
            [00:20.5][01:40.25]Отпечатков наших ног
            [00:30.123]<00:30.123>Звёздная <00:31.00>пыль
        """.trimIndent()
        val l = LrcParser.parse(lrc) as Lyrics.Synced
        assertEquals(listOf(11_800L, 20_000L, 29_623L, 99_750L), l.lines.map { it.timeMs })
        assertEquals("Звёздная пыль", l.lines[2].text)
        assertEquals("Отпечатков наших ног", l.lines[3].text)
        assertEquals(-1, l.indexAt(5_000))
        assertEquals(0, l.indexAt(11_800))
        assertEquals(1, l.indexAt(25_000))
        assertEquals(3, l.indexAt(500_000))
    }

    @Test fun `plain text stays plain, empty is nothing`() {
        val p = LrcParser.parse("[ti:Song]\nLine one\nLine two") as Lyrics.Plain
        assertEquals("Line one\nLine two", p.text)
        assertNull(LrcParser.parse("   "))
        assertTrue(LrcParser.parse("[00:01.00]") is Lyrics.Synced)
    }
}
