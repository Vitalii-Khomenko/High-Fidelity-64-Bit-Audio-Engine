package com.aiproject.musicplayer.playback

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persisted settings; field ids match the keys used by earlier versions. */
data class PlayerSettings(
    val contentMode: ContentMode = ContentMode.MUSIC,
    val speed: Float = 1f,
    val speedMode: SpeedMode = SpeedMode.MUSIC,
    /** Position on the app's decibel volume scale (VolumeCurve), 1 = 0 dB. */
    val volume: Float = 1f,
    val eq: EqSettings = EqSettings(),
    val replayGain: ReplayGainMode = ReplayGainMode.TRACK,
    val repeat: RepeatMode = RepeatMode.OFF,
    val sortMode: SortMode = SortMode.ALBUM,
    val crossfeed: CrossfeedMode = CrossfeedMode.OFF,
    val limiter: Boolean = true,
    /** Measure (EBU R128) upcoming tracks that have no ReplayGain tags. */
    val autoAnalyze: Boolean = true,
    /** Visible on the network as a DLNA / UPnP renderer. */
    val renderer: Boolean = false,
    /** Bit-perfect output to a USB DAC (Android 14+, when the device supports it). */
    val bitPerfect: Boolean = false,
    /** The app's own USB Audio Class driver for USB DACs (any Android version). */
    val ownUsbDriver: Boolean = false,
)

data class SavedQueue(
    val tracks: List<Track>,
    val index: Int,
    val shuffle: Boolean,
    val order: List<Int>,
    val resumeUri: String?,
    val resumePositionMs: Long,
)

/**
 * SharedPreferences persistence for the queue, settings, per-track bookmarks
 * ("Books" mode) and finished chapters. Reads the formats written by 0.8.x.
 */
class PlayerStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
    private val progress: SharedPreferences = context.getSharedPreferences("audiobook_progress", Context.MODE_PRIVATE)

    /** The decibel-scale position; older versions stored a linear gain, converted at the same loudness. */
    private fun loadVolume(): Float {
        val position = prefs.getFloat(KEY_VOLUME_POSITION, Float.NaN)
        if (position.isFinite()) return position.coerceIn(0f, 1f)
        val linear = prefs.getFloat(KEY_VOLUME, 1f)
        return if (linear.isFinite()) VolumeCurve.positionOf(linear.toDouble()) else 1f
    }

    fun loadSettings(): PlayerSettings = PlayerSettings(
        contentMode = ContentMode.fromId(prefs.getInt(KEY_CONTENT_MODE, ContentMode.MUSIC.id)),
        speed = PlaybackSpeed.clamp(prefs.getFloat(KEY_SPEED, 1f)),
        speedMode = SpeedMode.fromId(prefs.getInt(KEY_SPEED_MODE, SpeedMode.MUSIC.id)),
        volume = loadVolume(),
        eq = EqSettings.deserialize(
            prefs.getBoolean(KEY_EQ_ENABLED, false), prefs.getString(KEY_EQ_GAINS, null),
            prefs.getInt(KEY_EQ_MODE, 0), prefs.getString(KEY_EQ_PROFILE, null),
        ),
        replayGain = ReplayGainMode.fromId(prefs.getInt(KEY_REPLAY_GAIN, ReplayGainMode.TRACK.id)),
        repeat = RepeatMode.fromId(prefs.getInt(KEY_REPEAT, RepeatMode.OFF.id)),
        sortMode = SortMode.fromId(prefs.getInt(KEY_SORT_MODE, SortMode.ALBUM.id)),
        crossfeed = CrossfeedMode.fromId(prefs.getInt(KEY_CROSSFEED, 0)),
        limiter = prefs.getBoolean(KEY_LIMITER, true),
        autoAnalyze = prefs.getBoolean(KEY_AUTO_ANALYZE, true),
        renderer = prefs.getBoolean(KEY_RENDERER, false),
        bitPerfect = prefs.getBoolean(KEY_BIT_PERFECT, false),
        ownUsbDriver = prefs.getBoolean(KEY_OWN_USB_DRIVER, false),
    )

    fun saveSettings(s: PlayerSettings) {
        prefs.edit()
            .putInt(KEY_CONTENT_MODE, s.contentMode.id)
            .putFloat(KEY_SPEED, s.speed)
            .putInt(KEY_SPEED_MODE, s.speedMode.id)
            .putFloat(KEY_VOLUME_POSITION, s.volume)
            .putBoolean(KEY_EQ_ENABLED, s.eq.enabled)
            .putString(KEY_EQ_GAINS, s.eq.serialize())
            .putInt(KEY_REPLAY_GAIN, s.replayGain.id)
            .putInt(KEY_REPEAT, s.repeat.id)
            .putInt(KEY_SORT_MODE, s.sortMode.id)
            .putInt(KEY_EQ_MODE, s.eq.mode.id)
            .putString(KEY_EQ_PROFILE, s.eq.profile?.serialize())
            .putInt(KEY_CROSSFEED, s.crossfeed.id)
            .putBoolean(KEY_LIMITER, s.limiter)
            .putBoolean(KEY_AUTO_ANALYZE, s.autoAnalyze)
            .putBoolean(KEY_RENDERER, s.renderer)
            .putBoolean(KEY_BIT_PERFECT, s.bitPerfect)
            .putBoolean(KEY_OWN_USB_DRIVER, s.ownUsbDriver)
            .apply()
    }

    fun loadQueue(): SavedQueue {
        val tracks = decodeTracks(prefs.getString(KEY_QUEUE, null))
        return SavedQueue(
            tracks = tracks,
            index = prefs.getInt(KEY_INDEX, -1),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            order = decodeInts(prefs.getString(KEY_SHUFFLE_ORDER, null)),
            resumeUri = prefs.getString(KEY_RESUME_URI, null),
            resumePositionMs = prefs.getFloat(KEY_RESUME_POS, 0f).toLong(),
        )
    }

    fun saveTracks(tracks: List<Track>) {
        prefs.edit().putString(KEY_QUEUE, encodeTracks(tracks)).apply()
    }

    fun savePosition(index: Int, shuffle: Boolean, order: List<Int>) {
        prefs.edit()
            .putInt(KEY_INDEX, index)
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putString(KEY_SHUFFLE_ORDER, JSONArray(order).toString())
            .apply()
    }

    fun saveResumePoint(uri: String?, positionMs: Long) {
        val editor = prefs.edit()
        if (uri == null || positionMs <= 0L) editor.remove(KEY_RESUME_URI).remove(KEY_RESUME_POS)
        else editor.putString(KEY_RESUME_URI, uri).putFloat(KEY_RESUME_POS, positionMs.toFloat())
        editor.apply()
    }

    // ── Books mode ───────────────────────────────────────────────────────────

    fun bookmark(uri: String): Long {
        val key = bookmarkKey(uri)
        if (prefs.contains(key)) return prefs.getFloat(key, 0f).toLong()
        // 0.8.x and earlier keyed bookmarks by uri.hashCode(); migrate on first read.
        val legacyKey = legacyBookmarkKey(uri)
        if (!prefs.contains(legacyKey)) return 0L
        val position = prefs.getFloat(legacyKey, 0f)
        prefs.edit().putFloat(key, position).remove(legacyKey).apply()
        return position.toLong()
    }

    fun saveBookmark(uri: String, positionMs: Long) {
        prefs.edit().putFloat(bookmarkKey(uri), positionMs.toFloat()).apply()
    }

    fun clearBookmark(uri: String) {
        prefs.edit().remove(bookmarkKey(uri)).remove(legacyBookmarkKey(uri)).apply()
    }

    fun playedUris(): Set<String> = progress.getStringSet(KEY_PLAYED, emptySet())?.toSet() ?: emptySet()

    fun savePlayedUris(uris: Set<String>) {
        progress.edit().putStringSet(KEY_PLAYED, HashSet(uris)).apply()
    }

    private fun bookmarkKey(uri: String) = "pos_uri_$uri"

    // String.hashCode() is specified by the JVM, so this matches the old Uri.hashCode()
    // of android.net.Uri (which hashes its string form).
    private fun legacyBookmarkKey(uri: String) = "pos_${uri.hashCode()}"

    companion object {
        private const val KEY_QUEUE = "playlist_json"
        private const val KEY_INDEX = "current_index"
        private const val KEY_SHUFFLE = "shuffle_enabled"
        private const val KEY_SHUFFLE_ORDER = "shuffle_order_json"
        private const val KEY_RESUME_URI = "saved_position_uri"
        private const val KEY_RESUME_POS = "saved_position_ms"
        private const val KEY_CONTENT_MODE = "playback_content_mode"
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_SPEED_MODE = "playback_speed_mode"
        private const val KEY_VOLUME = "volume"                    // linear gain, up to 0.19.1
        private const val KEY_VOLUME_POSITION = "volume_position"  // VolumeCurve position
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_GAINS = "eq_gains"
        private const val KEY_REPLAY_GAIN = "replaygain_mode"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_SORT_MODE = "playlist_sort_mode"
        private const val KEY_PLAYED = "played_uris"
        private const val KEY_EQ_MODE = "eq_mode"
        private const val KEY_EQ_PROFILE = "eq_profile"
        private const val KEY_CROSSFEED = "crossfeed_mode"
        private const val KEY_LIMITER = "true_peak_limiter"
        private const val KEY_AUTO_ANALYZE = "loudness_auto_analyze"
        private const val KEY_RENDERER = "dlna_renderer"
        private const val KEY_BIT_PERFECT = "bit_perfect_usb"
        private const val KEY_OWN_USB_DRIVER = "own_usb_driver"

        fun encodeTracks(tracks: List<Track>): String = JSONArray().apply {
            tracks.forEach { t ->
                val o = JSONObject().put("uri", t.uri).put("name", t.title).put("folder", t.folder).put("durationMs", t.durationMs)
                if (t.artist.isNotEmpty()) o.put("artist", t.artist)
                if (t.album.isNotEmpty()) o.put("album", t.album)
                if (t.trackNumber > 0) o.put("track", t.trackNumber)
                if (t.discNumber > 0) o.put("disc", t.discNumber)
                put(o)
            }
        }.toString()

        fun decodeTracks(json: String?): List<Track> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Track(
                        uri, o.optString("name", uri), o.optString("folder", ""), o.optLong("durationMs", 0L),
                        artist = o.optString("artist", ""), album = o.optString("album", ""),
                        trackNumber = o.optInt("track", 0), discNumber = o.optInt("disc", 0),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun decodeInts(json: String?): List<Int> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { array.getInt(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
