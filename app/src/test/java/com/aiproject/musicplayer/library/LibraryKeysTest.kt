package com.aiproject.musicplayer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryKeysTest {
    @Test fun `albums with an album artist group across folders, case and spaces ignored`() {
        assertEquals(
            LibraryKeys.albumKey("Greatest  Hits", "ABBA", "content://a/1"),
            LibraryKeys.albumKey("greatest hits", " abba ", "content://a/2"),
        )
    }

    @Test fun `albums without an album artist stay per folder`() {
        assertNotEquals(
            LibraryKeys.albumKey("Hits", "", "content://a/1"),
            LibraryKeys.albumKey("Hits", "", "content://a/2"),
        )
    }

    @Test fun `search text and patterns fold Cyrillic case and neutralise wildcards`() {
        assertEquals("кино\nгруппа крови", LibraryKeys.searchText("Кино", "Группа Крови"))
        assertEquals("%кино%", LibraryKeys.likePattern(" КИНО "))
        assertEquals("%100%", LibraryKeys.likePattern("100%"))
        assertEquals("%a b%", LibraryKeys.likePattern("a_b"))
    }
}
