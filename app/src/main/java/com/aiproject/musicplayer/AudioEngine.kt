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
    external fun setEqEnabled(enabled: Boolean)
    external fun loadFileFd(fd: Int): Boolean
    external fun shutdownEngine()
    external fun play()
    external fun pause()
    external fun seekTo(positionMs: Double)
    external fun setSpeed(speed: Double)
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

    fun playTrack(uri: Uri, context: Context) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val fd = pfd?.detachFd() ?: -1
            if (fd != -1) {
                if (loadFileFd(fd)) {
                    play()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
