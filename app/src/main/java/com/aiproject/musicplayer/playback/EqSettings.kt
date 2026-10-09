package com.aiproject.musicplayer.playback

import java.util.Locale

/** Filter of a parametric EQ band; ids match dsp::EqBandType in src/dsp/ParametricEq.h. */
enum class EqFilter(val id: Int, val label: String) {
    PEAK(0, "PK"), LOW_SHELF(1, "LS"), HIGH_SHELF(2, "HS"), LOW_PASS(3, "LP"), HIGH_PASS(4, "HP");

    companion object {
        fun fromId(id: Int): EqFilter = entries.firstOrNull { it.id == id } ?: PEAK
    }
}

data class EqBandSpec(val filter: EqFilter, val frequency: Double, val q: Double, val gainDb: Double)

/** A parametric profile, e.g. an AutoEQ headphone correction. */
data class EqProfile(val name: String, val preampDb: Double, val bands: List<EqBandSpec>) {
    /** Line format: name, preamp, then "filter frequency q gain" per band (pure JVM, no org.json). */
    fun serialize(): String = buildString {
        append(name.replace('\n', ' ')).append('\n')
        append(String.format(Locale.US, "%.3f", preampDb))
        bands.forEach { b -> append('\n').append(String.format(Locale.US, "%d %.4f %.5f %.3f", b.filter.id, b.frequency, b.q, b.gainDb)) }
    }

    companion object {
        const val MAX_BANDS = 20

        fun deserialize(text: String?): EqProfile? {
            if (text.isNullOrBlank()) return null
            val lines = text.split('\n')
            if (lines.size < 2) return null
            val preamp = lines[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            val bands = lines.drop(2).mapNotNull { line ->
                val f = line.trim().split(' ')
                if (f.size != 4) return@mapNotNull null
                val type = f[0].toIntOrNull() ?: return@mapNotNull null
                val freq = f[1].toDoubleOrNull() ?: return@mapNotNull null
                val q = f[2].toDoubleOrNull() ?: return@mapNotNull null
                val gain = f[3].toDoubleOrNull() ?: return@mapNotNull null
                EqBandSpec(EqFilter.fromId(type), freq, q, gain).takeIf { freq.isFinite() && q.isFinite() && gain.isFinite() }
            }.take(MAX_BANDS)
            return EqProfile(lines[0], preamp, bands)
        }
    }
}

enum class EqMode(val id: Int) {
    GRAPHIC(0), PARAMETRIC(1);

    companion object {
        fun fromId(id: Int): EqMode = entries.firstOrNull { it.id == id } ?: GRAPHIC
    }
}

data class EqSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Float> = List(EqDefaults.BANDS.size) { 0f },
    val mode: EqMode = EqMode.GRAPHIC,
    val profile: EqProfile? = null,
) {
    /** What the engine runs: the five graphic bands, or the loaded profile. */
    fun engineBands(): List<EqBandSpec> =
        if (mode == EqMode.PARAMETRIC && profile != null) profile.bands
        else EqDefaults.BANDS.mapIndexed { i, b ->
            EqBandSpec(b.filter, b.frequency, b.q, normalized().bandGainsDb[i].toDouble())
        }

    fun enginePreampDb(): Double = if (mode == EqMode.PARAMETRIC && profile != null) profile.preampDb else 0.0

    fun normalized(): EqSettings {
        val normalizedGains = List(EqDefaults.BANDS.size) { index ->
            bandGainsDb.getOrNull(index)?.takeIf { it.isFinite() }?.coerceIn(EqDefaults.MIN_GAIN_DB, EqDefaults.MAX_GAIN_DB) ?: 0f
        }
        return copy(bandGainsDb = normalizedGains)
    }

    fun withBandGain(index: Int, gainDb: Float): EqSettings {
        if (index !in EqDefaults.BANDS.indices) return this
        val updated = normalized().bandGainsDb.toMutableList()
        updated[index] = if (gainDb.isFinite()) gainDb.coerceIn(EqDefaults.MIN_GAIN_DB, EqDefaults.MAX_GAIN_DB) else 0f
        return copy(bandGainsDb = updated)
    }

    fun serialize(): String = normalized().bandGainsDb.joinToString(",") { value -> String.format(Locale.US, "%.2f", value) }

    companion object {
        fun deserialize(enabled: Boolean, serialized: String?, mode: Int = 0, profile: String? = null): EqSettings {
            val gains = serialized
                ?.split(',')
                ?.mapNotNull { token -> token.toFloatOrNull() }
                .orEmpty()
            return EqSettings(enabled = enabled, bandGainsDb = gains, mode = EqMode.fromId(mode), profile = EqProfile.deserialize(profile)).normalized()
        }
    }
}

data class EqBand(val label: String, val frequencyLabel: String, val filter: EqFilter, val frequency: Double, val q: Double)

object EqDefaults {
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f

    val BANDS = listOf(
        EqBand("Sub", "60 Hz", EqFilter.LOW_SHELF, 60.0, 0.707),
        EqBand("Bass", "230 Hz", EqFilter.PEAK, 230.0, 0.9),
        EqBand("Mid", "910 Hz", EqFilter.PEAK, 910.0, 0.9),
        EqBand("Presence", "3.6 kHz", EqFilter.PEAK, 3600.0, 0.9),
        EqBand("Air", "14 kHz", EqFilter.HIGH_SHELF, 14000.0, 0.707),
    )
}