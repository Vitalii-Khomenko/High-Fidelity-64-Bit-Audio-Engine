package com.aiproject.musicplayer

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class TestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveRootProjection(projection))
        cursor.addRow(
            arrayOf(
                ROOT_ID,
                ROOT_ID,
                "Test Library",
                DocumentsContract.Root.FLAG_LOCAL_ONLY,
                "audio/*",
            )
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            addNodeRow(node)
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            parent.children.mapNotNull(nodes::get).forEach { node ->
                addNodeRow(node)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        if (node.isDirectory) throw FileNotFoundException("Directory cannot be opened: $documentId")
        val file = File(context!!.cacheDir, "${documentId.replace(':', '_')}.bin")
        if (!file.exists()) {
            file.writeText("test-audio")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String {
        return nodes[documentId]?.mimeType ?: throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = documentId
        while (true) {
            val node = nodes[current] ?: return false
            val parentId = node.parentId ?: return false
            if (parentId == parentDocumentId) return true
            current = parentId
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        throw FileNotFoundException("No thumbnails for test provider")
    }

    private fun MatrixCursor.addNodeRow(node: Node) {
        addRow(
            arrayOf(
                node.id,
                node.name,
                node.mimeType,
                if (node.isDirectory) {
                    DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED
                } else {
                    DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                },
            )
        )
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it.toString() }?.toTypedArray()
            ?: arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
            )
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it.toString() }?.toTypedArray()
            ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
    }

    private data class Node(
        val id: String,
        val name: String,
        val mimeType: String,
        val parentId: String? = null,
        val children: List<String> = emptyList(),
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    companion object {
        const val AUTHORITY = "com.aiproject.musicplayer.testdocuments"
        const val ROOT_ID = "root"

        private val nodes = listOf(
            Node(
                id = ROOT_ID,
                name = "Root",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                children = listOf("album", "root_track", "ignored"),
            ),
            Node(
                id = "album",
                name = "Album",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                parentId = ROOT_ID,
                children = listOf("track_flac", "track_dsf"),
            ),
            Node(
                id = "root_track",
                name = "RootSong.dff",
                mimeType = "application/octet-stream",
                parentId = ROOT_ID,
            ),
            Node(
                id = "ignored",
                name = "notes.txt",
                mimeType = "text/plain",
                parentId = ROOT_ID,
            ),
            Node(
                id = "track_flac",
                name = "Track01.flac",
                mimeType = "audio/flac",
                parentId = "album",
            ),
            Node(
                id = "track_dsf",
                name = "Track02.dsf",
                mimeType = "application/octet-stream",
                parentId = "album",
            ),
        ).associateBy { it.id }
    }
}
