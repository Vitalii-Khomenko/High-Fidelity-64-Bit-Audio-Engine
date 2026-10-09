package com.aiproject.musicplayer.library

import android.content.Context
import com.aiproject.musicplayer.playback.EqBandSpec
import com.aiproject.musicplayer.playback.EqFilter
import com.aiproject.musicplayer.playback.EqProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Parametric EQ text as written by AutoEQ ("… ParametricEQ.txt") and
 * Equalizer APO (pure JVM code):
 *
 *   Preamp: -6.3 dB
 *   Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
 *   Filter 2: ON PK Fc 125 Hz Gain -2.7 dB Q 0.55
 *
 * Disabled filters and unknown types are skipped; shelves and passes without
 * a Q get 0.707.
 */
object AutoEqParser {
    private val fc = Regex("""\bFc\s+([0-9]+(?:[.,][0-9]+)?)\s*(k?)Hz""", RegexOption.IGNORE_CASE)
    private val gain = Regex("""\bGain\s+([-+]?[0-9]+(?:[.,][0-9]+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val q = Regex("""\bQ\s+([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val preamp = Regex("""^\s*Preamp\s*:\s*([-+]?[0-9]+(?:[.,][0-9]+)?)\s*dB""", setOf(RegexOption.IGNORE_CASE))

    fun parse(text: String, name: String): EqProfile? {
        var pre = 0.0
        val bands = mutableListOf<EqBandSpec>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            preamp.find(line)?.let { pre = number(it.groupValues[1]) ?: pre }
            if (!line.startsWith("Filter", ignoreCase = true)) continue
            val body = line.substringAfter(':', "").trim()
            val words = body.split(Regex("\\s+"))
            if (words.firstOrNull()?.uppercase(Locale.ROOT) != "ON") continue
            val filter = when (words.getOrNull(1)?.uppercase(Locale.ROOT)) {
                "PK", "PEQ", "MODAL" -> EqFilter.PEAK
                "LS", "LSC", "LSQ" -> EqFilter.LOW_SHELF
                "HS", "HSC", "HSQ" -> EqFilter.HIGH_SHELF
                "LP", "LPQ" -> EqFilter.LOW_PASS
                "HP", "HPQ" -> EqFilter.HIGH_PASS
                else -> continue
            }
            val f = fc.find(body)?.let { m -> number(m.groupValues[1])?.let { if (m.groupValues[2].isNotEmpty()) it * 1000 else it } } ?: continue
            val g = gain.find(body)?.let { number(it.groupValues[1]) } ?: 0.0
            val quality = q.find(body)?.let { number(it.groupValues[1]) } ?: 0.707
            if (f !in 5.0..40000.0 || quality <= 0.0) continue
            bands += EqBandSpec(filter, f, quality, g.coerceIn(-30.0, 30.0))
            if (bands.size == EqProfile.MAX_BANDS) break
        }
        if (bands.isEmpty()) return null
        return EqProfile(name, pre.coerceIn(-30.0, 0.0), bands)
    }

    private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** One headphone in the AutoEQ results index. */
data class AutoEqEntry(val name: String, val source: String, val path: String)

/**
 * The AutoEQ results on GitHub (MIT): the index of ~9000 measured headphones
 * and their parametric profiles. The index is cached for a week.
 */
object AutoEqCatalog {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private const val MAX_INDEX_BYTES = 4L shl 20
    private const val MAX_PROFILE_BYTES = 64L shl 10
    private const val INDEX_TTL_MS = 7L * 24 * 3600 * 1000
    private val line = Regex("""^- \[(.+)]\(\./(.+)\)(?: by (.+))?$""")

    /** Entries of INDEX.md ("- [Name](./source/rig/Name) by source on rig"). */
    fun parseIndex(markdown: String): List<AutoEqEntry> = markdown.lineSequence().mapNotNull { l ->
        val m = line.find(l.trim()) ?: return@mapNotNull null
        AutoEqEntry(m.groupValues[1], m.groupValues[3], m.groupValues[2])
    }.toList()

    fun search(entries: List<AutoEqEntry>, query: String, limit: Int = 60): List<AutoEqEntry> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e -> val n = e.name.lowercase(Locale.ROOT); words.all { it in n } }
            .sortedBy { it.name.length }
            .take(limit)
    }

    /** Profile file URL for an entry: <path>/<name> ParametricEQ.txt. */
    fun profileUrl(entry: AutoEqEntry): String {
        val folder = entry.path.trimEnd('/')
        val last = URLDecoder.decode(folder.substringAfterLast('/'), "UTF-8")
        val file = java.net.URLEncoder.encode("$last ParametricEQ.txt", "UTF-8").replace("+", "%20")
        return BASE + folder + "/" + file
    }

    suspend fun index(context: Context): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "autoeq-index.md")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < INDEX_TTL_MS
        val text = if (fresh) cache.readText() else try {
            download(BASE + "INDEX.md", MAX_INDEX_BYTES).also { cache.writeText(it) }
        } catch (e: IOException) {
            if (cache.exists()) cache.readText() else throw e
        }
        parseIndex(text)
    }

    suspend fun profile(entry: AutoEqEntry): EqProfile? = withContext(Dispatchers.IO) {
        AutoEqParser.parse(download(profileUrl(entry), MAX_PROFILE_BYTES), entry.name)
    }

    private fun download(url: String, limit: Long): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw IOException("Response too large")
                    out.write(buffer, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
