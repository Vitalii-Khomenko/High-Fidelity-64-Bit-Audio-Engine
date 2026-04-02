package com.aiproject.musicplayer

import android.content.Context
import android.net.Uri

class AudioEngine {

    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }

    external fun initEngine()
    external fun setVolume(volume: Double)
    external fun forceSilence()
    external fun clearBufferedAudio()
    external fun setEqEnabled(enabled: Boolean)
    external fun setEqBandGain(bandIndex: Int, gainDb: Double)
    external fun resetEqBands()
    external fun loadFileFd(fd: Int): Boolean
    external fun shutdownEngine()
    external fun play()
    external fun pause()
    external fun seekTo(positionMs: Double)
    external fun setSpeed(speed: Double)
    external fun setSpeedMode(mode: Int)
    external fun isPlaying(): Boolean
    external fun getDurationMs(): Double
    external fun getPositionMs(): Double
    external fun getSampleRateNative(): Int
    external fun getBitsPerSample(): Int
    external fun loadNextFileFd(fd: Int): Boolean
    external fun clearNextTrack()
    external fun pollGaplessAdvanced(): Boolean
    external fun getSpectrum(bands: FloatArray)
    external fun getReplayGainDb(): Float
    external fun getDsdNativeRate(): Int

    fun playTrack(uri: Uri, context: Context): Boolean {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fd = pfd?.detachFd() ?: return false
            if (loadFileFd(fd)) {
                play()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadNextTrack(uri: Uri, context: Context): Boolean {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fd = pfd?.detachFd() ?: return false
            loadNextFileFd(fd)
        } catch (e: Exception) { false }
    }
}
