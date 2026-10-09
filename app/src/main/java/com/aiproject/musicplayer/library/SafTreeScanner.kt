package com.aiproject.musicplayer.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.aiproject.musicplayer.playback.Track

data class BrowseLocation(val documentId: String, val label: String)

data class BrowseEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val track: Track? = null,
)

/** Storage Access Framework folder access (persisted tree URIs). */
object SafTreeScanner {
    private const val MAX_DEPTH = 12

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    fun folderNameFromTreeUri(treeUri: Uri): String = try {
        val decoded = Uri.decode(DocumentsContract.getTreeDocumentId(treeUri))
        decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { decoded }
    } catch (_: Exception) {
        ""
    }

    fun rootLocation(treeUri: Uri, label: String): BrowseLocation =
        BrowseLocation(DocumentsContract.getTreeDocumentId(treeUri), label)

    /** All supported tracks below [docId], depth-first, in provider order. */
    fun scanTracks(
        resolver: ContentResolver,
        treeUri: Uri,
        docId: String = DocumentsContract.getTreeDocumentId(treeUri),
        folderLabel: String = folderNameFromTreeUri(treeUri),
    ): List<Track> {
        val result = mutableListOf<Track>()
        scanInto(resolver, treeUri, docId, folderLabel, 0, result)
        return result
    }

    private fun scanInto(
        resolver: ContentResolver,
        treeUri: Uri,
        docId: String,
        folderLabel: String,
        depth: Int,
        out: MutableList<Track>,
    ) {
        if (depth > MAX_DEPTH) return
        val children = try {
            listChildren(resolver, treeUri, docId)
        } catch (_: Exception) {
            return
        }
        for ((childId, name, mime) in children) {
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                scanInto(resolver, treeUri, childId, name, depth + 1, out)
            } else if (SupportedFormats.isSupported(name, mime)) {
                out += trackFor(treeUri, childId, name, folderLabel)
            }
        }
    }

    /** One level of a folder for the browser: folders first, then tracks. Throws on access errors. */
    fun listFolder(resolver: ContentResolver, treeUri: Uri, docId: String, folderLabel: String): List<BrowseEntry> {
        val entries = listChildren(resolver, treeUri, docId).mapNotNull { (childId, name, mime) ->
            when {
                mime == DocumentsContract.Document.MIME_TYPE_DIR -> BrowseEntry(childId, name, isDirectory = true)
                SupportedFormats.isSupported(name, mime) ->
                    BrowseEntry(childId, name, isDirectory = false, track = trackFor(treeUri, childId, name, folderLabel))
                else -> null
            }
        }
        return entries.sortedWith(compareBy<BrowseEntry> { !it.isDirectory }.thenBy { PlaylistOrdering.naturalSortKey(it.name) })
    }

    private fun trackFor(treeUri: Uri, docId: String, name: String, folder: String) = Track(
        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
        title = SupportedFormats.displayTitle(name),
        folder = folder,
    )

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, docId: String): List<Triple<String, String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<Triple<String, String, String>>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                result += Triple(id, cursor.getString(nameCol).orEmpty(), cursor.getString(mimeCol).orEmpty())
            }
        }
        return result
    }
}
