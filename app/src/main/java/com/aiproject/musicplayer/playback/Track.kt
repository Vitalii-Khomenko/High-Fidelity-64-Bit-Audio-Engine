package com.aiproject.musicplayer.playback

/**
 * A playable item. [uri] is a content://, file:// or http(s):// (DLNA) URI
 * string; a CUE sheet track carries its range in the fragment (see [PlayableUri]).
 */
data class Track(
    val uri: String,
    val title: String,
    val folder: String = "",
    val durationMs: Long = 0L,
    val artist: String = "",
    val album: String = "",
    /** Position on the album (tags or CUE sheet), 0 when unknown. */
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
)

enum class RepeatMode(val id: Int) {
    OFF(0), ALL(1), ONE(2);

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: Int): RepeatMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Music forgets positions; Books keep a bookmark per track and mark finished chapters. */
enum class ContentMode(val id: Int) {
    MUSIC(0), BOOKS(1);

    val remembersPosition: Boolean get() = this == BOOKS

    companion object {
        fun fromId(id: Int): ContentMode = entries.firstOrNull { it.id == id } ?: MUSIC
    }
}

/** Sonic profile used when the speed is not 1x. */
enum class SpeedMode(val id: Int) {
    MUSIC(0), SPEECH(1);

    companion object {
        fun fromId(id: Int): SpeedMode = entries.firstOrNull { it.id == id } ?: MUSIC
    }
}

/** Headphone crossfeed presets (bs2b); ids match dsp::Crossfeed::Preset. */
enum class CrossfeedMode(val id: Int) {
    OFF(0), DEFAULT(1), CHU_MOY(2), JAN_MEIER(3);

    companion object {
        fun fromId(id: Int): CrossfeedMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

enum class ReplayGainMode(val id: Int) {
    OFF(0), TRACK(1), ALBUM(2);

    companion object {
        fun fromId(id: Int): ReplayGainMode = entries.firstOrNull { it.id == id } ?: TRACK
    }
}

enum class SortMode(val id: Int) {
    NAME(0), NUMBER(1), ALBUM(2);

    fun next(): SortMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: Int): SortMode = entries.firstOrNull { it.id == id } ?: NAME
    }
}

object TimeFormat {
    /** 0:07, 3:25, 1:02:03 */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** 42s, 3m 05s, 2h 07m */
    fun span(ms: Long): String {
        if (ms <= 0L) return ""
        val total = ms / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "%ds".format(s)
        }
    }
}
