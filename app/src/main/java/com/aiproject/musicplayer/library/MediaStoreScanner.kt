package com.aiproject.musicplayer.library

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import com.aiproject.musicplayer.playback.Track

/** Device-wide audio from MediaStore, limited to formats the engine plays. */
object MediaStoreScanner {
    fun scan(resolver: ContentResolver): List<Track> {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            folderColumn,
        )
        val result = mutableListOf<Track>()
        try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val folderCol = cursor.getColumnIndexOrThrow(folderColumn)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!SupportedFormats.isSupported(name, cursor.getString(mimeCol))) continue
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(folderCol).orEmpty()
                    val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        path.trimEnd('/').substringAfterLast('/')
                    } else {
                        path.substringBeforeLast('/').substringAfterLast('/')
                    }
                    result += Track(
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        title = SupportedFormats.displayTitle(name),
                        folder = folder,
                        durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission not granted: the UI explains how to add folders instead.
        }
        return result
    }
}
