package com.aiproject.musicplayer

import kotlin.random.Random

object PlaybackShuffle {
    fun buildQueue(
        currentIndex: Int,
        playlistSize: Int,
        random: Random = Random.Default,
    ): List<Int> {
        if (currentIndex !in 0 until playlistSize || playlistSize <= 1) return emptyList()
        return (0 until playlistSize)
            .filter { it != currentIndex }
            .shuffled(random)
    }

    fun sanitizeQueue(
        queue: List<Int>,
        currentIndex: Int,
        playlistSize: Int,
    ): List<Int> {
        return queue
            .filter { it in 0 until playlistSize && it != currentIndex }
            .distinct()
    }

    fun previewNextIndex(
        currentIndex: Int,
        playlistSize: Int,
        repeatMode: Int,
        queue: List<Int>,
        random: Random = Random.Default,
    ): Int {
        if (currentIndex !in 0 until playlistSize) return -1
        if (repeatMode == 1) return currentIndex
        val sanitizedQueue = sanitizeQueue(queue, currentIndex, playlistSize)
        if (sanitizedQueue.isNotEmpty()) return sanitizedQueue.first()
        if (playlistSize <= 1) {
            return if (repeatMode == 2) currentIndex else -1
        }
        if (repeatMode == 2) {
            return buildQueue(currentIndex, playlistSize, random).firstOrNull() ?: -1
        }
        return -1
    }

    fun queueAfterAdvance(
        currentIndex: Int,
        nextIndex: Int,
        playlistSize: Int,
        repeatMode: Int,
        queue: List<Int>,
        random: Random = Random.Default,
    ): List<Int> {
        if (nextIndex !in 0 until playlistSize) return emptyList()
        if (repeatMode == 1) {
            return sanitizeQueue(queue, currentIndex, playlistSize)
        }
        val sanitizedQueue = sanitizeQueue(queue, currentIndex, playlistSize)
        val remaining = if (sanitizedQueue.firstOrNull() == nextIndex) {
            sanitizedQueue.drop(1)
        } else {
            sanitizeQueue(queue, nextIndex, playlistSize)
        }
        if (remaining.isNotEmpty()) return remaining
        return if (repeatMode == 2) {
            buildQueue(nextIndex, playlistSize, random)
        } else {
            emptyList()
        }
    }

    fun nextIndex(
        currentIndex: Int,
        playlistSize: Int,
        repeatMode: Int,
        queuedNextIndex: Int = -1,
        random: Random = Random.Default,
    ): Int {
        if (currentIndex !in 0 until playlistSize) return -1
        if (repeatMode == 1) return currentIndex
        if (queuedNextIndex in 0 until playlistSize && queuedNextIndex != currentIndex) {
            return queuedNextIndex
        }
        if (playlistSize <= 1) {
            return if (repeatMode == 2) currentIndex else -1
        }
        val candidates = (0 until playlistSize).filter { it != currentIndex }
        return candidates[random.nextInt(candidates.size)]
    }

    fun previousIndex(history: List<Int>): Int = history.lastOrNull() ?: -1

    fun pushHistory(history: List<Int>, currentIndex: Int, maxSize: Int = 200): List<Int> {
        if (currentIndex < 0) return history
        return (history + currentIndex).takeLast(maxSize)
    }

    fun popHistory(history: List<Int>): Pair<Int, List<Int>> {
        if (history.isEmpty()) return -1 to history
        return history.last() to history.dropLast(1)
    }
}