package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTagsTest {
    @Test fun `native record parses into fields`() {
        val record = listOf("Title 🎵", "Artist", "Album", "AA", "Rock", "2001", "la", "3", "12", "1", "2", "1", "-6.50", "")
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        val t = AudioTags.parse(record)
        assertEquals("Title 🎵", t.title)
        assertEquals(3, t.track)
        assertEquals(12, t.trackTotal)
        assertEquals(2, t.discTotal)
        assertTrue(t.hasPicture)
        assertEquals(-6.5f, t.trackGainDb)
        assertNull(t.albumGainDb)
    }

    @Test fun `short or empty records do not crash`() {
        val t = AudioTags.parse(ByteArray(0))
        assertEquals("", t.title)
        assertEquals(0, t.track)
    }
}
