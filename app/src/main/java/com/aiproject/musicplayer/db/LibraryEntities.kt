package com.aiproject.musicplayer.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One playable item of the library index (a file, or one track of a CUE
 * sheet). Rebuilt incrementally by LibraryIndexer from the saved folders.
 */
@Entity(
    tableName = "library_tracks",
    indices = [Index("albumKey"), Index("artistKey"), Index("source"), Index("fileUri")],
)
data class LibraryTrackEntity(
    @PrimaryKey val uri: String,
    /** Tree URI of the saved folder the item was found in. */
    val source: String,
    /** The audio file (differs from [uri] for CUE tracks). */
    val fileUri: String,
    /** Parent folder document URI. */
    val folderUri: String,
    val folder: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    /** Embedded cover present. */
    val hasPicture: Boolean,
    /** Image file next to the track (cover.jpg, folder.jpg …), "" when none. */
    val folderCover: String,
    val fileSize: Long,
    val modified: Long,
    // Derived for grouping and searching.
    val albumKey: String,
    val albumTitle: String,
    val albumArtistName: String,
    val artistKey: String,
    /** Lower-case title / artist / album (SQLite LIKE only folds ASCII case). */
    val searchText: String,
)

data class AlbumRow(
    val albumKey: String,
    val albumTitle: String,
    val albumArtistName: String,
    val year: String?,
    val tracks: Int,
    val durationMs: Long,
    val artists: Int,
    /** A track whose cover represents the album. */
    val coverUri: String,
)

data class ArtistRow(
    val artistKey: String,
    val name: String,
    val albums: Int,
    val tracks: Int,
    val coverUri: String,
)

data class FileStampRow(val uri: String, val fileUri: String, val fileSize: Long, val modified: Long)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<LibraryTrackEntity>)

    @Query("DELETE FROM library_tracks WHERE uri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)

    @Query("DELETE FROM library_tracks WHERE source = :source")
    suspend fun deleteSource(source: String)

    @Query("DELETE FROM library_tracks WHERE source NOT IN (:sources)")
    suspend fun deleteOtherSources(sources: List<String>)

    @Query("SELECT uri, fileUri, fileSize, modified FROM library_tracks WHERE source = :source")
    suspend fun stamps(source: String): List<FileStampRow>

    @Query("SELECT * FROM library_tracks WHERE fileUri IN (:fileUris)")
    suspend fun byFiles(fileUris: List<String>): List<LibraryTrackEntity>

    @Query("SELECT * FROM library_tracks WHERE uri = :uri")
    suspend fun byUri(uri: String): LibraryTrackEntity?

    @Query("SELECT COUNT(*) FROM library_tracks")
    fun count(): Flow<Int>

    @Query(
        """
        SELECT albumKey, albumTitle, albumArtistName, MIN(NULLIF(year, '')) AS year, COUNT(*) AS tracks,
               SUM(durationMs) AS durationMs, COUNT(DISTINCT artist) AS artists,
               COALESCE(MIN(CASE WHEN hasPicture OR folderCover != '' THEN uri END), MIN(uri)) AS coverUri
        FROM library_tracks GROUP BY albumKey
        ORDER BY albumArtistName COLLATE NOCASE, year, albumTitle COLLATE NOCASE
        """,
    )
    fun albums(): Flow<List<AlbumRow>>

    @Query(
        """
        SELECT artistKey, albumArtistName AS name, COUNT(DISTINCT albumKey) AS albums, COUNT(*) AS tracks,
               COALESCE(MIN(CASE WHEN hasPicture OR folderCover != '' THEN uri END), MIN(uri)) AS coverUri
        FROM library_tracks GROUP BY artistKey ORDER BY name COLLATE NOCASE
        """,
    )
    fun artists(): Flow<List<ArtistRow>>

    @Query("SELECT * FROM library_tracks WHERE albumKey = :albumKey ORDER BY discNumber, trackNumber, title COLLATE NOCASE")
    suspend fun albumTracks(albumKey: String): List<LibraryTrackEntity>

    @Query(
        """
        SELECT * FROM library_tracks WHERE artistKey = :artistKey
        ORDER BY year, albumTitle COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE
        """,
    )
    suspend fun artistTracks(artistKey: String): List<LibraryTrackEntity>

    @Query("SELECT * FROM library_tracks WHERE searchText LIKE :pattern ORDER BY albumArtistName, albumTitle, discNumber, trackNumber LIMIT :limit")
    suspend fun search(pattern: String, limit: Int): List<LibraryTrackEntity>

    @Query(
        """
        SELECT albumKey, albumTitle, albumArtistName, MIN(NULLIF(year, '')) AS year, COUNT(*) AS tracks,
               SUM(durationMs) AS durationMs, COUNT(DISTINCT artist) AS artists,
               COALESCE(MIN(CASE WHEN hasPicture OR folderCover != '' THEN uri END), MIN(uri)) AS coverUri
        FROM library_tracks WHERE artistKey = :artistKey GROUP BY albumKey ORDER BY year, albumTitle COLLATE NOCASE
        """,
    )
    suspend fun artistAlbums(artistKey: String): List<AlbumRow>
}
