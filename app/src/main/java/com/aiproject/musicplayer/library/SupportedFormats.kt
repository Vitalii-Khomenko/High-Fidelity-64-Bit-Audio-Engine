package com.aiproject.musicplayer.library

import java.util.Locale

/** Containers the native engine decodes. Anything else is not offered for playback. */
object SupportedFormats {
    val EXTENSIONS = setOf("flac", "wav", "wave", "w64", "rf64", "aif", "aiff", "aifc", "mp3", "dsf", "dff")

    fun isSupportedName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS

    /** Uses the file name first; falls back to MIME for providers that hide extensions. */
    fun isSupported(name: String, mime: String?): Boolean {
        if (isSupportedName(name)) return true
        if (name.contains('.')) return false
        return when (mime?.lowercase(Locale.ROOT)) {
            "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
            "audio/aiff", "audio/x-aiff", "audio/mpeg", "audio/mp3", "audio/x-dsf", "audio/dsf",
            "audio/x-dff", "audio/dff" -> true
            else -> false
        }
    }

    fun displayTitle(fileName: String): String = fileName.substringBeforeLast('.', fileName)
}
