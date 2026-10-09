package com.aiproject.musicplayer.library

import com.aiproject.musicplayer.playback.SortMode
import com.aiproject.musicplayer.playback.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedFormatsTest {
    @Test fun `formats the engine decodes are offered`() {
        listOf(
            "a.flac", "B.FLAC", "c.wav", "d.aiff", "e.aif", "f.mp3", "g.dsf", "h.dff", "i.w64", "j.rf64",
            "k.ogg", "l.opus", "m.wv", "n.ape", "o.tta", "p.m4a", "q.m4b", "r.aac", "s.mka",
        ).forEach { assertTrue(it, SupportedFormats.isSupported(it, null)) }
        listOf("cover.jpg", "notes.txt", "album.cue", "a.wvc").forEach {
            assertFalse(it, SupportedFormats.isSupported(it, "audio/mpeg"))
        }
    }

    @Test fun `mime decides only when the name has no extension`() {
        assertTrue(SupportedFormats.isSupported("track", "audio/flac"))
        assertTrue(SupportedFormats.isSupported("track", "audio/ogg"))
        assertTrue(SupportedFormats.isSupported("track", "audio/mp4"))
        assertFalse(SupportedFormats.isSupported("track", "image/jpeg"))
    }

    @Test fun `cue sheets are recognised`() {
        assertTrue(SupportedFormats.isCue("Album.CUE"))
        assertFalse(SupportedFormats.isCue("album.cue.txt"))
    }

    @Test fun `titles drop the extension`() {
        assertEquals("01 - Intro", SupportedFormats.displayTitle("01 - Intro.flac"))
        assertEquals("no extension", SupportedFormats.displayTitle("no extension"))
    }

    @Test fun `sort comparator orders chapters by number`() {
        val tracks = listOf("Chapter 10", "Chapter 2", "Chapter 1").map { Track(uri = it, title = it) }
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), PlaylistOrdering.sortTracks(tracks, SortMode.NUMBER).map { it.title })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), PlaylistOrdering.sortTracks(tracks, SortMode.NAME).map { it.title })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), PlaylistOrdering.sortTracks(tracks, SortMode.ALBUM).map { it.title })
    }

    @Test fun `album order uses disc and track numbers, then album`() {
        val tracks = listOf(
            Track("u1", "Zebra", album = "B", trackNumber = 1),
            Track("u2", "Intro", album = "A", discNumber = 2, trackNumber = 1),
            Track("u3", "Outro", album = "A", discNumber = 1, trackNumber = 9),
            Track("u4", "Middle", album = "A", discNumber = 1, trackNumber = 2),
        )
        assertEquals(listOf("u4", "u3", "u2", "u1"), PlaylistOrdering.sortTracks(tracks, SortMode.ALBUM).map { it.uri })
    }
}
