package com.aiproject.musicplayer

data class PlaylistSummary(
    val trackCount: Int,
    val totalDurationMs: Long,
) {
    val formattedDuration: String
        get() {
            if (totalDurationMs <= 0L) return ""
            val totalSeconds = totalDurationMs / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return when {
                hours > 0L -> "%dh %02dm".format(hours, minutes)
                minutes > 0L -> "%dm %02ds".format(minutes, seconds)
                else -> "%ds".format(seconds)
            }
        }
}

object PlaylistSummaryCalculator {
    fun fromTracks(tracks: List<AudioTrack>): PlaylistSummary =
        PlaylistSummary(
            trackCount = tracks.size,
            totalDurationMs = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }
        )
}