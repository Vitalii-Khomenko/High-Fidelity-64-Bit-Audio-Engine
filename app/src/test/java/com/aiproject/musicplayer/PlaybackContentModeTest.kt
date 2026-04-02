package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackContentModeTest {

    @Test fun `fromId returns matching mode`() {
        assertEquals(PlaybackContentMode.MUSIC, PlaybackContentMode.fromId(0))
        assertEquals(PlaybackContentMode.BOOKS, PlaybackContentMode.fromId(1))
    }

    @Test fun `fromId falls back to books`() {
        assertEquals(PlaybackContentMode.BOOKS, PlaybackContentMode.fromId(99))
    }

    @Test fun `books mode remembers progress and played state`() {
        assertTrue(PlaybackContentMode.BOOKS.remembersTrackProgress)
        assertTrue(PlaybackContentMode.BOOKS.showsPlayedState)
        assertFalse(PlaybackContentMode.MUSIC.remembersTrackProgress)
        assertFalse(PlaybackContentMode.MUSIC.showsPlayedState)
    }
}