package com.aiproject.musicplayer.playback

import kotlin.math.abs

object PlaybackSpeed {
    const val MIN = 0.75f
    const val MAX = 2.0f
    val PRESETS = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN, MAX) else 1.0f

    fun snapToPreset(value: Float): Float {
        val clamped = clamp(value)
        return PRESETS.minByOrNull { preset -> abs(preset - clamped) } ?: 1.0f
    }
}