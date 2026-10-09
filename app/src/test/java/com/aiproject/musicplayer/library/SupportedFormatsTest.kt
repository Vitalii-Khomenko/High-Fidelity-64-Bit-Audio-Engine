package com.aiproject.musicplayer.library

import com.aiproject.musicplayer.playback.SortMode
import com.aiproject.musicplayer.playback.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedFormatsTest {
    @Test fun `only formats the engine decodes are offered`() {
        listOf("a.flac", "B.FLAC", "c.wav", "d.aiff", "e.aif", "f.mp3", "g.dsf", "h.dff", "i.w64", "j.rf64").forEach {
            assertTrue(it, SupportedFormats.isSupported(it, null))
        }
        listOf("a.m4a", "b.ogg", "c.opus", "d.aac", "cover.jpg").forEach {
            assertFalse(it, SupportedFormats.isSupported(it, "audio/mpeg"))
        }
    }

    @Test fun `mime decides only when the name has no extension`() {
        assertTrue(SupportedFormats.isSupported("track", "audio/flac"))
        assertFalse(SupportedFormats.isSupported("track", "audio/ogg"))
    }

    @Test fun `titles drop the extension`() {
        assertEquals("01 - Intro", SupportedFormats.displayTitle("01 - Intro.flac"))
        assertEquals("no extension", SupportedFormats.displayTitle("no extension"))
    }

    @Test fun `sort comparator orders chapters by number`() {
        val tracks = listOf("Chapter 10", "Chapter 2", "Chapter 1").map { Track(uri = it, title = it) }
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), PlaylistOrdering.sortTracks(tracks, SortMode.NUMBER).map { it.title })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), PlaylistOrdering.sortTracks(tracks, SortMode.NAME).map { it.title })
    }
}
