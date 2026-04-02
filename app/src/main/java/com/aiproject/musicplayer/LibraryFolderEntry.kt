package com.aiproject.musicplayer

import java.util.Base64

data class LibraryFolderEntry(
    val uriString: String,
    val label: String,
) {
    companion object {
        const val PREF_KEY = "library_folders_json"
        const val LEGACY_URI_KEY = "saved_folder_uris_json"

        fun serialize(entries: List<LibraryFolderEntry>): String {
            val normalized = normalize(entries)
            return normalized.joinToString(separator = "\n") { entry ->
                "${encode(entry.uriString)}\t${encode(entry.label)}"
            }
        }

        fun deserialize(serialized: String?): List<LibraryFolderEntry> {
            if (serialized.isNullOrBlank()) return emptyList()
            return normalize(
                serialized.lineSequence().mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) {
                        null
                    } else {
                        val uriString = decode(parts[0]).trim()
                        if (uriString.isEmpty()) {
                            null
                        } else {
                            LibraryFolderEntry(
                                uriString = uriString,
                                label = decode(parts[1]).trim()
                            )
                        }
                    }
                }.toList()
            )
        }

        fun fromLegacyUris(uriStrings: List<String>, labelResolver: (String) -> String): List<LibraryFolderEntry> {
            return normalize(
                uriStrings.mapNotNull { uriString ->
                    val normalizedUri = uriString.trim()
                    if (normalizedUri.isEmpty()) {
                        null
                    } else {
                        LibraryFolderEntry(
                            uriString = normalizedUri,
                            label = labelResolver(normalizedUri)
                        )
                    }
                }
            )
        }

        fun normalize(entries: List<LibraryFolderEntry>): List<LibraryFolderEntry> {
            return entries
                .mapNotNull { entry ->
                    val uriString = entry.uriString.trim()
                    if (uriString.isEmpty()) {
                        null
                    } else {
                        val normalizedLabel = entry.label.trim().ifEmpty { uriString }
                        LibraryFolderEntry(uriString = uriString, label = normalizedLabel)
                    }
                }
                .distinctBy { it.uriString }
        }

        private fun encode(value: String): String {
            return Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        }

        private fun decode(value: String): String {
            return try {
                String(Base64.getDecoder().decode(value), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }
}