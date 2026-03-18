package com.aiproject.musicplayer

import android.content.Context
import android.net.Uri

class AudioEngine {

    companion object {
        init {
            System.loadLibrary("audioengine")
        }
    }

    // Native methods
    external fun initEngine()
    external fun setVolume(volume: Double)
    external fun setEqEnabled(enabled: Boolean)
    external fun loadFileFd(fd: Int): Boolean
    external fun shutdownEngine()

    fun playTrack(uri: Uri, context: Context) {
        try {
            // Android 10+ Scoped Storage requires File Descriptors, not string paths
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

    external fun play()
    external fun pause()
}
