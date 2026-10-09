package com.aiproject.musicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    @Test fun `every id survives a round trip, including awkward characters`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic|x"
        val ids = listOf(
            MediaId.Root, MediaId.Empty, MediaId.Queue, MediaId.Playlists, MediaId.Folders,
            MediaId.QueueTrack(42, "content://media/external/audio/media/7?x=1|2"),
            MediaId.Playlist(3),
            MediaId.PlaylistTrack(3, 17),
            MediaId.Folder(tree, "primary:Music/Альбом | 2", "Альбом | 2"),
            MediaId.FolderTrack(tree, "primary:Music", "Music", 5),
        )
        for (id in ids) assertEquals(id, MediaId.parse(id.encode()))
    }

    @Test fun `foreign or damaged ids are rejected`() {
        assertNull(MediaId.parse(null))
        assertNull(MediaId.parse("something"))
        assertNull(MediaId.parse("qt|bm90LWEtbnVtYmVy|eA"))   // "not-a-number"
        assertNull(MediaId.parse("pl"))                        // missing field
        assertNull(MediaId.parse("fo|***|***|***"))           // not Base64
    }

    @Test fun `voice search prefers exact, then prefix, then substring matches`() {
        val titles = listOf("Time (Live)", "Time", "Us and Them", "Money Time")
        assertEquals(1, MediaSearch.bestMatch("time", titles))
        assertEquals(2, MediaSearch.bestMatch("us and", titles))
        assertEquals(3, MediaSearch.bestMatch("money", titles))
        assertEquals(-1, MediaSearch.bestMatch("eclipse", titles))
        assertEquals(-1, MediaSearch.bestMatch("  ", titles))
        assertEquals(0, MediaSearch.bestMatch("ВРЕМЯ!", listOf("Время")))
    }

    @Test fun `long queues are windowed around the current track`() {
        assertEquals(0 to 10, queueWindow(10, 5, max = 300))
        assertEquals(0 to 300, queueWindow(1000, 50, max = 300))
        assertEquals(400 to 700, queueWindow(1000, 500, max = 300))
        assertEquals(700 to 1000, queueWindow(1000, 990, max = 300))
    }
}
