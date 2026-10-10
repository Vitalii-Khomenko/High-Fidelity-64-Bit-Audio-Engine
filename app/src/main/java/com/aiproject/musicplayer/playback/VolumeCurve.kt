package com.aiproject.musicplayer.playback

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The app's volume control is a position (0..1) on a decibel scale: equal
 * slider travel is an equal change in loudness, from 0 dB at the top down to
 * −60 dB, and silence at the very bottom. A linear gain would crowd almost the
 * whole audible range into the bottom tenth of the slider, which matters once
 * the app's volume is the only one (direct and own-driver USB output).
 */
object VolumeCurve {
    const val RANGE_DB = 60.0
    /** Volume-key steps over the whole range: 2 dB each. */
    const val STEPS = 30

    /** Linear gain for a position; exactly 1.0 at the top (bit-perfect stays possible). */
    fun gain(position: Float): Double = when {
        position >= 1f -> 1.0
        position <= 0f -> 0.0
        else -> 10.0.pow(db(position) / 20.0)
    }

    fun db(position: Float): Double = RANGE_DB * (position.toDouble() - 1.0)

    /** The position giving a linear gain (settings saved before the curve). */
    fun positionOf(gain: Double): Float = when {
        gain >= 1.0 -> 1f
        gain <= 0.0 -> 0f
        else -> (1.0 + 20.0 * log10(gain) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
    }

    fun step(position: Float): Int = (position * STEPS).roundToInt().coerceIn(0, STEPS)

    fun label(position: Float): String = when {
        position <= 0f -> "Mute"
        position >= 1f -> "0 dB"
        else -> String.format(Locale.US, "−%.1f dB", -db(position))
    }
}
