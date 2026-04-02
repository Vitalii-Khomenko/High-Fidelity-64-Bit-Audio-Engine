package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFolderEntryTest {
    @Test
    fun `serialize and deserialize preserve library folders`() {
        val serialized = LibraryFolderEntry.serialize(
            listOf(
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                    label = "Music"
                ),
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
                    label = "Audiobooks"
                )
            )
        )

        assertEquals(
            listOf(
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                    label = "Music"
                ),
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
                    label = "Audiobooks"
                )
            ),
            LibraryFolderEntry.deserialize(serialized)
        )
    }

    @Test
    fun `legacy migration keeps distinct uri entries`() {
        val migrated = LibraryFolderEntry.fromLegacyUris(
            listOf(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"
            )
        ) { uriString ->
            if (uriString.contains("Music")) "Music" else "Audiobooks"
        }

        assertEquals(
            listOf(
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                    label = "Music"
                ),
                LibraryFolderEntry(
                    uriString = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
                    label = "Audiobooks"
                )
            ),
            migrated
        )
    }
}