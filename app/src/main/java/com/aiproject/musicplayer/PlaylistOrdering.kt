package com.aiproject.musicplayer

import java.util.Locale

object PlaylistOrdering {
    private val numberRegex = Regex("\\d+")
    private val leadingNumericPrefixRegex = Regex("^(?:\\d+\\s*)+")

    fun sortTracks(tracks: List<AudioTrack>, mode: PlaylistSortMode): List<AudioTrack> {
        if (tracks.size < 2) return tracks
        return when (mode) {
            PlaylistSortMode.NAME -> tracks.sortedWith(
                compareBy<AudioTrack> { naturalSortKey(it.name) }
                    .thenBy { it.folder.lowercase(Locale.ROOT) }
                    .thenBy { it.uri.toString() }
            )
            PlaylistSortMode.NUMBER -> tracks.sortedWith(
                compareBy<AudioTrack> { numericSortKey(it.name) }
                    .thenBy { naturalSortKey(it.name) }
                    .thenBy { it.folder.lowercase(Locale.ROOT) }
                    .thenBy { it.uri.toString() }
            )
        }
    }

    data class NameSortKey(
        val tokens: List<NaturalToken>,
        val fallback: String,
    ) : Comparable<NameSortKey> {
        override fun compareTo(other: NameSortKey): Int {
            val limit = minOf(tokens.size, other.tokens.size)
            for (index in 0 until limit) {
                val result = tokens[index].compareTo(other.tokens[index])
                if (result != 0) return result
            }
            if (tokens.size != other.tokens.size) return tokens.size.compareTo(other.tokens.size)
            return fallback.compareTo(other.fallback)
        }
    }

    data class NumericSortKey(
        val hasNumbers: Boolean,
        val numbers: List<Long>,
        val fallback: String,
    ) : Comparable<NumericSortKey> {
        override fun compareTo(other: NumericSortKey): Int {
            if (hasNumbers != other.hasNumbers) return if (hasNumbers) -1 else 1
            val limit = minOf(numbers.size, other.numbers.size)
            for (index in 0 until limit) {
                val result = numbers[index].compareTo(other.numbers[index])
                if (result != 0) return result
            }
            if (numbers.size != other.numbers.size) return numbers.size.compareTo(other.numbers.size)
            return fallback.compareTo(other.fallback)
        }
    }

    sealed interface NaturalToken : Comparable<NaturalToken> {
        data class Text(val value: String) : NaturalToken {
            override fun compareTo(other: NaturalToken): Int = when (other) {
                is Text -> value.compareTo(other.value)
                is Number -> 1
            }
        }

        data class Number(val value: Long) : NaturalToken {
            override fun compareTo(other: NaturalToken): Int = when (other) {
                is Number -> value.compareTo(other.value)
                is Text -> -1
            }
        }
    }

    fun naturalSortKey(name: String): NameSortKey {
        val normalized = normalizeNameForAlphabeticalSort(name)
        val tokens = mutableListOf<NaturalToken>()
        var lastIndex = 0
        numberRegex.findAll(normalized).forEach { match ->
            if (match.range.first > lastIndex) {
                val text = normalized.substring(lastIndex, match.range.first)
                if (text.isNotEmpty()) tokens += NaturalToken.Text(text)
            }
            tokens += NaturalToken.Number(match.value.toLongOrNull() ?: Long.MAX_VALUE)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < normalized.length) {
            val text = normalized.substring(lastIndex)
            if (text.isNotEmpty()) tokens += NaturalToken.Text(text)
        }
        return NameSortKey(tokens = tokens, fallback = normalized)
    }

    fun numericSortKey(name: String): NumericSortKey {
        val normalized = normalizeName(name)
        val numbers = numberRegex.findAll(normalized)
            .mapNotNull { it.value.toLongOrNull() }
            .toList()
        return NumericSortKey(
            hasNumbers = numbers.isNotEmpty(),
            numbers = numbers,
            fallback = normalized,
        )
    }

    private fun normalizeName(name: String): String {
        val withoutExtension = name.substringBeforeLast('.', name)
        return withoutExtension
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeNameForAlphabeticalSort(name: String): String {
        val normalized = normalizeName(name)
        val withoutPrefix = normalized.replace(leadingNumericPrefixRegex, "").trim()
        return withoutPrefix.ifBlank { normalized }
    }
}