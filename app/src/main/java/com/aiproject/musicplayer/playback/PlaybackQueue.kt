package com.aiproject.musicplayer.playback

import kotlin.random.Random

/**
 * The play queue: tracks, the current position and the play order.
 *
 * Shuffle is a permutation of track indices ([order]) walked by [orderPos], so
 * "next", "previous" and the gapless pre-load always agree on what comes next.
 * Without shuffle the order is the identity. Pure Kotlin, unit-tested on the JVM.
 */
class PlaybackQueue(private val random: Random = Random.Default) {

    var tracks: List<Track> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var shuffle: Boolean = false
        private set
    var repeat: RepeatMode = RepeatMode.OFF

    private var order: MutableList<Int> = mutableListOf()
    private var orderPos: Int = -1
    // Next shuffled cycle, fixed once peeked so peek and advance agree.
    private var nextCycle: List<Int>? = null

    val current: Track? get() = tracks.getOrNull(currentIndex)
    val size: Int get() = tracks.size
    val totalDurationMs: Long get() = tracks.sumOf { it.durationMs.coerceAtLeast(0L) }

    /** Play order as track indices (for persistence and tests). */
    fun playOrder(): List<Int> = order.toList()

    fun replace(newTracks: List<Track>, startIndex: Int = -1) {
        tracks = newTracks.distinctBy { it.uri }
        currentIndex = if (startIndex in tracks.indices) startIndex else -1
        rebuildOrder()
    }

    /** Restores persisted state; an invalid order falls back to a fresh one. */
    fun restore(newTracks: List<Track>, index: Int, shuffled: Boolean, savedOrder: List<Int>) {
        tracks = newTracks.distinctBy { it.uri }
        currentIndex = if (index in tracks.indices) index else -1
        shuffle = shuffled
        val valid = savedOrder.size == tracks.size && savedOrder.toSet() == tracks.indices.toSet()
        if (shuffled && valid) {
            order = savedOrder.toMutableList()
            orderPos = if (currentIndex >= 0) order.indexOf(currentIndex) else -1
            nextCycle = null
        } else {
            rebuildOrder()
        }
    }

    /** Appends tracks not already queued. Returns how many were added. */
    fun append(incoming: List<Track>): Int {
        val known = tracks.mapTo(HashSet()) { it.uri }
        val added = incoming.filter { known.add(it.uri) }
        if (added.isEmpty()) return 0
        val firstNew = tracks.size
        tracks = tracks + added
        val newIndices = (firstNew until tracks.size).toList()
        if (shuffle) {
            // New tracks join the not-yet-played part of the shuffle at random spots.
            for (i in newIndices) {
                val from = (orderPos + 1).coerceAtLeast(0)
                order.add(from + random.nextInt(order.size - from + 1), i)
            }
        } else {
            order.addAll(newIndices)
        }
        nextCycle = null
        return added.size
    }

    /** Removes a track. Returns true if it was the current one (which becomes none). */
    fun removeAt(index: Int): Boolean {
        if (index !in tracks.indices) return false
        val wasCurrent = index == currentIndex
        tracks = tracks.toMutableList().also { it.removeAt(index) }
        val removedPos = order.indexOf(index)
        order.removeAt(removedPos)
        for (i in order.indices) if (order[i] > index) order[i] = order[i] - 1
        currentIndex = when {
            wasCurrent -> -1
            currentIndex > index -> currentIndex - 1
            else -> currentIndex
        }
        orderPos = when {
            wasCurrent -> removedPos - 1      // "next" continues where the removed track was
            removedPos < orderPos -> orderPos - 1
            else -> orderPos
        }
        nextCycle = null
        return wasCurrent
    }

    fun move(from: Int, to: Int) {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        val mapping = tracks.indices.toMutableList().also { it.add(to, it.removeAt(from)) }
        reorder(mapping)
    }

    fun clear() {
        tracks = emptyList()
        currentIndex = -1
        order.clear()
        orderPos = -1
        nextCycle = null
    }

