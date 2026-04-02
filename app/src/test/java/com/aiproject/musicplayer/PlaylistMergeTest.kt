package com.aiproject.musicplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistMergeTest {

    @Test fun `append distinct keeps existing order and appends only new keys`() {
        val merged = PlaylistMerge.appendDistinctBy(
            existing = listOf("a", "b"),
            incoming = listOf("b", "c", "d")
        ) { it }

        assertEquals(listOf("a", "b", "c", "d"), merged)
    }

    @Test fun `append distinct with only duplicates returns original contents`() {
        val merged = PlaylistMerge.appendDistinctBy(
            existing = listOf("a", "b"),
            incoming = listOf("a", "b")
        ) { it }

        assertEquals(listOf("a", "b"), merged)
    }

    @Test fun `append distinct handles empty incoming list`() {
        val merged = PlaylistMerge.appendDistinctBy(
            existing = listOf("a"),
            incoming = emptyList<String>()
        ) { it }

        assertEquals(listOf("a"), merged)
    }
}