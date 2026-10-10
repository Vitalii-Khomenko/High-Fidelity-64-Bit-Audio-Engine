package com.aiproject.musicplayer

import android.content.Context
import android.net.Uri
import com.aiproject.musicplayer.library.Playable
import com.aiproject.musicplayer.playback.CrossfeedMode
import com.aiproject.musicplayer.playback.EqSettings
import com.aiproject.musicplayer.playback.PlayableUri
import com.aiproject.musicplayer.playback.ReplayGainMode

/** Format of the audible track as reported by the native engine. */
data class StreamFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dsdRate: Int,
    val codec: Codec,
    val outputRate: Int,
    val outputChannels: Int,
    val replayGainDb: Float,
    val underruns: Int,
    /** The device stream bypasses the system mixer (bit-perfect mixer attributes). */
    val outputDirect: Boolean = false,
    val outputBits: Int = 32,
    val outputFloat: Boolean = true,
    /** Wrapping count of output samples the engine had to round (processed); compare successive values. */
    val processedSamples: Int = 0,
) {
    /** Same order as decoders::Codec in src/decoders/IAudioDecoder.h. */
    enum class Codec(val label: String, val lossy: Boolean = false) {
        UNKNOWN(""), FLAC("FLAC"), WAV("WAV"), MP3("MP3", true), DSF("DSF"), DFF("DFF"), AIFF("AIFF"),
        AAC("AAC", true), ALAC("ALAC"), VORBIS("VORBIS", true), OPUS("OPUS", true), WAVPACK("WAVPACK"),
        APE("APE"), TTA("TTA"), OTHER(""),
    }
}

/**
 * Asked by the engine before it opens a device stream (on an engine thread,
 * with the output locked: never call back into the engine). Returns the
 * OutputEncoding id the stream must use for bit-perfect output, or -1 for the
 * shared mixer. The native side calls [encodingFor] by name.
 */
fun interface DirectOutputPolicy {
    fun encodingFor(sampleRate: Int, channels: Int): Int
}

/**
 * Thin JNI wrapper around the native AudioPlayer (src/core/AudioPlayer.h).
 *
 * Every method may be called from any thread. Queries never block on decoding;
 * load(), play(), pause() and stop() can take tens of milliseconds (stream
 * reconfiguration, fades) and should not run on the main thread.
 */
class AudioEngine {

    enum class State { IDLE, PAUSED, PLAYING, ENDED, ERROR }

    // Native instance owned by this object; 0 after release(). Calls on a
    // released engine are ignored natively.
    @Volatile
    private var id: Long = nativeCreate()

    fun release() {
        val old = id
        id = 0L
        nativeRelease(old)
    }

    /**
     * Opens [uri] (content:// or file://, optionally a CUE range, see
     * [PlayableUri]) and makes it current, paused at 0.
     */
    fun load(context: Context, uri: Uri, replayGain: ReplayGainMode, measuredGain: DoubleArray? = null, stream: Playable? = null): Boolean {
        val parts = PlayableUri.split(uri.toString())
        val (fd, streamId) = open(context, Uri.parse(parts.fileUri), stream) ?: return false
        return nativeLoad(id, fd, replayGain.id, parts.startUs, parts.endUs, measuredGain, streamId)
    }

    /** Queues [uri] for a gapless transition after the current track. */
    fun loadNext(context: Context, uri: Uri, replayGain: ReplayGainMode, measuredGain: DoubleArray? = null, stream: Playable? = null): Boolean {
        val parts = PlayableUri.split(uri.toString())
        val (fd, streamId) = open(context, Uri.parse(parts.fileUri), stream) ?: return false
        return nativeLoadNext(id, fd, replayGain.id, parts.startUs, parts.endUs, measuredGain, streamId)
    }

    /**
     * A descriptor for [uri]. A download still in progress is opened with its
     * stream id; if it finished (and was renamed) meanwhile, its final file.
     */
    private fun open(context: Context, uri: Uri, stream: Playable?): Pair<Int, Long>? {
        if (stream != null && stream.streamId != 0L) {
            openFd(context, stream.uri)?.let { return it to stream.streamId }
            stream.completeUri?.let { complete -> openFd(context, complete)?.let { return it to 0L } }
            return null
        }
        return openFd(context, uri)?.let { it to 0L }
    }

