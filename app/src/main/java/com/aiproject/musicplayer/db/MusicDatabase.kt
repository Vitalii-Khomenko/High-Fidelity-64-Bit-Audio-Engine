package com.aiproject.musicplayer.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistTrackEntity::class, PlaylistEntity::class, LibraryTrackEntity::class, LoudnessEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun libraryDao(): LibraryDao
    abstract fun loudnessDao(): LoudnessDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_tracks_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        playlistId INTEGER NOT NULL,
                        uriString TEXT NOT NULL,
                        title TEXT NOT NULL,
                        folder TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        playOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO playlist_tracks_new (id, playlistId, uriString, title, folder, durationMs, playOrder)
                    SELECT
                        t1.id,
                        t1.playlistId,
                        t1.uriString,
                        t1.title,
                        t1.folder,
                        t1.durationMs,
                        (
                            SELECT COUNT(*)
                            FROM playlist_tracks t2
                            WHERE t2.playlistId = t1.playlistId AND t2.id <= t1.id
                        ) - 1
                    FROM playlist_tracks t1
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE playlist_tracks")
                db.execSQL("ALTER TABLE playlist_tracks_new RENAME TO playlist_tracks")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlistId ON playlist_tracks(playlistId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_tracks_playlistId_playOrder ON playlist_tracks(playlistId, playOrder)"
                )
            }
        }

        /** 0.11: album fields for playlist tracks (CUE sheets, tags). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN album TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.12: the library index (artists, albums). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `library_tracks` (
                        `uri` TEXT NOT NULL, `source` TEXT NOT NULL, `fileUri` TEXT NOT NULL,
                        `folderUri` TEXT NOT NULL, `folder` TEXT NOT NULL, `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `albumArtist` TEXT NOT NULL,
                        `genre` TEXT NOT NULL, `year` TEXT NOT NULL, `trackNumber` INTEGER NOT NULL,
                        `discNumber` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `hasPicture` INTEGER NOT NULL,
                        `folderCover` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `modified` INTEGER NOT NULL,
                        `albumKey` TEXT NOT NULL, `albumTitle` TEXT NOT NULL, `albumArtistName` TEXT NOT NULL,
                        `artistKey` TEXT NOT NULL, `searchText` TEXT NOT NULL, PRIMARY KEY(`uri`)
                    )
                    """.trimIndent(),
                )
                for (column in listOf("albumKey", "artistKey", "source", "fileUri")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_tracks_$column` ON `library_tracks` (`$column`)")
                }
            }
        }

        /** 0.13: EBU R128 measurements of untagged files. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loudness` (`uri` TEXT NOT NULL, `lufs` REAL, `truePeakDb` REAL, " +
                        "`seconds` REAL NOT NULL, `analyzedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
                )
            }
        }

        @Volatile
        private var INSTANCE: MusicDatabase? = null

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "musicplayer_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
