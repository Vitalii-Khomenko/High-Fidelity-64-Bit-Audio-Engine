package com.aiproject.musicplayer

/**
 * Downloads the engine can play while they are still running (DLNA
 * streaming). The downloader reports progress; decoders reading past it wait.
 */
object NativeStreams {
    init {
        System.loadLibrary("audioengine")
    }

    /** Registers a download of [totalBytes]; returns its id. */
    @JvmStatic external fun create(totalBytes: Long): Long

    /** status: 0 downloading, 1 complete, 2 failed. */
    @JvmStatic external fun progress(id: Long, availableBytes: Long, status: Int)

    @JvmStatic external fun release(id: Long)
}