    fun clearNext() = nativeClearNext(id)
    fun play(): Boolean = nativePlay(id)
    fun pause() = nativePause(id)
    fun stop() = nativeStop(id)
    fun seekTo(positionMs: Long) = nativeSeekTo(id, positionMs.toDouble())
    fun setVolume(volume: Double) = nativeSetVolume(id, volume)
    fun setSpeed(speed: Double) = nativeSetSpeed(id, speed)
    fun setSpeedMode(mode: Int) = nativeSetSpeedMode(id, mode)
    fun setEq(eq: EqSettings) {
        val bands = eq.engineBands()
        val flat = DoubleArray(bands.size * 4)
        bands.forEachIndexed { i, b ->
            flat[i * 4] = b.filter.id.toDouble()
            flat[i * 4 + 1] = b.frequency
            flat[i * 4 + 2] = b.q
            flat[i * 4 + 3] = b.gainDb
        }
        nativeSetEq(id, eq.enabled, eq.enginePreampDb(), flat)
    }
    fun setCrossfeed(mode: CrossfeedMode) = nativeSetCrossfeed(id, mode.id)
    fun setLimiter(enabled: Boolean) = nativeSetLimiter(id, enabled)

    /** Direct output policy (null: shared mixer only). Reopens a running stream with it. */
    fun setDirectOutput(policy: DirectOutputPolicy?) = nativeSetDirectOutput(id, policy)

    /** Reopens the device stream, asking the policy again, without losing the position. */
    fun reopenOutput() = nativeReopenOutput(id)

    fun state(): State = State.entries.getOrElse(nativeGetState(id)) { State.ERROR }
    fun positionMs(): Long = nativeGetPositionMs(id).toLong()
    fun durationMs(): Long = nativeGetDurationMs(id).toLong()

    /** True once after the audible track switched gaplessly to the queued one. */
    fun consumeTrackAdvanced(): Boolean = nativeConsumeTrackAdvanced(id)

    fun format(): StreamFormat? {
        val v = IntArray(14)
        nativeGetTrackInfo(id, v)
        if (v[0] == 0) return null
        return StreamFormat(
            sampleRate = v[0],
            channels = v[1],
            bitsPerSample = v[2],
            dsdRate = v[3],
            codec = StreamFormat.Codec.entries.getOrElse(v[4]) { StreamFormat.Codec.UNKNOWN },
            outputRate = v[5],
            outputChannels = v[6],
            replayGainDb = v[7] / 100f,
            underruns = v[9],
            outputDirect = v[10] != 0,
            outputBits = v[11],
            outputFloat = v[12] != 0,
            processedSamples = v[13],
        )
    }

    /** Fills [bands] (up to 64) with 0..1 log-spaced levels of what is being heard. */
    fun spectrum(bands: FloatArray) = nativeGetSpectrum(id, bands)

    private fun openFd(context: Context, uri: Uri): Int? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (_: Exception) {
        null
    }

    private external fun nativeCreate(): Long
    private external fun nativeRelease(id: Long)
    private external fun nativeLoad(id: Long, fd: Int, replayGainMode: Int, startUs: Long, endUs: Long, fallbackGain: DoubleArray?, streamId: Long): Boolean
    private external fun nativeLoadNext(id: Long, fd: Int, replayGainMode: Int, startUs: Long, endUs: Long, fallbackGain: DoubleArray?, streamId: Long): Boolean
    private external fun nativeClearNext(id: Long)
    private external fun nativePlay(id: Long): Boolean
    private external fun nativePause(id: Long)
    private external fun nativeStop(id: Long)
    private external fun nativeSeekTo(id: Long, positionMs: Double)
    private external fun nativeSetVolume(id: Long, volume: Double)
    private external fun nativeSetSpeed(id: Long, speed: Double)
    private external fun nativeSetSpeedMode(id: Long, mode: Int)
    private external fun nativeSetEq(id: Long, enabled: Boolean, preampDb: Double, bands: DoubleArray)
    private external fun nativeSetCrossfeed(id: Long, preset: Int)
    private external fun nativeSetLimiter(id: Long, enabled: Boolean)
    private external fun nativeSetDirectOutput(id: Long, policy: DirectOutputPolicy?)
    private external fun nativeReopenOutput(id: Long)
    private external fun nativeGetState(id: Long): Int
    private external fun nativeGetPositionMs(id: Long): Double
    private external fun nativeGetDurationMs(id: Long): Double
    private external fun nativeConsumeTrackAdvanced(id: Long): Boolean
    private external fun nativeGetTrackInfo(id: Long, out: IntArray)
    private external fun nativeGetSpectrum(id: Long, out: FloatArray)

    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }
}
