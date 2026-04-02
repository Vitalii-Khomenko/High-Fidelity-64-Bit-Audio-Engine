package com.aiproject.musicplayer

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSummaryTest {

    @Test fun `summary counts tracks and sums durations`() {
        val tracks = listOf(
            AudioTrack(mockk(relaxed = true), "One", durationMs = 60_000L),
            AudioTrack(mockk(relaxed = true), "Two", durationMs = 90_000L),
        )
        val summary = PlaylistSummaryCalculator.fromTracks(tracks)
        assertEquals(2, summary.trackCount)
        assertEquals(150_000L, summary.totalDurationMs)
    }

    @Test fun `formatted duration uses minutes and seconds below one hour`() {
        val summary = PlaylistSummary(trackCount = 3, totalDurationMs = 125_000L)
        assertEquals("2m 05s", summary.formattedDuration)
    }

    @Test fun `formatted duration uses hours when needed`() {
        val summary = PlaylistSummary(trackCount = 20, totalDurationMs = 3_930_000L)
        assertEquals("1h 05m", summary.formattedDuration)
    }
}