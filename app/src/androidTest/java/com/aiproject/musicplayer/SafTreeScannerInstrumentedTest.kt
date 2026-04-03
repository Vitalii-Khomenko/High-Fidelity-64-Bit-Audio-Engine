package com.aiproject.musicplayer

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafTreeScannerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        TestDocumentsProvider.AUTHORITY,
        TestDocumentsProvider.ROOT_ID,
    )

    @Test
    fun recursiveScanIncludesNestedAudioAndDsdExtensions() {
        val tracks = SafTreeScanner.loadTracksFromTree(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            folderLabel = "Library",
        )

        assertEquals(3, tracks.size)
        assertEquals(setOf("Track01.flac", "Track02.dsf", "RootSong.dff"), tracks.map { it.name }.toSet())
        assertTrue(tracks.any { it.name == "Track01.flac" && it.folder == "Album" })
        assertTrue(tracks.any { it.name == "Track02.dsf" && it.folder == "Album" })
        assertTrue(tracks.any { it.name == "RootSong.dff" && it.folder == "Library" })
    }

    @Test
    fun listChildrenKeepsDirectoriesBeforeTracks() {
        val children = SafTreeScanner.listLibraryFolderChildren(
            contentResolver = context.contentResolver,
            treeUri = treeUri,
            docId = TestDocumentsProvider.ROOT_ID,
            folderLabel = "Library",
        )

        assertEquals(listOf("Album", "RootSong.dff"), children.map { it.name })
        assertTrue(children.first().isDirectory)
        assertTrue(children.last().track != null)
    }
}
