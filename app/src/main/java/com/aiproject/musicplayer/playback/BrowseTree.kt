package com.aiproject.musicplayer.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.db.MusicDatabase
import com.aiproject.musicplayer.db.PlaylistStore
import com.aiproject.musicplayer.library.LibraryFolders
import com.aiproject.musicplayer.library.SafTreeScanner
import kotlinx.coroutines.flow.first

/**
 * Browse tree for Android Auto and other media browsers:
 *
 *   Queue      → tracks of the current queue
 *   Playlists  → saved playlists → their tracks
 *   Folders    → saved SAF folders → subfolders and tracks
 *
 * Loading functions do I/O and must run off the main thread.
 */
class BrowseTree(private val context: Context) {

    companion object {
        /** Car screens are small; long queues are shown around the current track. */
        const val MAX_QUEUE_ITEMS = 300

        // androidx.media.utils.MediaConstants values, spelled out for media 1.6.
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val STYLE_LIST = 1

        fun rootExtras(): Bundle = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
            putBoolean(SEARCH_SUPPORTED, true)
        }
    }

    private val playlists by lazy { PlaylistStore(MusicDatabase.getDatabase(context)) }

    fun rootChildren(): List<MediaItem> = listOf(
        browsable(MediaId.Queue, context.getString(R.string.queue), R.drawable.ic_auto_queue),
        browsable(MediaId.Playlists, context.getString(R.string.playlists), R.drawable.ic_auto_playlist),
        browsable(MediaId.Folders, context.getString(R.string.folders), R.drawable.ic_auto_folder),
    )

    fun queueChildren(tracks: List<Track>, currentIndex: Int): List<MediaItem> {
        val (from, to) = queueWindow(tracks.size, currentIndex)
        return (from until to).map { i -> playable(MediaId.QueueTrack(i, tracks[i].uri), tracks[i]) }
    }

    suspend fun playlistsChildren(): List<MediaItem> =
        playlists.playlists().first().map { browsable(MediaId.Playlist(it.id), it.name, R.drawable.ic_auto_playlist) }

    suspend fun playlistChildren(id: Int): List<MediaItem> =
        playlistTracks(id).mapIndexed { i, t -> playable(MediaId.PlaylistTrack(id, i), t) }

    suspend fun playlistTracks(id: Int): List<Track> = playlists.tracks(id)

    suspend fun playlistByName(query: String): Pair<Int, Boolean>? {
        val all = playlists.playlists().first()
        val index = MediaSearch.bestMatch(query, all.map { it.name })
        return all.getOrNull(index)?.let { it.id to it.shuffleEnabled }
    }

    suspend fun playlistShuffle(id: Int): Boolean =
        playlists.playlists().first().firstOrNull { it.id == id }?.shuffleEnabled ?: false

    fun foldersChildren(): List<MediaItem> {
        val accessible = LibraryFolders.accessibleUris(context)
        return LibraryFolders.load(context).filter { it.uriString in accessible }.map { entry ->
            val tree = Uri.parse(entry.uriString)
            val root = SafTreeScanner.rootLocation(tree, entry.label)
            browsable(MediaId.Folder(entry.uriString, root.documentId, entry.label), entry.label, R.drawable.ic_auto_folder)
        }
    }

    fun folderChildren(resolver: ContentResolver, folder: MediaId.Folder): List<MediaItem> {
        val entries = SafTreeScanner.listFolder(resolver, Uri.parse(folder.treeUri), folder.documentId, folder.label)
        val subfolders = entries.filter { it.isDirectory }.map {
            browsable(MediaId.Folder(folder.treeUri, it.documentId, it.name), it.name, R.drawable.ic_auto_folder)
        }
        val tracks = entries.mapNotNull { it.track }.mapIndexed { i, track ->
            playable(MediaId.FolderTrack(folder.treeUri, folder.documentId, folder.label, i), track)
        }
        return subfolders + tracks
    }

    /** Tracks of one folder level, in the order shown by [folderChildren]. */
    fun folderTracks(resolver: ContentResolver, treeUri: String, documentId: String, label: String): List<Track> =
        SafTreeScanner.listFolder(resolver, Uri.parse(treeUri), documentId, label).mapNotNull { it.track }

    fun matchingFolder(query: String): MediaId.Folder? {
        val accessible = LibraryFolders.accessibleUris(context)
        val folders = LibraryFolders.load(context).filter { it.uriString in accessible }
        val entry = folders.getOrNull(MediaSearch.bestMatch(query, folders.map { it.label })) ?: return null
        val root = SafTreeScanner.rootLocation(Uri.parse(entry.uriString), entry.label)
        return MediaId.Folder(entry.uriString, root.documentId, entry.label)
    }

    fun description(id: MediaId, track: Track): MediaDescriptionCompat =
        MediaDescriptionCompat.Builder()
            .setMediaId(id.encode())
            .setTitle(track.title)
            .setSubtitle(track.folder.ifBlank { null })
            .build()

    private fun playable(id: MediaId, track: Track) = MediaItem(description(id, track), MediaItem.FLAG_PLAYABLE)

    private fun browsable(id: MediaId, title: String, icon: Int) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id.encode())
            .setTitle(title)
            .setIconUri(resourceUri(icon))
            .build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun resourceUri(id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()
}

/** The slice [from, to) of a long queue to publish, centred on the current track. */
fun queueWindow(size: Int, currentIndex: Int, max: Int = BrowseTree.MAX_QUEUE_ITEMS): Pair<Int, Int> {
    if (size <= max) return 0 to size
    val start = (currentIndex - max / 3).coerceIn(0, size - max)
    return start to start + max
}
