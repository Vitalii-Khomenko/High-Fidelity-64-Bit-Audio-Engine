package com.aiproject.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPathsTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    @Test fun `path-style document ids give the path`() {
        val folder = "$tree/document/primary%3AMusic%2FMixes%2F2024%20Summer"
        assertEquals("Music/Mixes/2024 Summer", FolderPaths.path(folder))
        assertEquals("Music/Mixes", FolderPaths.parent(folder))
    }

    @Test fun `file names for ordering`() {
        assertEquals("02 Artist - Song.flac", FolderPaths.fileName("$tree/document/primary%3AMusic%2FMix%2F02%20Artist%20-%20Song.flac"))
        // A literal plus stays a plus.
        assertEquals("A+B.mp3", FolderPaths.fileName("$tree/document/primary%3AMusic%2FA%2BB.mp3"))
        assertEquals("C+D.mp3", FolderPaths.fileName("$tree/document/primary%3AMusic%2FC+D.mp3"))
    }

    @Test fun `SD cards and the storage root`() {
        assertEquals("Podcasts", FolderPaths.path("content://x/tree/1234-ABCD%3A/document/1234-ABCD%3APodcasts"))
        assertEquals("", FolderPaths.parent("content://x/tree/1234-ABCD%3A/document/1234-ABCD%3APodcasts"))
        assertEquals("", FolderPaths.path("$tree/document/primary%3A"))
    }

    @Test fun `opaque ids give nothing`() {
        assertEquals("", FolderPaths.path("content://com.android.providers.downloads.documents/document/msf%3A1234"))
        assertEquals("", FolderPaths.path("content://media/external/audio/media/77"))
        assertEquals("", FolderPaths.fileName("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3DXyZ"))
    }
}
