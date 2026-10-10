package com.aiproject.musicplayer.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aiproject.musicplayer.AudioTags
import com.aiproject.musicplayer.NativeTags
import com.aiproject.musicplayer.db.AlbumRow
import com.aiproject.musicplayer.db.ArtistRow
import com.aiproject.musicplayer.db.LibraryTrackEntity
import com.aiproject.musicplayer.db.MusicDatabase
import com.aiproject.musicplayer.playback.PlayableUri
import com.aiproject.musicplayer.playback.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/** Grouping keys and display values of the library (pure Kotlin). */
object LibraryKeys {
    fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * Tracks with an album artist group by (album artist, album) across
     * folders; without one, by (folder, album), so a compilation in one folder
     * stays one album.
     */
    fun albumKey(albumTitle: String, albumArtist: String, folderUri: String): String =
        if (albumArtist.isNotBlank()) "a|${normalize(albumArtist)}|${normalize(albumTitle)}"
        else "f|$folderUri|${normalize(albumTitle)}"

    fun searchText(vararg parts: String): String = parts.joinToString("\n") { it.lowercase(Locale.ROOT) }

    /** "%term%" for LIKE, with LIKE wildcards in the user's text taken literally. */
    fun likePattern(query: String): String =
        "%" + query.trim().lowercase(Locale.ROOT).replace("\\", "").replace("%", "").replace("_", " ") + "%"
}

/** What the indexer knows about one file before it is turned into rows. */
internal data class IndexedFile(val tags: AudioTags, val durationMs: Long)

/**
 * Library index over the saved folders: tags, numbering, durations and the
 * cover source of every file (and every CUE track). Incremental: a file whose
 * size and modification time are unchanged is not read again.
 */
class LibraryIndex(context: Context) {
    private val app = context.applicationContext
    private val dao = MusicDatabase.getDatabase(app).libraryDao()

    data class Progress(val folder: String, val tracks: Int)

    fun albums(): Flow<List<AlbumRow>> = dao.albums()
    fun artists(): Flow<List<ArtistRow>> = dao.artists()
    fun count(): Flow<Int> = dao.count()

    /** An indexed folder with the files directly in it. */
    data class Folder(
        val uri: String,
        val name: String,
        /** The folders above it ("Music/Mixes"), "" when the provider has no paths. */
        val parent: String,
        val tracks: Int,
        val durationMs: Long,
        val artists: Int,
        val coverUri: String,
    )

    /** Every folder holding tracks, in path order (natural: "2" before "10"). */
    fun folders(): Flow<List<Folder>> = dao.folders().map { rows ->
        rows.map { Folder(it.folderUri, it.name, FolderPaths.parent(it.folderUri), it.tracks, it.durationMs, it.artists, it.coverUri) }
            .sortedWith(compareBy { PlaylistOrdering.naturalSortKey(FolderPaths.path(it.uri).ifEmpty { it.name }) })
    }

    /** The folder's tracks in file-name order, as a file manager shows them (CUE tracks by number). */
    suspend fun folderTracks(folderUri: String): List<Track> = dao.folderTracks(folderUri)
        .sortedWith(
            compareBy<LibraryTrackEntity> { PlaylistOrdering.naturalSortKey(FolderPaths.fileName(it.fileUri).ifEmpty { it.title }) }
                .thenBy { it.discNumber }.thenBy { it.trackNumber },
        )
        .map(::toTrack)

    suspend fun albumTracks(albumKey: String): List<Track> = dao.albumTracks(albumKey).map(::toTrack)
    suspend fun artistTracks(artistKey: String): List<Track> = dao.artistTracks(artistKey).map(::toTrack)
    suspend fun artistAlbums(artistKey: String): List<AlbumRow> = dao.artistAlbums(artistKey)
    suspend fun search(query: String, limit: Int = 200): List<Track> =
        if (query.isBlank()) emptyList() else dao.search(LibraryKeys.likePattern(query), limit).map(::toTrack)

    suspend fun entry(uri: String): LibraryTrackEntity? = dao.byUri(uri)

    /** Re-indexes every saved folder and drops what belongs to removed folders. */
    suspend fun update(sources: List<LibraryFolderEntry>, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        dao.deleteOtherSources(sources.map { it.uriString }.ifEmpty { listOf("") })
        for (source in sources) updateSource(source, onProgress)
    }

    suspend fun remove(source: LibraryFolderEntry) = withContext(Dispatchers.IO) { dao.deleteSource(source.uriString) }

