package com.aiproject.musicplayer.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.aiproject.musicplayer.db.MusicDatabase
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves cover pictures to Android Auto and other media browsers, which load
 * browse-item icons by URI. Read-only; only tracks of the library index or the
 * current queue (registered by the service) are served, so another app cannot
 * make us read arbitrary URIs.
 *
 *   content://<package>.covers/cover?track=<track uri>
 */
class CoverProvider : ContentProvider() {

    companion object {
        private val allowed = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

        fun uri(context: Context, trackUri: String): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.covers")
            .appendPath("cover")
            .appendQueryParameter("track", trackUri)
            .build()

        /** Lets the provider serve a track that is not in the library index (e.g. the playing one). */
        fun allow(trackUri: String) {
            synchronized(allowed) {
                allowed += trackUri
                while (allowed.size > 64) allowed.remove(allowed.first())
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val context = context ?: throw FileNotFoundException()
        val track = uri.getQueryParameter("track") ?: throw FileNotFoundException()
        val known = track in allowed || runBlocking { MusicDatabase.getDatabase(context).libraryDao().byUri(track) } != null
        if (!known || !CoverArt.isLocal(track)) throw FileNotFoundException()
        val file = runBlocking { CoverArt.cachedFile(context, track) } ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
