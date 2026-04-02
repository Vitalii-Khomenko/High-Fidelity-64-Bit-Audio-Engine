package com.aiproject.musicplayer

/**
 * Pure-Kotlin fade math — generates volume step sequences.
 * No Android imports — fully unit-testable on the JVM.
 */
object VolumeRamp {

    /**
     * Returns a list of [steps] volume values ramping from near-0 up to [targetVolume].
     * The first value is targetVolume/steps, the last is targetVolume.
     */
    fun fadeIn(steps: Int, targetVolume: Double): List<Double> {
        require(steps > 0) { "steps must be > 0" }
        require(targetVolume in 0.0..1.0) { "targetVolume must be in [0, 1]" }
        return (1..steps).map { targetVolume * it / steps }
    }

    /**
     * Returns a list of [steps] volume values ramping from [currentVolume] down to 0.
     * The first value is currentVolume * (steps-1)/steps, the last is 0.
     */
    fun fadeOut(steps: Int, currentVolume: Double): List<Double> {
        require(steps > 0) { "steps must be > 0" }
        require(currentVolume in 0.0..1.0) { "currentVolume must be in [0, 1]" }
        return (steps - 1 downTo 0).map { currentVolume * it / steps }
    }

    /**
     * Returns a single duck level: [baseVolume] * [duckFactor].
     * [duckFactor] is typically 0.2 (80% reduction for notifications).
     */
    fun duckLevel(baseVolume: Double, duckFactor: Double = 0.2): Double =
        (baseVolume * duckFactor).coerceIn(0.0, 1.0)
}
