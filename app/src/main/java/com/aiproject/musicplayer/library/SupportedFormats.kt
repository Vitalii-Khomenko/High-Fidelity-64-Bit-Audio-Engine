package com.aiproject.musicplayer.library

import java.util.Locale

/** Containers the native engine decodes. Anything else is not offered for playback. */
object SupportedFormats {
    val EXTENSIONS = setOf(
        // Own decoders
        "flac", "wav", "wave", "w64", "rf64", "aif", "aiff", "aifc", "mp3", "dsf", "dff",
        "ogg", "oga", "opus", "wv", "ape", "tta",
        // Through the phone's MediaCodec
        "m4a", "m4b", "mp4", "aac", "alac", "caf", "mka", "webm", "3gp", "amr",
    )

    private val MIMES = setOf(
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
        "audio/aiff", "audio/x-aiff", "audio/mpeg", "audio/mp3", "audio/x-dsf", "audio/dsf",
        "audio/x-dff", "audio/dff", "audio/ogg", "audio/x-ogg", "audio/vorbis", "audio/opus",
        "audio/x-wavpack", "audio/wavpack", "audio/x-ape", "audio/ape", "audio/x-monkeys-audio",
        "audio/x-tta", "audio/tta", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/x-aac",
        "audio/aacp", "audio/alac", "audio/x-matroska", "audio/webm", "audio/3gpp", "audio/amr",
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isSupportedName(name: String): Boolean = extension(name) in EXTENSIONS

    /** Uses the file name first; falls back to MIME for providers that hide extensions. */
    fun isSupported(name: String, mime: String?): Boolean {
        if (isSupportedName(name)) return true
        if (name.contains('.')) return false
        return mime?.lowercase(Locale.ROOT) in MIMES
    }

    fun isCue(name: String): Boolean = extension(name) == "cue"

    fun displayTitle(fileName: String): String = fileName.substringBeforeLast('.', fileName)
}
