package com.aiproject.musicplayer.library

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Saved SAF folders (stored in the same preferences/format as 0.8.x). */
object LibraryFolders {
    private const val PREFS = "player_state"

    fun load(context: Context): List<LibraryFolderEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(LibraryFolderEntry.PREF_KEY)) {
            // 0.7.x stored a JSON array of tree URIs.
            val legacy = prefs.getString(LibraryFolderEntry.LEGACY_URI_KEY, null) ?: return emptyList()
            val uris = runCatching {
                val array = org.json.JSONArray(legacy)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())
            return LibraryFolderEntry.fromLegacyUris(uris) { SafTreeScanner.folderNameFromTreeUri(Uri.parse(it)) }
        }
        return LibraryFolderEntry.deserialize(prefs.getString(LibraryFolderEntry.PREF_KEY, null))
    }

    fun save(context: Context, entries: List<LibraryFolderEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LibraryFolderEntry.PREF_KEY, LibraryFolderEntry.serialize(entries))
            .remove(LibraryFolderEntry.LEGACY_URI_KEY)
            .apply()
    }

    /** Persists read access to a picked tree and returns its entry. */
    fun add(context: Context, treeUri: Uri): LibraryFolderEntry {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access; the folder still works this session.
        }
        val entry = LibraryFolderEntry(treeUri.toString(), SafTreeScanner.folderNameFromTreeUri(treeUri))
        save(context, LibraryFolderEntry.normalize(load(context) + entry))
        return entry
    }

    fun remove(context: Context, entry: LibraryFolderEntry) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(entry.uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        save(context, load(context).filterNot { it.uriString == entry.uriString })
    }

    fun accessibleUris(context: Context): Set<String> = try {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}
