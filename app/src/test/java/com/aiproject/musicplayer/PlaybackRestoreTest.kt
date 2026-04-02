package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRestoreTest {

    @Test fun `BUG duplicate titles restore by URI picks exact track`() {
        val uris = listOf(
            "content://library/track/1",
            "content://library/track/2",
            "content://library/track/3"
        )

        assertEquals(1, PlaybackRestore.findTrackIndexByUri(uris, "content://library/track/2"))
    }

    @Test fun `missing URI returns minus one`() {
        val uris = listOf("a", "b")

        assertEquals(-1, PlaybackRestore.findTrackIndexByUri(uris, "c"))
    }

    @Test fun `blank URI returns minus one`() {
        val uris = listOf("a", "b")

        assertEquals(-1, PlaybackRestore.findTrackIndexByUri(uris, ""))
    }
}