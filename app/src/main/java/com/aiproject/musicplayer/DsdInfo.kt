package com.aiproject.musicplayer

/**
 * DSD (Direct Stream Digital) format helpers.
 * Pure Kotlin — unit-testable on JVM.
 *
 * Standard DSD sample rates (multiples of 44100 Hz):
 *   DSD64  =   64 × 44100 =  2,822,400 Hz
 *   DSD128 =  128 × 44100 =  5,644,800 Hz
 *   DSD256 =  256 × 44100 = 11,289,600 Hz
 *   DSD512 =  512 × 44100 = 22,579,200 Hz
 */
object DsdInfo {

    /**
     * Returns the marketing label for a given native DSD sample rate.
     * Returns null if the rate does not match any known DSD format.
     */
    fun label(nativeRateHz: Int): String? = when {
        nativeRateHz <= 0             -> null
        nativeRateHz >= 22_579_200    -> "DSD512"
        nativeRateHz >= 11_289_600    -> "DSD256"
        nativeRateHz >=  5_644_800    -> "DSD128"
        nativeRateHz >=  2_822_400    -> "DSD64"
        else                          -> null   // not a recognised DSD rate
    }

    /** True if the rate corresponds to any known DSD format. */
    fun isDsd(nativeRateHz: Int): Boolean = label(nativeRateHz) != null
}
