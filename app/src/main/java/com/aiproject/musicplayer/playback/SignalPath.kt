package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.StreamFormat

/** Sample format of the device stream. Ids match hw::SampleEncoding in src/hw/PcmEncoder.h. */
enum class OutputEncoding(val id: Int, val bits: Int) {
    FLOAT(0, 32), PCM_16(1, 16), PCM_24(2, 24), PCM_32(3, 32);

    companion object {
        /** Preferred first: integer formats keep the conversion in our hands (with dither when needed). */
        val PREFERENCE = listOf(PCM_32, PCM_24, FLOAT, PCM_16)
    }
}

/** One bit-perfect format a USB device offers (from AudioMixerAttributes). */
data class DirectCandidate<T>(val sampleRate: Int, val channels: Int, val encoding: OutputEncoding, val handle: T)

object DirectFormatChoice {
    /**
     * The best candidate for a stream at exactly [sampleRate] and [channels]
     * (a rate of 0 means the device takes any rate), or null when the device
     * cannot play it bit-perfect and the shared mixer must be used.
     */
    fun <T> choose(candidates: List<DirectCandidate<T>>, sampleRate: Int, channels: Int): DirectCandidate<T>? =
        candidates
            .filter { (it.sampleRate == sampleRate || it.sampleRate == 0) && it.channels == channels }
            .minByOrNull { OutputEncoding.PREFERENCE.indexOf(it.encoding) }
}

/** What actually reaches the device, for the indicator on the player and in settings. */
data class SignalPath(val kind: Kind, val sampleRate: Int, val bits: Int, val float: Boolean) {
    enum class Kind {
        /** The DAC receives the file's own samples at the file's rate. */
        BIT_PERFECT,
        /** Direct to the DAC at the file's rate, but volume / EQ / gain / ... change the samples. */
        DIRECT_PROCESSED,
        /** Through Android's mixer, which may resample and applies the system volume. */
        MIXED,
    }

    companion object {
        /**
         * [volume] is the engine gain actually applied (setting, ducking, sleep
         * fade); [processedRecently] is true when the engine reported samples it
         * had to round (and dither) within the last moment.
         */
        fun of(format: StreamFormat, settings: PlayerSettings, volume: Double, processedRecently: Boolean): SignalPath? {
            if (format.outputRate <= 0) return null
            if (!format.outputDirect) return SignalPath(Kind.MIXED, format.outputRate, format.outputBits, format.outputFloat)
            val untouched = volume == 1.0 &&
                !settings.eq.enabled &&
                settings.crossfeed == CrossfeedMode.OFF &&
                settings.speed == 1f &&
                format.replayGainDb == 0f &&
                format.dsdRate == 0 &&
                format.outputChannels == format.channels &&
                format.outputRate == format.sampleRate &&
                !processedRecently
            return SignalPath(
                if (untouched) Kind.BIT_PERFECT else Kind.DIRECT_PROCESSED,
                format.outputRate, format.outputBits, format.outputFloat,
            )
        }
    }
}
