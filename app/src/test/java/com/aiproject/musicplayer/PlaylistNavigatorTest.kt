package com.aiproject.musicplayer

import org.junit.Assert.*
import org.junit.Test

class PlaylistNavigatorTest {

    private fun nav(size: Int) = PlaylistNavigator { size }

    // ── Repeat OFF ────────────────────────────────────────────────────────────

    @Test fun `repeat-off - next from middle advances by 1`() {
        val n = nav(5).also { it.jumpTo(2) }
        assertEquals(3, n.nextIndex())
    }

    @Test fun `repeat-off - next from last track returns -1 (stop)`() {
        val n = nav(5).also { it.jumpTo(4) }
        assertEquals(-1, n.nextIndex())
    }

    @Test fun `repeat-off - advance returns false at last track`() {
        val n = nav(3).also { it.jumpTo(2) }
        assertFalse("Should stop at end", n.advance())
        assertEquals("Index must stay at last", 2, n.currentIndex)
    }

    @Test fun `repeat-off - advance returns true in middle`() {
        val n = nav(3).also { it.jumpTo(0) }
        assertTrue(n.advance())
        assertEquals(1, n.currentIndex)
    }

    // ── Repeat ALL ────────────────────────────────────────────────────────────

    @Test fun `repeat-all - next from last track wraps to 0`() {
        val n = nav(5).also { it.jumpTo(4); it.repeatMode = 2 }
        assertEquals(0, n.nextIndex())
    }

    @Test fun `repeat-all - advance always returns true`() {
        val n = nav(3).also { it.jumpTo(2); it.repeatMode = 2 }
        assertTrue("Should wrap", n.advance())
        assertEquals(0, n.currentIndex)
    }

    @Test fun `repeat-all - wraps single-track playlist`() {
        val n = nav(1).also { it.repeatMode = 2 }
        assertEquals(0, n.nextIndex())
    }

    // ── Repeat ONE ────────────────────────────────────────────────────────────

    @Test fun `repeat-one - next always returns currentIndex`() {
        val n = nav(5).also { it.jumpTo(3); it.repeatMode = 1 }
        assertEquals(3, n.nextIndex())
    }

    @Test fun `repeat-one - advance stays on same track`() {
        val n = nav(5).also { it.jumpTo(2); it.repeatMode = 1 }
        assertTrue(n.advance())
        assertEquals("Index must not change", 2, n.currentIndex)
    }

    // ── Previous ──────────────────────────────────────────────────────────────

    @Test fun `prev from middle goes back`() {
        val n = nav(5).also { it.jumpTo(3) }
        assertEquals(2, n.prevIndex())
    }

    @Test fun `prev from first track stays at 0`() {
        val n = nav(5).also { it.jumpTo(0) }
        assertEquals("Should not go below 0", 0, n.prevIndex())
    }

    @Test fun `goBack updates currentIndex`() {
        val n = nav(5).also { it.jumpTo(3) }
        n.goBack()
        assertEquals(2, n.currentIndex)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test fun `empty playlist returns -1 for next and prev`() {
        val n = nav(0)
        assertEquals(-1, n.nextIndex())
        assertEquals(-1, n.prevIndex())
    }

    @Test fun `jumpTo clamps to valid range`() {
        val n = nav(5)
        n.jumpTo(100)
        assertEquals(4, n.currentIndex)
        n.jumpTo(-5)
        assertEquals(0, n.currentIndex)
    }

    @Test fun `repeatMode setter clamps to 0-2`() {
        val n = nav(3)
        n.repeatMode = 99
        assertEquals(2, n.repeatMode)
        n.repeatMode = -1
        assertEquals(0, n.repeatMode)
    }

    @Test fun `changing repeatMode mid-playback takes effect immediately`() {
        val n = nav(3).also { it.jumpTo(2) }
        // At end, repeat-off → stop
        assertEquals(-1, n.nextIndex())
        // Switch to repeat-all
        n.repeatMode = 2
        assertEquals(0, n.nextIndex())
    }

    @Test fun `full playlist run with repeat-off stops at end`() {
        val n = nav(3)
        assertTrue(n.advance())  // 0→1
        assertTrue(n.advance())  // 1→2
        assertFalse(n.advance()) // 2→stop
    }

    @Test fun `full playlist run with repeat-all loops`() {
        val n = nav(3).also { it.repeatMode = 2 }
        assertTrue(n.advance())  // 0→1
        assertTrue(n.advance())  // 1→2
        assertTrue(n.advance())  // 2→0 (wrap)
        assertEquals(0, n.currentIndex)
    }
}
