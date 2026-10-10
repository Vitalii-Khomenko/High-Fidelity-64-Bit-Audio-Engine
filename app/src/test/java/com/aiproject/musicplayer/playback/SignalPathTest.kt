package com.aiproject.musicplayer.playback

import com.aiproject.musicplayer.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalPathTest {

    // ── Choosing the bit-perfect format ───────────────────────────────────────

    private fun c(rate: Int, encoding: OutputEncoding, channels: Int = 2) = DirectCandidate(rate, channels, encoding, "$rate/$encoding/$channels")

    private val dac = listOf(
        c(44_100, OutputEncoding.PCM_16), c(44_100, OutputEncoding.PCM_24), c(44_100, OutputEncoding.PCM_32),
        c(96_000, OutputEncoding.PCM_16), c(96_000, OutputEncoding.PCM_24),
        c(48_000, OutputEncoding.FLOAT), c(48_000, OutputEncoding.PCM_16),
    )

    @Test fun `picks the widest integer format at the exact rate`() {
        assertEquals(OutputEncoding.PCM_32, DirectFormatChoice.choose(dac, 44_100, 2)?.encoding)
        assertEquals(OutputEncoding.PCM_24, DirectFormatChoice.choose(dac, 96_000, 2)?.encoding)
    }

    @Test fun `float is preferred over 16-bit only`() =
        assertEquals(OutputEncoding.FLOAT, DirectFormatChoice.choose(dac, 48_000, 2)?.encoding)

    @Test fun `no exact rate means the shared mixer`() {
        assertNull(DirectFormatChoice.choose(dac, 88_200, 2))
        assertNull(DirectFormatChoice.choose(dac, 192_000, 2))
    }

    @Test fun `channel count must match`() {
        assertNull(DirectFormatChoice.choose(dac, 44_100, 1))
        assertNull(DirectFormatChoice.choose(dac, 44_100, 6))
    }

    @Test fun `a rate of zero accepts any rate`() =
        assertEquals("0/PCM_24/2", DirectFormatChoice.choose(listOf(c(0, OutputEncoding.PCM_24)), 352_800, 2)?.handle)

    @Test fun `encoding ids match the native SampleEncoding`() =
        assertEquals(listOf(0, 1, 2, 3), listOf(OutputEncoding.FLOAT, OutputEncoding.PCM_16, OutputEncoding.PCM_24, OutputEncoding.PCM_32).map { it.id })

    // ── The indicator ─────────────────────────────────────────────────────────

    private fun format(
        rate: Int = 96_000, direct: Boolean = true, bits: Int = 24, gainDb: Float = 0f, dsdRate: Int = 0,
        channels: Int = 2, outputChannels: Int = 2,
    ) = StreamFormat(
        sampleRate = rate, channels = channels, bitsPerSample = 24, dsdRate = dsdRate, codec = StreamFormat.Codec.FLAC,
        outputRate = rate, outputChannels = outputChannels, replayGainDb = gainDb, underruns = 0,
        outputDirect = direct, outputBits = bits, outputFloat = false,
    )

    private val flat = PlayerSettings()

    private fun kind(f: StreamFormat, s: PlayerSettings = flat, volume: Double = 1.0, processed: Boolean = false) =
        SignalPath.of(f, s, volume, processed)?.kind

    @Test fun `direct and untouched is bit-perfect`() {
        val path = SignalPath.of(format(), flat, 1.0, false)
        assertEquals(SignalPath(SignalPath.Kind.BIT_PERFECT, 96_000, 24, false), path)
        assertEquals("BIT-PERFECT 96 kHz / 24-bit", FormatText.path(path!!))
    }

    @Test fun `shared stream is mixed whatever the settings`() =
        assertEquals(SignalPath.Kind.MIXED, kind(format(direct = false)))

    @Test fun `anything that changes samples makes it processed`() {
        val processed = SignalPath.Kind.DIRECT_PROCESSED
        assertEquals(processed, kind(format(), volume = 0.5))
        assertEquals(processed, kind(format(gainDb = -6.5f)))
        assertEquals(processed, kind(format(), flat.copy(eq = flat.eq.copy(enabled = true))))
        assertEquals(processed, kind(format(), flat.copy(crossfeed = CrossfeedMode.DEFAULT)))
        assertEquals(processed, kind(format(), flat.copy(speed = 1.25f)))
        assertEquals(processed, kind(format(dsdRate = 2_822_400)))
        assertEquals(processed, kind(format(channels = 6, outputChannels = 2)))
        // The limiter catching a peak, or dither after a 24 → 16-bit cut, shows up as rounded samples.
        assertEquals(processed, kind(format(), processed = true))
    }

    @Test fun `no output means no path`() =
        assertNull(SignalPath.of(format().copy(outputRate = 0), flat, 1.0, false))

    @Test fun `output text names the route`() {
        assertEquals("Out 96 kHz 24-bit · direct", FormatText.output(format()))
        assertEquals("Out 44.1 kHz float · mixer", FormatText.output(format(rate = 44_100, direct = false).copy(outputFloat = true)))
        val converted = format(rate = 44_100, direct = false).copy(outputRate = 48_000, outputFloat = true)
        assertEquals("Out 48 kHz float · 64-bit SRC · mixer", FormatText.output(converted))
        assertEquals("MIXER 48 kHz", FormatText.path(SignalPath.of(converted, flat, 1.0, false)!!))
    }
}
