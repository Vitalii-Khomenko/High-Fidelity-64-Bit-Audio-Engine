package com.aiproject.musicplayer.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aiproject.musicplayer.NativeTags
import com.aiproject.musicplayer.playback.PlayableUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lyrics of a track: timed lines (LRC) or plain text. */
sealed interface Lyrics {
    data class Synced(val lines: List<Line>) : Lyrics {
        /** Index of the line being sung at [positionMs], -1 before the first. */
        fun indexAt(positionMs: Long): Int {
            var lo = 0
            var hi = lines.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
            }
            return found
        }
    }
    data class Plain(val text: String) : Lyrics

    data class Line(val timeMs: Long, val text: String)
}

/**
 * LRC parser (pure JVM code): "[mm:ss.xx]text", several time tags per line,
 * "[offset:+/-ms]", ID tags ([ar:], [ti:], …) ignored, enhanced word timings
 * ("<mm:ss.xx>") removed. Text without any time tag is plain lyrics.
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val idTag = Regex("""^\[[a-zA-Z#]+:.*]$""")

    fun parse(text: String): Lyrics? {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isEmpty()) return null
        // A positive offset shows lyrics earlier (LRC convention).
        val offset = offsetTag.find(clean)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<Lyrics.Line>()
        var timed = false
        for (raw in clean.lineSequence()) {
            val line = raw.trim()
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) continue
            // Only the leading run of time tags belongs to this line.
            var end = 0
            val times = mutableListOf<Long>()
            for (m in tags) {
                if (m.range.first != end) break
                end = m.range.last + 1
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                times += min * 60_000 + sec * 1000 + ms
            }
            timed = true
            val content = wordTag.replace(line.substring(end), "").trim()
            times.forEach { lines += Lyrics.Line((it - offset).coerceAtLeast(0L), content) }
        }
        if (!timed) {
            val plain = clean.lineSequence().filterNot { idTag.matches(it.trim()) }.joinToString("\n").trim()
            return if (plain.isEmpty()) null else Lyrics.Plain(plain)
        }
        val sorted = lines.sortedBy { it.timeMs }
        return if (sorted.isEmpty()) null else Lyrics.Synced(sorted)
    }
}

/** Finds lyrics for a track: a .lrc next to the file first, then the tags. */
object LyricsLoader {
    private const val MAX_BYTES = 512 * 1024

    suspend fun load(context: Context, trackUri: String): Lyrics? = withContext(Dispatchers.IO) {
        val file = Uri.parse(PlayableUri.split(trackUri).fileUri)
        if (file.scheme != "content" && file.scheme != "file") return@withContext null
        sidecar(context, file)?.let { return@withContext it }
        val text = runCatching {
            context.contentResolver.openFileDescriptor(file, "r")?.use { NativeTags.tags(it.fd)?.lyrics }
        }.getOrNull()
        text?.let(LrcParser::parse)
    }

    /** "Album/01 Song.flac" → "Album/01 Song.lrc" in path-style document trees (ExternalStorageProvider). */
    private fun sidecar(context: Context, file: Uri): Lyrics? = try {
        val docId = DocumentsContract.getDocumentId(file)
        val treeId = DocumentsContract.getTreeDocumentId(file)
        val base = docId.substringBeforeLast('.', docId)
        listOf("$base.lrc", "$base.LRC", "$base.txt").firstNotNullOfOrNull { id ->
            runCatching {
                val uri = DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(file.authority, treeId), id)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_BYTES) null else LrcParser.parse(CueParser.decode(bytes))
                }
            }.getOrNull()
        }
    } catch (_: Exception) {
        null
    }
}
