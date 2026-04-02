package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderingTest {
    @Test
    fun `number sort uses numeric tokens anywhere in filename`() {
        val sorted = listOf(
            "chapter 12 finale.mp3",
            "chapter 2 intro.mp3",
            "part_105_end.mp3",
            "part_9_middle.mp3",
        ).sortedBy {
            PlaylistOrdering.numericSortKey(it)
        }

        assertEquals(
            listOf(
                "chapter 2 intro.mp3",
                "part_9_middle.mp3",
                "chapter 12 finale.mp3",
                "part_105_end.mp3",
            ),
            sorted
        )
    }

    @Test
    fun `name sort stays natural for embedded numbers`() {
        val sorted = listOf(
            "Track 10.mp3",
            "Track 2.mp3",
            "Track 01.mp3",
        ).sortedBy {
            PlaylistOrdering.naturalSortKey(it)
        }

        assertEquals(
            listOf(
                "Track 01.mp3",
                "Track 2.mp3",
                "Track 10.mp3",
            ),
            sorted
        )
    }

    @Test
    fun `name sort ignores leading numeric prefixes`() {
        val sorted = listOf(
            "02 - Banana.mp3",
            "10 - Cherry.mp3",
            "01 - Apple.mp3",
        ).sortedBy {
            PlaylistOrdering.naturalSortKey(it)
        }

        assertEquals(
            listOf(
                "01 - Apple.mp3",
                "02 - Banana.mp3",
                "10 - Cherry.mp3",
            ),
            sorted
        )
    }

    @Test
    fun `number sort keeps non-number tracks after numbered ones`() {
        val sorted = listOf(
            "preface.mp3",
            "chapter 03.mp3",
            "chapter 01.mp3",
        ).sortedBy {
            PlaylistOrdering.numericSortKey(it)
        }

        assertEquals(
            listOf(
                "chapter 01.mp3",
                "chapter 03.mp3",
                "preface.mp3",
            ),
            sorted
        )
    }
}