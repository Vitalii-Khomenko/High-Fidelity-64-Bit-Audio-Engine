package com.aiproject.musicplayer.playback

/** Commands the UI sends to playback. Implemented by [PlaybackService]. */
interface PlayerCommands {
    fun playIndex(index: Int)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun cycleRepeat()
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun setSpeedMode(mode: SpeedMode)
    fun setEq(eq: EqSettings)
    fun setReplayGain(mode: ReplayGainMode)
    fun setContentMode(mode: ContentMode)
    fun startSleepTimer(durationMs: Long)
    fun cancelSleepTimer()
    fun setQueue(tracks: List<Track>, startIndex: Int = 0, play: Boolean = true)
    fun addTracks(tracks: List<Track>): Int
    fun removeAt(index: Int)
    fun clearQueue()
    fun sortQueue(mode: SortMode)
    fun clearPlayedMarks()
    fun updateDurations(durations: Map<String, Long>)
    fun readSpectrum(bands: FloatArray)

    /**
     * Scans a SAF folder (all subfolders) in the background and adds the tracks,
     * or replaces the queue and plays when [play] is true. Survives the UI going away.
     */
    fun importFolder(treeUri: String, documentId: String?, label: String, play: Boolean)

    /** Adds every playable file MediaStore knows about. */
    fun importDeviceLibrary()
}
