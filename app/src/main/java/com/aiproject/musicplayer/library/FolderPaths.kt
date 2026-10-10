package com.aiproject.musicplayer.library

import java.net.URLDecoder

/**
 * Readable paths from the document URIs of the library index. Path-style
 * providers (internal storage, SD cards) encode the path in the document id
 * ("primary:Music/Mixes/2024"); other providers use opaque ids, which give "".
 */
object FolderPaths {
    /** "…/document/primary%3AMusic%2FMixes%2F2024" → "Music/Mixes/2024". */
    fun path(documentUri: String): String {
        val id = decode(documentUri.substringAfterLast("/document/", ""))
        if (':' !in id) return ""
        val path = id.substringAfter(':').trim('/')
        // Opaque ids ("msf:1234", "audio:56") are numbers, not paths.
        return if (path.isEmpty() || path.all { it.isDigit() }) "" else path
    }

    /** The folder above: "Music/Mixes/2024" → "Music/Mixes". */
    fun parent(documentUri: String): String = path(documentUri).substringBeforeLast('/', "")

    /** The file's own name, for ordering a folder as a file manager shows it; "" when opaque. */
    fun fileName(documentUri: String): String = path(documentUri).substringAfterLast('/')

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
}
