package com.aiproject.musicplayer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

object SafTreeScanner {

    fun folderNameFromTreeUri(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val decoded = Uri.decode(docId)
            decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { decoded }
        } catch (_: Exception) {
            ""
        }
    }

    fun loadTracksFromTree(
        contentResolver: ContentResolver,
        treeUri: Uri,
        docId: String = DocumentsContract.getTreeDocumentId(treeUri),
        folderLabel: String = folderNameFromTreeUri(treeUri),
        depth: Int = 0,
    ): List<AudioTrack> {
        if (depth > 10) return emptyList()

        val result = mutableListOf<AudioTrack>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> {
                            result += loadTracksFromTree(contentResolver, treeUri, childId, name, depth + 1)
                        }

                        isAudioEntry(name, mime) -> {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            result += AudioTrack(fileUri, name, folderLabel, 0L)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun listLibraryFolderChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
        docId: String,
        folderLabel: String,
    ): List<LibraryBrowserEntry> {
        val result = mutableListOf<LibraryBrowserEntry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> {
                            result += LibraryBrowserEntry(
                                documentId = childId,
                                name = name,
                                isDirectory = true,
                            )
                        }

                        isAudioEntry(name, mime) -> {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            result += LibraryBrowserEntry(
                                documentId = childId,
                                name = name,
                                isDirectory = false,
                                track = AudioTrack(fileUri, name, folderLabel, 0L),
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }

        return result.sortedWith(
            compareBy<LibraryBrowserEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun isAudioEntry(name: String, mime: String): Boolean {
        return mime.startsWith("audio/") ||
            name.endsWith(".dsf", ignoreCase = true) ||
            name.endsWith(".dff", ignoreCase = true)
    }
}
