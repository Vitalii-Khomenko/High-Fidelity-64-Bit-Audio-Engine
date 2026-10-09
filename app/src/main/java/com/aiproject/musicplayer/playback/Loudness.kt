package com.aiproject.musicplayer.playback

import android.content.Context
import android.net.Uri
import com.aiproject.musicplayer.NativeTags
import com.aiproject.musicplayer.db.LoudnessEntity
import com.aiproject.musicplayer.db.MusicDatabase
import kotlin.math.log10
import kotlin.math.pow

/** ReplayGain values from EBU R128 measurements (pure JVM code). */
object LoudnessMath {
    /** ReplayGain 2.0 reference level. */
    const val REFERENCE_LUFS = -18.0

    fun gainDb(lufs: Double): Double = REFERENCE_LUFS - lufs

    fun peakLinear(truePeakDb: Double): Double = 10.0.pow(truePeakDb / 20.0)

    /**
     * Album loudness as the duration-weighted energy mean of its tracks. (Exact
     * R128 album gating would need every block; this is within a few tenths
     * of a dB for real albums.)
     */
    fun albumLufs(tracks: List<Pair<Double, Double>>): Double? {
        val valid = tracks.filter { it.first.isFinite() && it.second > 0 }
        if (valid.isEmpty()) return null
        val total = valid.sumOf { it.second }
        val energy = valid.sumOf { 10.0.pow(it.first / 10.0) * it.second } / total
        return 10.0 * log10(energy)
    }

    /** [trackGainDb, trackPeak, albumGainDb, albumPeak] for the engine; NaN = unknown. */
    fun fallback(track: LoudnessEntity?, album: List<LoudnessEntity>): DoubleArray {
        val out = DoubleArray(4) { Double.NaN }
        val lufs = track?.lufs
        if (lufs != null && lufs.isFinite()) {
            out[0] = gainDb(lufs)
            track.truePeakDb?.takeIf { it.isFinite() }?.let { out[1] = peakLinear(it) }
        }
        val measured = album.filter { it.lufs != null && it.seconds > 0 }
        albumLufs(measured.map { it.lufs!! to it.seconds })?.let { albumLufs ->
            out[2] = gainDb(albumLufs)
            measured.mapNotNull { it.truePeakDb }.filter { it.isFinite() }.maxOrNull()?.let { out[3] = peakLinear(it) }
        }
        return out
    }
}

/**
 * Measures files that have no ReplayGain tags (EBU R128, native) and keeps the
 * results, so they can be normalised like tagged files.
 */
class LoudnessAnalyzer(context: Context) {
    private val app = context.applicationContext
    private val dao = MusicDatabase.getDatabase(app).loudnessDao()

    /** A file that carries ReplayGain tags is stored with seconds = -1 and never measured. */
    suspend fun needsAnalysis(uri: String): Boolean {
        if (!uri.startsWith("content:") && !uri.startsWith("file:")) return false
        return dao.get(uri) == null
    }

    /** Measures (or marks as tagged) one track; blocking for seconds. Call on a background dispatcher. */
    suspend fun analyze(uri: String): LoudnessEntity? {
        val parts = PlayableUri.split(uri)
        val entry = try {
            app.contentResolver.openFileDescriptor(Uri.parse(parts.fileUri), "r")?.use { pfd ->
                val tags = NativeTags.tags(pfd.fd)
                if (tags?.trackGainDb != null || tags?.albumGainDb != null) {
                    LoudnessEntity(uri, null, null, -1.0, System.currentTimeMillis())
                } else {
                    val r = NativeTags.loudness(pfd.fd, parts.startUs, parts.endUs) ?: return null
                    LoudnessEntity(uri, r[0].takeIf { it.isFinite() }, r[1].takeIf { it.isFinite() }, r[2], System.currentTimeMillis())
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        dao.put(entry)
        return entry
    }

    suspend fun fallbackFor(uri: String): DoubleArray {
        val track = dao.get(uri)
        val mates = dao.albumMates(uri)
        val album = if (mates.size > 1) dao.getAll(mates).let { if (it.size == mates.size) it else emptyList() } else emptyList()
        return LoudnessMath.fallback(track, album)
    }

    suspend fun libraryPending(): List<String> = dao.libraryWithout()
}
