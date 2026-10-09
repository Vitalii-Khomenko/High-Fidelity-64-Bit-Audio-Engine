package com.aiproject.musicplayer.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val shuffleEnabled: Boolean = false,
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["playlistId", "playOrder"], unique = true),
    ]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val playlistId: Int,
    val uriString: String,
    val title: String,
    val folder: String = "",
    val durationMs: Long = 0L,
    val playOrder: Int = 0,
    @ColumnInfo(defaultValue = "") val artist: String = "",
    @ColumnInfo(defaultValue = "") val album: String = "",
    @ColumnInfo(defaultValue = "0") val trackNumber: Int = 0,
    @ColumnInfo(defaultValue = "0") val discNumber: Int = 0,
)
