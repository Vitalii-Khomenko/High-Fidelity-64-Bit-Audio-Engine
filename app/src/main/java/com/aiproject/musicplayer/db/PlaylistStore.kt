package com.aiproject.musicplayer.db

import androidx.room.withTransaction
import com.aiproject.musicplayer.playback.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Saved playlists (Room). */
class PlaylistStore(private val db: MusicDatabase) {
    fun playlists(): Flow<List<PlaylistEntity>> = db.playlistDao().getAllPlaylists()

    suspend fun save(name: String, tracks: List<Track>, shuffle: Boolean): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val id = db.playlistDao().insertPlaylist(PlaylistEntity(name = name, shuffleEnabled = shuffle))
            tracks.forEachIndexed { order, t ->
                db.trackDao().insertTrack(
                    PlaylistTrackEntity(
                        playlistId = id.toInt(), uriString = t.uri, title = t.title,
                        folder = t.folder, durationMs = t.durationMs, playOrder = order,
                    ),
                )
            }
            id
        }
    }

    suspend fun tracks(playlistId: Int): List<Track> = withContext(Dispatchers.IO) {
        db.trackDao().getTracksForPlaylist(playlistId).first().map {
            Track(it.uriString, it.title, it.folder, it.durationMs)
        }
    }

    suspend fun rename(playlistId: Int, name: String) = withContext(Dispatchers.IO) {
        db.playlistDao().renamePlaylist(playlistId, name)
    }

    suspend fun delete(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        db.playlistDao().deletePlaylist(playlist)
    }
}
