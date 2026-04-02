package com.aiproject.musicplayer

import java.util.Locale

data class EqSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = List(EqDefaults.BANDS.size) { 0f },
) {
    fun normalized(): EqSettings {
        val normalizedGains = List(EqDefaults.BANDS.size) { index ->
            bandGainsDb.getOrNull(index)?.coerceIn(EqDefaults.MIN_GAIN_DB, EqDefaults.MAX_GAIN_DB) ?: 0f
        }
        return copy(bandGainsDb = normalizedGains)
    }

    fun withBandGain(index: Int, gainDb: Float): EqSettings {
        if (index !in EqDefaults.BANDS.indices) return this
        val updated = bandGainsDb.toMutableList()
        updated[index] = gainDb.coerceIn(EqDefaults.MIN_GAIN_DB, EqDefaults.MAX_GAIN_DB)
        return copy(bandGainsDb = updated)
    }

    fun serialize(): String = normalized().bandGainsDb.joinToString(",") { value -> String.format(Locale.US, "%.2f", value) }

    companion object {
        fun deserialize(enabled: Boolean, serialized: String?): EqSettings {
            val gains = serialized
                ?.split(',')
                ?.mapNotNull { token -> token.toFloatOrNull() }
                .orEmpty()
            return EqSettings(enabled = enabled, bandGainsDb = gains).normalized()
        }
    }
}

data class EqBand(val label: String, val frequencyLabel: String)

object EqDefaults {
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f

    val BANDS = listOf(
        EqBand(label = "Sub", frequencyLabel = "60 Hz"),
        EqBand(label = "Bass", frequencyLabel = "230 Hz"),
        EqBand(label = "Mid", frequencyLabel = "910 Hz"),
        EqBand(label = "Presence", frequencyLabel = "3.6 kHz"),
        EqBand(label = "Air", frequencyLabel = "14 kHz"),
    )
}