    /** Reorders tracks with [comparator], keeping the current track and shuffle order. */
    fun sort(comparator: Comparator<Track>) {
        val mapping = tracks.indices.sortedWith { a, b -> comparator.compare(tracks[a], tracks[b]) }
        reorder(mapping)
    }

    fun setShuffle(enabled: Boolean) {
        if (shuffle == enabled) return
        shuffle = enabled
        rebuildOrder()
    }

    /** Makes [index] current. In shuffle it is played now and the unplayed rest is kept. */
    fun jumpTo(index: Int) {
        if (index !in tracks.indices) return
        if (shuffle) {
            val pos = order.indexOf(index)
            order.removeAt(pos)
            val insertAt = if (pos <= orderPos) orderPos else orderPos + 1
            order.add(insertAt, index)
            orderPos = insertAt
        } else {
            orderPos = index
        }
        currentIndex = index
        nextCycle = null
    }

    /**
     * Index that follows the current track, or -1.
     * [auto] = the track ended by itself: repeat-one repeats it; a user "next" moves on.
     */
    fun peekNext(auto: Boolean): Int {
        if (tracks.isEmpty()) return -1
        if (currentIndex < 0) return if (orderPos + 1 in order.indices) order[orderPos + 1] else order[0]
        if (auto && repeat == RepeatMode.ONE) return currentIndex
        if (orderPos + 1 < order.size) return order[orderPos + 1]
        if (repeat == RepeatMode.OFF) return -1
        return wrapOrder().first()
    }

    /** Moves to [peekNext] and returns it (or -1, leaving the queue unchanged). */
    fun advance(auto: Boolean): Int {
        if (tracks.isEmpty()) return -1
        when {
            currentIndex < 0 -> orderPos = if (orderPos + 1 in order.indices) orderPos + 1 else 0
            auto && repeat == RepeatMode.ONE -> return currentIndex
            orderPos + 1 < order.size -> orderPos += 1
            repeat == RepeatMode.OFF -> return -1
            else -> {
                order = wrapOrder().toMutableList()
                orderPos = 0
            }
        }
        nextCycle = null
        currentIndex = order[orderPos]
        return currentIndex
    }

    /** Index before the current one in play order (wrapping with repeat-all), or -1. */
    fun peekPrevious(): Int {
        if (tracks.isEmpty() || currentIndex < 0) return -1
        if (orderPos > 0) return order[orderPos - 1]
        return if (repeat == RepeatMode.ALL && !shuffle) order.last() else -1
    }

    fun retreat(): Int {
        val previous = peekPrevious()
        if (previous < 0) return -1
        orderPos = if (orderPos > 0) orderPos - 1 else order.lastIndex
        currentIndex = previous
        nextCycle = null
        return previous
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun wrapOrder(): List<Int> {
        if (!shuffle) return tracks.indices.toList()
        nextCycle?.let { return it }
        // A fresh cycle that does not start with the track that just played.
        var cycle = tracks.indices.shuffled(random)
        if (cycle.size > 1 && cycle.first() == currentIndex) {
            cycle = cycle.drop(1) + cycle.first()
        }
        nextCycle = cycle
        return cycle
    }

    private fun rebuildOrder() {
        nextCycle = null
        if (!shuffle) {
            order = tracks.indices.toMutableList()
            orderPos = currentIndex
            return
        }
        val rest = tracks.indices.filter { it != currentIndex }.shuffled(random)
        if (currentIndex >= 0) {
            order = (listOf(currentIndex) + rest).toMutableList()
            orderPos = 0
        } else {
            order = rest.toMutableList()
            orderPos = -1
        }
    }

    /** mapping[newIndex] = oldIndex */
    private fun reorder(mapping: List<Int>) {
        val oldToNew = IntArray(mapping.size)
        mapping.forEachIndexed { newIndex, oldIndex -> oldToNew[oldIndex] = newIndex }
        tracks = mapping.map { tracks[it] }
        currentIndex = if (currentIndex >= 0) oldToNew[currentIndex] else -1
        if (shuffle) {
            order = order.map { oldToNew[it] }.toMutableList()
        } else {
            order = tracks.indices.toMutableList()
            orderPos = currentIndex
        }
        nextCycle = null
    }
}
