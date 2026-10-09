package com.aiproject.musicplayer.library

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One track of a CUE sheet: a time range of [fileName]. */
data class CueTrack(
    val number: Int,
    val title: String,
    val performer: String,
    val fileName: String,
    val startUs: Long,
    /** 0 = to the end of the file. */
    val endUs: Long,
)

data class CueSheet(
    val title: String,
    val performer: String,
    val genre: String,
    val date: String,
    val tracks: List<CueTrack>,
) {
    val fileNames: Set<String> get() = tracks.mapTo(LinkedHashSet()) { it.fileName }
}

/**
 * CUE sheet parser (pure JVM code).
 *
 * A track starts at its INDEX 01. The pregap (INDEX 00) stays at the end of
 * the previous track of the same file, so consecutive tracks join without a
 * gap or an overlap. Tracks in different files (one file per track) run to
 * the end of their file.
 */
object CueParser {
    const val MAX_BYTES = 256 * 1024

    private val windows1251: Charset = Charset.forName("windows-1251")
    private val windows1252: Charset = Charset.forName("windows-1252")

    /**
     * CUE files come in UTF-8 (with or without a BOM) or in the legacy code
     * page of the ripping PC: Windows-1251 when most letters are in the upper
     * half (Cyrillic), otherwise Windows-1252.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            // Not UTF-8: a legacy code page.
        }
        var high = 0
        var ascii = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c >= 0xC0) high++ else if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code) ascii++
        }
        return String(bytes, if (high >= 2 && high * 2 > high + ascii) windows1251 else windows1252)
    }

    fun parse(text: String): CueSheet? {
        var albumTitle = ""
        var albumPerformer = ""
        var genre = ""
        var date = ""
        var file = ""

        data class Pending(val number: Int, val file: String, var title: String = "", var performer: String = "", var startUs: Long = -1L)
        val pending = mutableListOf<Pending>()
        var current: Pending? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val command = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (command) {
                "FILE" -> {
                    // FILE "name" WAVE, or unquoted: FILE name with spaces.flac WAVE
                    file = if (rest.startsWith('"')) quoted(rest) else rest.substringBeforeLast(' ', rest)
                    current = null
                }
                "TRACK" -> {
                    val number = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val isAudio = rest.substringAfter(' ', "AUDIO").trim().uppercase() == "AUDIO"
                    current = if (isAudio && file.isNotEmpty()) Pending(number, file).also { pending += it } else null
                }
                "TITLE" -> if (current != null) current.title = quoted(rest) else albumTitle = quoted(rest)
                "PERFORMER" -> if (current != null) current.performer = quoted(rest) else albumPerformer = quoted(rest)
                "INDEX" -> {
                    val track = current ?: continue
                    val index = rest.substringBefore(' ').toIntOrNull() ?: continue
                    val time = parseTime(rest.substringAfter(' ', "").trim()) ?: continue
                    if (index == 1) track.startUs = time
                }
                "REM" -> {
                    val key = rest.substringBefore(' ').uppercase()
                    val value = quoted(rest.substringAfter(' ', ""))
                    if (key == "GENRE") genre = value
                    if (key == "DATE") date = value
                }
            }
        }

        val tracks = pending.filter { it.startUs >= 0 }
        if (tracks.isEmpty()) return null
        val result = tracks.mapIndexed { i, t ->
            val next = tracks.getOrNull(i + 1)
            val end = if (next != null && next.file == t.file && next.startUs > t.startUs) next.startUs else 0L
            CueTrack(
                number = t.number,
                title = t.title.ifBlank { "Track %02d".format(t.number) },
                performer = t.performer.ifBlank { albumPerformer },
                fileName = t.file,
                startUs = t.startUs,
                endUs = end,
            )
        }
        return CueSheet(albumTitle, albumPerformer, genre, date, result)
    }

    /** "mm:ss:ff" with 75 frames per second → microseconds (rounded; exact at the sample level). */
    fun parseTime(text: String): Long? {
        val parts = text.split(':')
        if (parts.size != 3) return null
        val m = parts[0].toLongOrNull() ?: return null
        val s = parts[1].toLongOrNull() ?: return null
        val f = parts[2].toLongOrNull() ?: return null
        if (m < 0 || s !in 0..59 || f !in 0..74) return null
        val frames = (m * 60 + s) * 75 + f
        return (frames * 1_000_000L + 37) / 75
    }

    private fun quoted(text: String): String {
        val t = text.trim()
        if (t.startsWith('"')) {
            val end = t.indexOf('"', 1)
            return if (end > 0) t.substring(1, end) else t.substring(1)
        }
        return t
    }
}
