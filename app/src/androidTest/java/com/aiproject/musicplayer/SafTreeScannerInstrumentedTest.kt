package com.aiproject.musicplayer

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiproject.musicplayer.library.SafTreeScanner
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
        val tracks = SafTreeScanner.scanTracks(
            resolver = context.contentResolver,
            treeUri = treeUri,
            folderLabel = "Library",
        )

        assertEquals(3, tracks.size)
        assertEquals(setOf("Track01", "Track02", "RootSong"), tracks.map { it.title }.toSet())
        assertTrue(tracks.any { it.title == "Track01" && it.folder == "Album" })
        assertTrue(tracks.any { it.title == "Track02" && it.folder == "Album" })
        assertTrue(tracks.any { it.title == "RootSong" && it.folder == "Library" })
    }

    @Test
    fun listChildrenKeepsDirectoriesBeforeTracks() {
        val children = SafTreeScanner.listFolder(
            resolver = context.contentResolver,
            treeUri = treeUri,
            docId = TestDocumentsProvider.ROOT_ID,
            folderLabel = "Library",
        )

        assertEquals(listOf("Album", "RootSong.dff"), children.map { it.name })
        assertTrue(children.first().isDirectory)
        assertTrue(children.last().track != null)
    }
}
