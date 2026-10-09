package com.aiproject.musicplayer.library

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Saved SAF folders (stored in the same preferences/format as 0.8.x). */
object LibraryFolders {
    private const val PREFS = "player_state"

    // Trees opened this process without a persistable grant: readable until restart.
    private val sessionGrants = java.util.Collections.synchronizedSet(HashSet<String>())

    data class Added(val entry: LibraryFolderEntry, val persisted: Boolean)

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

    /**
     * Persists read access to a picked tree. Some providers only grant access
     * for this session ([Added.persisted] false): the folder works until the
     * app restarts and must then be picked again.
     */
    fun add(context: Context, treeUri: Uri): Added {
        val persisted = try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
        if (!persisted) sessionGrants += treeUri.toString()
        val entry = LibraryFolderEntry(treeUri.toString(), SafTreeScanner.folderNameFromTreeUri(treeUri))
        save(context, LibraryFolderEntry.normalize(load(context) + entry))
        return Added(entry, persisted)
    }

    fun remove(context: Context, entry: LibraryFolderEntry) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(entry.uriString), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        save(context, load(context).filterNot { it.uriString == entry.uriString })
    }

    fun accessibleUris(context: Context): Set<String> = try {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet() +
            synchronized(sessionGrants) { sessionGrants.toSet() }
    } catch (_: Exception) {
        synchronized(sessionGrants) { sessionGrants.toSet() }
    }
}