    suspend fun updateSource(source: LibraryFolderEntry, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val tree = Uri.parse(source.uriString)
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return@withContext
        }
        val previous = dao.stamps(source.uriString).groupBy { it.fileUri }
        val seen = HashSet<String>()
        val batch = ArrayList<LibraryTrackEntity>()
        var count = 0
        SafTreeScanner.walk(app.contentResolver, tree, rootId, source.label, visit = { level ->
            val byFile = level.entries.mapNotNull { e -> e.track?.let { e to it } }
                .groupBy { PlayableUri.split(it.second.uri).fileUri }
            for ((fileUri, items) in byFile) {
                val stamp = level.stamps[fileUri] ?: FileStamp(0L, 0L)
                items.forEach { seen += it.second.uri }
                count += items.size
                val old = previous[fileUri]
                val unchanged = old != null && stamp.modified != 0L &&
                    old.all { it.fileSize == stamp.size && it.modified == stamp.modified } &&
                    old.map { it.uri }.toSet() == items.map { it.second.uri }.toSet()
                if (unchanged) continue
                val file = read(fileUri)
                for ((entry, track) in items) batch += entity(source, level, entry, track, file, stamp)
                if (batch.size >= 100) {
                    dao.upsert(batch.toList())
                    batch.clear()
                }
            }
            onProgress(Progress(source.label, count))
        })
        if (batch.isNotEmpty()) dao.upsert(batch)
        // Only reached when the walk was not cancelled: now it is safe to drop what is gone.
        val gone = previous.values.flatten().map { it.uri }.filter { it !in seen }
        gone.chunked(500).forEach { dao.deleteUris(it) }
    }

    private fun read(fileUri: String): IndexedFile = try {
        app.contentResolver.openFileDescriptor(Uri.parse(fileUri), "r")?.use { pfd ->
            IndexedFile(NativeTags.tags(pfd.fd) ?: AudioTags(), NativeTags.durationMs(pfd.fd))
        } ?: IndexedFile(AudioTags(), 0L)
    } catch (_: Exception) {
        IndexedFile(AudioTags(), 0L)
    }

    private fun entity(
        source: LibraryFolderEntry,
        level: FolderLevel,
        entry: BrowseEntry,
        track: Track,
        file: IndexedFile,
        stamp: FileStamp,
    ): LibraryTrackEntity {
        val tags = file.tags
        val parts = PlayableUri.split(track.uri)
        val cue = entry.cue
        val title = if (cue != null) track.title else tags.title.ifBlank { track.title }
        val artist = if (cue != null) track.artist.ifBlank { tags.artist } else tags.artist
        val album = if (cue != null) cue.title.ifBlank { tags.album } else tags.album
        val albumArtist = if (cue != null) cue.performer.ifBlank { tags.albumArtist } else tags.albumArtist
        val genre = cue?.genre?.ifBlank { null } ?: tags.genre
        val year = cue?.date?.ifBlank { null } ?: tags.year
        val duration = when {
            parts.durationMs > 0 -> parts.durationMs
            parts.isRange -> (file.durationMs - parts.startUs / 1000L).coerceAtLeast(0L)
            else -> file.durationMs
        }
        val albumTitle = album.ifBlank { level.label }
        val albumArtistName = albumArtist.ifBlank { artist }
        return LibraryTrackEntity(
            uri = track.uri,
            source = source.uriString,
            fileUri = parts.fileUri,
            folderUri = level.folderUri,
            folder = level.label,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = if (cue != null) track.trackNumber else tags.track,
            discNumber = tags.disc,
            durationMs = duration,
            hasPicture = tags.hasPicture,
            folderCover = level.coverUri,
            fileSize = stamp.size,
            modified = stamp.modified,
            albumKey = LibraryKeys.albumKey(albumTitle, albumArtist, level.folderUri),
            albumTitle = albumTitle,
            albumArtistName = albumArtistName,
            artistKey = LibraryKeys.normalize(albumArtistName),
            searchText = LibraryKeys.searchText(title, artist, albumTitle, albumArtist),
        )
    }

    companion object {
        fun toTrack(e: LibraryTrackEntity): Track = Track(
            uri = e.uri,
            title = e.title,
            folder = e.folder,
            durationMs = e.durationMs,
            artist = e.artist,
            album = e.albumTitle,
            trackNumber = e.trackNumber,
            discNumber = e.discNumber,
        )
    }
}
