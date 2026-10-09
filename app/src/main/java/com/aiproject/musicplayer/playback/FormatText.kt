package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.StreamFormat
import java.util.Locale

/** Human-readable descriptions of the stream format. */
object FormatText {
    fun rate(hz: Int): String {
        if (hz <= 0) return ""
        val khz = hz / 1000.0
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    }

    /** "FLAC · 96 kHz · 24-bit", "DSD128 · DSF", "MP3 · 44.1 kHz". */
    fun short(format: StreamFormat): String = chips(format).joinToString(" · ")

    fun chips(format: StreamFormat): List<String> {
        val dsd = DsdInfo.label(format.dsdRate)
        return buildList {
            if (dsd != null) {
                add(dsd)
                add(format.codec.label)
            } else {
                if (format.codec.label.isNotEmpty()) add(format.codec.label)
                add(rate(format.sampleRate))
                if (format.bitsPerSample > 1) add("${format.bitsPerSample}-bit")
            }
            if (format.channels > 2) add("${format.channels} ch")
        }.filter { it.isNotEmpty() }
    }

    /** What the device receives, e.g. "Out 88.2 kHz float" or "Out 48 kHz · stereo mix". */
    fun output(format: StreamFormat): String {
        if (format.outputRate <= 0) return ""
        val mix = if (format.outputChannels in 1 until format.channels) " · stereo mix" else ""
        return "Out ${rate(format.outputRate)} float$mix"
    }

    fun replayGain(db: Float): String =
        if (db == 0f) "" else String.format(Locale.US, "RG %+.1f dB", db)
}
