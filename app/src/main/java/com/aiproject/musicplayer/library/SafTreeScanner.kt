package com.aiproject.musicplayer.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.aiproject.musicplayer.playback.PlayableUri
import com.aiproject.musicplayer.playback.Track

data class BrowseLocation(val documentId: String, val label: String)

data class BrowseEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val track: Track? = null,
    /** Ordering inside a folder: CUE tracks keep the sheet's order next to each other. */
    val sortKey: String = name,
)

/** Storage Access Framework folder access (persisted tree URIs). */
object SafTreeScanner {
    private const val MAX_DEPTH = 12

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    private data class Child(val id: String, val name: String, val mime: String) {
        val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun folderNameFromTreeUri(treeUri: Uri): String = try {
        val decoded = Uri.decode(DocumentsContract.getTreeDocumentId(treeUri))
        decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { decoded }
    } catch (_: Exception) {
        ""
    }

    fun rootLocation(treeUri: Uri, label: String): BrowseLocation =
        BrowseLocation(DocumentsContract.getTreeDocumentId(treeUri), label)

    /** All supported tracks below [docId], depth-first, in provider order; CUE sheets expanded. */
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
        out += levelEntries(resolver, treeUri, children, folderLabel).mapNotNull { it.track }
        for (child in children) {
            if (child.isDirectory) scanInto(resolver, treeUri, child.id, child.name, depth + 1, out)
        }
    }

    /** One level of a folder for the browser: folders first, then tracks. Throws on access errors. */
    fun listFolder(resolver: ContentResolver, treeUri: Uri, docId: String, folderLabel: String): List<BrowseEntry> {
        val children = listChildren(resolver, treeUri, docId)
        val folders = children.filter { it.isDirectory }.map { BrowseEntry(it.id, it.name, isDirectory = true) }
        val entries = folders + levelEntries(resolver, treeUri, children, folderLabel)
        return entries.sortedWith(compareBy<BrowseEntry> { !it.isDirectory }.thenBy { PlaylistOrdering.naturalSortKey(it.sortKey) })
    }

    /**
     * Playable entries of one folder level. A CUE sheet whose audio files are
     * all present replaces those files with its tracks; otherwise the files
     * are listed as they are.
     */
    private fun levelEntries(resolver: ContentResolver, treeUri: Uri, children: List<Child>, folderLabel: String): List<BrowseEntry> {
        val audio = children.filter { !it.isDirectory && SupportedFormats.isSupported(it.name, it.mime) }
        val result = mutableListOf<BrowseEntry>()
        val covered = HashSet<String>()
        for (cue in children.filter { !it.isDirectory && SupportedFormats.isCue(it.name) }) {
            val sheet = readCue(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, cue.id)) ?: continue
            val files = sheet.fileNames.associateWith { matchAudio(audio, it) }
            if (files.values.any { it == null }) continue
            sheet.tracks.forEachIndexed { index, t ->
                val file = files.getValue(t.fileName)!!
                covered += file.id
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.id).toString()
                val track = Track(
                    uri = PlayableUri.withRange(fileUri, t.startUs, t.endUs),
                    title = t.title,
                    folder = folderLabel,
                    durationMs = if (t.endUs > t.startUs) (t.endUs - t.startUs) / 1000L else 0L,
                    artist = t.performer,
                    album = sheet.title,
                    trackNumber = t.number,
                )
                result += BrowseEntry("${cue.id}#${t.number}", t.title, isDirectory = false, track = track,
                    sortKey = "${cue.name} %04d".format(index + 1))
            }
        }
        for (file in audio) {
            if (file.id in covered) continue
            result += BrowseEntry(file.id, file.name, isDirectory = false, track = trackFor(treeUri, file.id, file.name, folderLabel))
        }
        return result
    }

    /** The audio file a CUE sheet names; rippers often say ".wav" for what is now ".flac" or ".ape". */
    private fun matchAudio(audio: List<Child>, name: String): Child? {
        val plain = name.substringAfterLast('/').substringAfterLast('\\')
        audio.firstOrNull { it.name.equals(plain, ignoreCase = true) }?.let { return it }
        val base = plain.substringBeforeLast('.', plain)
        return audio.firstOrNull { it.name.substringBeforeLast('.', it.name).equals(base, ignoreCase = true) }
    }

    private fun readCue(resolver: ContentResolver, uri: Uri): CueSheet? = try {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(CueParser.MAX_BYTES)
            CueParser.parse(CueParser.decode(bytes))
        }
    } catch (_: Exception) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun trackFor(treeUri: Uri, docId: String, name: String, folder: String) = Track(
        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
        title = SupportedFormats.displayTitle(name),
        folder = folder,
    )

    private fun listChildren(resolver: ContentResolver, treeUri: Uri, docId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val result = mutableListOf<Child>()
        resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                result += Child(id, cursor.getString(nameCol).orEmpty(), cursor.getString(mimeCol).orEmpty())
            }
        }
        return result
    }
}
