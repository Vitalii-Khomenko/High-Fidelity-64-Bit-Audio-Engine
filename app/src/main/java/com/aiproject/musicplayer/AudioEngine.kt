package com.aiproject.musicplayer

import android.content.Context
import android.net.Uri
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
) {
    enum class Codec(val label: String) { UNKNOWN(""), FLAC("FLAC"), WAV("WAV"), MP3("MP3"), DSF("DSF"), DFF("DFF"), AIFF("AIFF") }
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

    fun init() = nativeInit()
    fun release() = nativeRelease()

    /** Opens [uri] (content:// or file://) and makes it current, paused at 0. */
    fun load(context: Context, uri: Uri, replayGain: ReplayGainMode): Boolean {
        val fd = openFd(context, uri) ?: return false
        return nativeLoad(fd, replayGain.id)
    }

    /** Queues [uri] for a gapless transition after the current track. */
    fun loadNext(context: Context, uri: Uri, replayGain: ReplayGainMode): Boolean {
        val fd = openFd(context, uri) ?: return false
        return nativeLoadNext(fd, replayGain.id)
    }

    fun clearNext() = nativeClearNext()
    fun play(): Boolean = nativePlay()
    fun pause() = nativePause()
    fun stop() = nativeStop()
    fun seekTo(positionMs: Long) = nativeSeekTo(positionMs.toDouble())
    fun setVolume(volume: Double) = nativeSetVolume(volume)
    fun setSpeed(speed: Double) = nativeSetSpeed(speed)
    fun setSpeedMode(mode: Int) = nativeSetSpeedMode(mode)
    fun setEqEnabled(enabled: Boolean) = nativeSetEqEnabled(enabled)
    fun setEqBand(band: Int, gainDb: Double) = nativeSetEqBand(band, gainDb)

    fun state(): State = State.entries.getOrElse(nativeGetState()) { State.ERROR }
    fun positionMs(): Long = nativeGetPositionMs().toLong()
    fun durationMs(): Long = nativeGetDurationMs().toLong()

    /** True once after the audible track switched gaplessly to the queued one. */
    fun consumeTrackAdvanced(): Boolean = nativeConsumeTrackAdvanced()

    fun format(): StreamFormat? {
        val v = IntArray(10)
        nativeGetTrackInfo(v)
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
        )
    }

    /** Fills [bands] (up to 64) with 0..1 log-spaced levels of what is being heard. */
    fun spectrum(bands: FloatArray) = nativeGetSpectrum(bands)

    private fun openFd(context: Context, uri: Uri): Int? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()
    } catch (_: Exception) {
        null
    }

    private external fun nativeInit()
    private external fun nativeRelease()
    private external fun nativeLoad(fd: Int, replayGainMode: Int): Boolean
    private external fun nativeLoadNext(fd: Int, replayGainMode: Int): Boolean
    private external fun nativeClearNext()
    private external fun nativePlay(): Boolean
    private external fun nativePause()
    private external fun nativeStop()
    private external fun nativeSeekTo(positionMs: Double)
    private external fun nativeSetVolume(volume: Double)
    private external fun nativeSetSpeed(speed: Double)
    private external fun nativeSetSpeedMode(mode: Int)
    private external fun nativeSetEqEnabled(enabled: Boolean)
    private external fun nativeSetEqBand(band: Int, gainDb: Double)
    private external fun nativeGetState(): Int
    private external fun nativeGetPositionMs(): Double
    private external fun nativeGetDurationMs(): Double
    private external fun nativeConsumeTrackAdvanced(): Boolean
    private external fun nativeGetTrackInfo(out: IntArray)
    private external fun nativeGetSpectrum(out: FloatArray)

    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }
}
