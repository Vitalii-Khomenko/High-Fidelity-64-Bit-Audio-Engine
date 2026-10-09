package com.aiproject.musicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueTest {

    private fun tracks(n: Int) = List(n) { Track(uri = "content://t/$it", title = "Track $it") }

    private fun queue(n: Int, start: Int = 0, seed: Int = 7) =
        PlaybackQueue(Random(seed)).apply { replace(tracks(n), start) }

    @Test fun `linear order stops at the end without repeat`() {
        val q = queue(3)
        assertEquals(1, q.advance(auto = true))
        assertEquals(2, q.advance(auto = true))
        assertEquals(-1, q.peekNext(auto = true))
        assertEquals(-1, q.advance(auto = true))
        assertEquals(2, q.currentIndex)
    }

    @Test fun `repeat all wraps and repeat one repeats only on auto advance`() {
        val q = queue(3, start = 2)
        q.repeat = RepeatMode.ALL
        assertEquals(0, q.advance(auto = true))
        q.repeat = RepeatMode.ONE
        assertEquals(0, q.peekNext(auto = true))
        assertEquals(0, q.advance(auto = true))
        assertEquals(1, q.advance(auto = false))  // the user's "next" still moves on
    }

    @Test fun `previous walks back and wraps only with repeat all`() {
        val q = queue(3, start = 0)
        assertEquals(-1, q.retreat())
        q.repeat = RepeatMode.ALL
        assertEquals(2, q.retreat())
        assertEquals(1, q.retreat())
    }

    @Test fun `shuffle plays every track once per cycle starting with the current one`() {
        val q = queue(20, start = 5)
        q.setShuffle(true)
        val seen = mutableListOf(q.currentIndex)
        while (true) {
            val next = q.advance(auto = true)
            if (next < 0) break
            seen += next
        }
        assertEquals(5, seen.first())
        assertEquals((0 until 20).toSet(), seen.toSet())
        assertEquals(20, seen.size)
    }

    @Test fun `peek and advance agree, including across a shuffled wrap`() {
        val q = queue(6, start = 0)
        q.setShuffle(true)
        q.repeat = RepeatMode.ALL
        repeat(40) {
            val peeked = q.peekNext(auto = true)
            assertEquals(peeked, q.peekNext(auto = true))
            assertEquals(peeked, q.advance(auto = true))
        }
    }

    @Test fun `shuffle back retraces the order`() {
        val q = queue(10, start = 3)
        q.setShuffle(true)
        val forward = listOf(q.currentIndex) + List(4) { q.advance(auto = false) }
        val backward = List(4) { q.retreat() }
        assertEquals(forward.dropLast(1).reversed(), backward)
    }

    @Test fun `jumping in shuffle keeps the unplayed tracks ahead`() {
        val q = queue(8, start = 0)
        q.setShuffle(true)
        val target = q.playOrder().last()
        q.jumpTo(target)
        assertEquals(target, q.currentIndex)
        val rest = mutableSetOf<Int>()
        while (true) { val n = q.advance(auto = true); if (n < 0) break; rest += n }
        assertEquals((0 until 8).toSet() - setOf(0, target), rest)
    }

    @Test fun `append skips duplicates and joins the shuffle ahead of the current track`() {
        val q = queue(4, start = 1)
        q.setShuffle(true)
        q.advance(auto = false)
        val added = q.append(tracks(6))  // 4 duplicates, 2 new
        assertEquals(2, added)
        assertEquals(6, q.size)
        val order = q.playOrder()
        val pos = order.indexOf(q.currentIndex)
        assertTrue(order.indexOf(4) > pos && order.indexOf(5) > pos)
        assertEquals((0 until 6).toSet(), order.toSet())
    }

    @Test fun `removing the current track continues with the one after it`() {
        val q = queue(4, start = 1)
        assertTrue(q.removeAt(1))
        assertEquals(-1, q.currentIndex)
        assertEquals("content://t/2", q.tracks[q.peekNext(auto = true)].uri)
        assertEquals(1, q.advance(auto = true))
        assertEquals("content://t/2", q.current?.uri)
    }

    @Test fun `removing another track keeps the current one`() {
        val q = queue(4, start = 2)
        assertFalse(q.removeAt(0))
        assertEquals("content://t/2", q.current?.uri)
        assertEquals(1, q.currentIndex)
        assertEquals("content://t/3", q.tracks[q.advance(auto = true)].uri)
    }

    @Test fun `sorting keeps the current track and shuffle order`() {
        val q = PlaybackQueue(Random(3)).apply {
            replace(listOf(Track("c", "C"), Track("a", "A"), Track("b", "B")), startIndex = 0)
        }
        q.sort(compareBy { it.title })
        assertEquals(listOf("A", "B", "C"), q.tracks.map { it.title })
        assertEquals("c", q.current?.uri)
        assertEquals(-1, q.advance(auto = true))
        q.setShuffle(true)
        val before = q.playOrder().map { q.tracks[it].uri }
        q.sort(compareByDescending { it.title })
        assertEquals(before, q.playOrder().map { q.tracks[it].uri })
    }

    @Test fun `restore accepts a valid shuffle order and rejects a stale one`() {
        val q = PlaybackQueue(Random(1))
        q.restore(tracks(4), index = 2, shuffled = true, savedOrder = listOf(3, 2, 0, 1))
        assertEquals(listOf(3, 2, 0, 1), q.playOrder())
        assertEquals(0, q.advance(auto = true))
        q.restore(tracks(4), index = 2, shuffled = true, savedOrder = listOf(9, 2))
        assertEquals((0 until 4).toSet(), q.playOrder().toSet())
        assertEquals(2, q.playOrder().first())
    }

    @Test fun `empty queue is inert`() {
        val q = PlaybackQueue()
        assertEquals(-1, q.peekNext(auto = true))
        assertEquals(-1, q.advance(auto = false))
        assertEquals(-1, q.retreat())
        q.setShuffle(true)
        q.jumpTo(3)
        assertEquals(-1, q.currentIndex)
    }

    @Test fun `fresh queue starts at the first track of the order`() {
        val q = PlaybackQueue().apply { replace(tracks(3)) }
        assertEquals(0, q.peekNext(auto = false))
        assertEquals(0, q.advance(auto = false))
    }

    @Test fun `move reorders without losing the current track`() {
        val q = queue(4, start = 1)
        q.move(from = 3, to = 0)
        assertEquals(listOf("content://t/3", "content://t/0", "content://t/1", "content://t/2"), q.tracks.map { it.uri })
        assertEquals("content://t/1", q.current?.uri)
    }

    @Test fun `time formatting`() {
        assertEquals("0:07", TimeFormat.clock(7_400))
        assertEquals("1:02:03", TimeFormat.clock(3_723_000))
        assertEquals("3m 05s", TimeFormat.span(185_000))
        assertEquals("", TimeFormat.span(0))
    }
}
