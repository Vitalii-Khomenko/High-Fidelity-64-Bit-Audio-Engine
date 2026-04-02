package com.aiproject.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class QueuedTrack(val uri: Uri, val title: String)

class PlaybackService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val loadMutex = Mutex()
    private var loadJob: Job? = null

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var audioEngine: AudioEngine
    private val engineInitMutex = Mutex()
    private var engineInitJob: Deferred<Boolean>? = null
    @Volatile
    private var isAudioEngineReady = false
    @Volatile
    private var engineInitFailed = false
    private var audioFocusRequest: AudioFocusRequest? = null

    var currentTitle: String = ""
        private set

    var currentTrackUri: String = ""
        private set

    private var pausedByFocusLoss = false

    var skipToNextCallback: (() -> Unit)? = null
    var skipToPreviousCallback: (() -> Unit)? = null
    var onTrackCompleted: (() -> Unit)? = null
    var nextTrackProvider: (() -> QueuedTrack?)? = null
    var onGaplessAdvanced: (() -> Unit)? = null

    private var gaplessJob: Job? = null
    private var duckJob: Job? = null
    private var pendingGaplessTrack: QueuedTrack? = null
    private var duckedVolume = 0.0

    private var isManualStop = false
    private var completionJob: Job? = null
    private var fadeJob: Job? = null
    private var currentVolume = 1.0

    private val playbackStateMachine = PlaybackStateMachine(
        doFadeOutAndPause = { handleUserPause() },
        doFastFadeOutAndPause = { handleFocusPause(fast = true) },
        doFadeInAndPlay = { handleFocusGainResume() },
        doDuck = { level -> handleDuck(level) },
        doRestoreVolume = { restoreFromDuck() },
        doAbandonFocus = { abandonAudioFocus() },
        isEnginePlayingNow = { isAudioEngineReady && audioEngine.isPlaying() },
        hasCurrentTrack = { currentTitle.isNotEmpty() }
    )

    fun getEngine(): AudioEngine? = audioEngine.takeIf { isAudioEngineReady }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createNotificationChannel()
        beginAudioEngineInitialization()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, getEngine()?.isPlaying() == true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fadeJob?.cancel()
        duckJob?.cancel()
        completionJob?.cancel()
        gaplessJob?.cancel()
        loadJob?.cancel()
        serviceScope.cancel()
        try { mediaSession.isActive = false } catch (_: Exception) {}
        try { mediaSession.release() } catch (_: Exception) {}
        abandonAudioFocus()
        if (isAudioEngineReady) {
            audioEngine.shutdownEngine()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        playbackStateMachine.onUserStop()
        syncStateFromMachine()
        fadeJob?.cancel()
        completionJob?.cancel()
        gaplessJob?.cancel()
        loadJob?.cancel()
        if (isAudioEngineReady) {
            audioEngine.clearNextTrack()
        }
        pendingGaplessTrack = null
        if (isAudioEngineReady) {
            audioEngine.pause()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun beginAudioEngineInitialization() {
        if (isAudioEngineReady || engineInitFailed) return
        val existingJob = engineInitJob
        if (existingJob != null && !existingJob.isCancelled) return
        engineInitJob = serviceScope.async(Dispatchers.Default) {
            initializeAudioEngineIfNeeded()
        }
    }

    private suspend fun initializeAudioEngineIfNeeded(): Boolean {
        if (isAudioEngineReady) return true
        if (engineInitFailed) return false
        return engineInitMutex.withLock {
            if (isAudioEngineReady) return@withLock true
            if (engineInitFailed) return@withLock false
            try {
                audioEngine.initEngine()
                isAudioEngineReady = true
                true
            } catch (_: Throwable) {
                engineInitFailed = true
                false
            }
        }
    }

    private suspend fun awaitAudioEngineReady(): Boolean {
        if (isAudioEngineReady) return true
        if (engineInitFailed) return false
        beginAudioEngineInitialization()
        return try {
            engineInitJob?.await() ?: initializeAudioEngineIfNeeded()
        } catch (_: Throwable) {
            engineInitFailed = true
            false
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerProSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (currentTitle.isEmpty()) return
                    playbackStateMachine.onPlayTrackStarted()
                    syncStateFromMachine()
                    if (requestAudioFocus()) {
                        handleFocusGainResume()
                    } else {
                        playbackStateMachine.onUserStop()
                        syncStateFromMachine()
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                        showNotification(currentTitle, false)
                    }
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopPlayback()
                }

                override fun onSkipToNext() {
                    skipToNextCallback?.invoke()
                }

                override fun onSkipToPrevious() {
                    skipToPreviousCallback?.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    audioEngine.seekTo(pos.toDouble())
                }
            })
            isActive = true
        }
    }

    fun stopPlayback() {
        playbackStateMachine.onUserStop()
        syncStateFromMachine()
        completionJob?.cancel()
        gaplessJob?.cancel()
        loadJob?.cancel()
        fadeJob?.cancel()
        audioEngine.clearNextTrack()
        pendingGaplessTrack = null
        fadeJob = serviceScope.launch {
            if (awaitAudioEngineReady()) {
                fadeOutAndPause(PlaybackTransitions.STOP_FADE_MS)
                audioEngine.seekTo(0.0)
            }
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun fadeOutAndPause(fadeMs: Long) {
        audioEngine.setVolume(0.0)
        if (fadeMs > 0L) delay(fadeMs)
        audioEngine.forceSilence()
        audioEngine.pause()
        audioEngine.setVolume(currentVolume)
    }

    private fun syncStateFromMachine() {
        pausedByFocusLoss = playbackStateMachine.pausedByFocus
        isManualStop = playbackStateMachine.isManualStop
    }

    private fun handleFocusPause(fast: Boolean) {
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
        fadeJob = serviceScope.launch {
            fadeOutAndPause(PlaybackTransitions.focusPauseFadeMs(fast))
        }
    }

    private fun handleUserPause() {
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
        fadeJob = serviceScope.launch {
            fadeOutAndPause(PlaybackTransitions.USER_PAUSE_FADE_MS)
        }
    }

    private fun restartCompletionMonitor(initialDelayMs: Long = 500L) {
        completionJob?.cancel()
        completionJob = serviceScope.launch {
            delay(initialDelayMs)
            var wasPlaying = false
            while (true) {
                val playing = audioEngine.isPlaying()
                if (playing) wasPlaying = true
                if (wasPlaying && !playing && playbackStateMachine.shouldFireCompletion()) {
                    onTrackCompleted?.invoke()
                    break
                }
                delay(300L)
            }
        }
    }

    private fun handleFocusGainResume() {
        duckJob?.cancel()
        fadeJob?.cancel()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, true)
        fadeJob = serviceScope.launch {
            audioEngine.forceSilence()
            audioEngine.play()
            delay(PlaybackTransitions.RESUME_PREROLL_MS)
            audioEngine.clearBufferedAudio()
            audioEngine.setVolume(currentVolume)
            restartCompletionMonitor(initialDelayMs = 500L)
        }
    }

    private fun handleDuck(targetLevel: Double) {
        duckJob?.cancel()
        val targetVolume = (currentVolume * targetLevel).coerceAtLeast(0.0)
        duckJob = serviceScope.launch {
            duckedVolume = targetVolume
            audioEngine.setVolume(targetVolume)
        }
    }

    private fun restoreFromDuck() {
        duckJob?.cancel()
        duckJob = serviceScope.launch {
            if (duckedVolume > 0.0) {
                audioEngine.setVolume(currentVolume)
            }
            duckedVolume = 0.0
        }
    }

    fun playTrack(uri: Uri, context: Context, title: String, startPositionMs: Double = 0.0) {
        isManualStop = true
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        loadJob?.cancel()
        pendingGaplessTrack = null

        loadJob = serviceScope.launch {
            if (!awaitAudioEngineReady()) {
                currentTitle = ""
                currentTrackUri = ""
                playbackStateMachine.onUserStop()
                syncStateFromMachine()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            if (audioEngine.isPlaying()) {
                audioEngine.setVolume(0.0)
                delay(PlaybackTransitions.SWITCH_FADE_MS)
            }
            audioEngine.pause()
            audioEngine.forceSilence()

            playbackStateMachine.onPlayTrackStarted()
            syncStateFromMachine()
            currentTitle = title
            currentTrackUri = uri.toString()
            if (!requestAudioFocus()) {
                audioEngine.setVolume(currentVolume)
                playbackStateMachine.onUserStop()
                syncStateFromMachine()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
            showNotification(title, true)

            val loaded = loadMutex.withLock {
                withContext(Dispatchers.IO) {
                    audioEngine.forceSilence()
                    audioEngine.playTrack(uri, context)
                }
            }
            if (!loaded) {
                audioEngine.setVolume(currentVolume)
                currentTitle = ""
                currentTrackUri = ""
                playbackStateMachine.onUserStop()
                syncStateFromMachine()
                abandonAudioFocus()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }

            if (startPositionMs > 2000.0) {
                audioEngine.seekTo(startPositionMs)
                delay(40L)
            }

            delay(PlaybackTransitions.START_PREROLL_MS)
            audioEngine.clearBufferedAudio()
            audioEngine.setVolume(currentVolume)

            val durationMs = try { audioEngine.getDurationMs().toLong() } catch (_: Exception) { 0L }
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(title, true)
            restartCompletionMonitor(initialDelayMs = 800L)

            gaplessJob?.cancel()
            gaplessJob = serviceScope.launch {
                delay(3000L)
                var preloaded = false
                while (!isManualStop) {
                    if (audioEngine.pollGaplessAdvanced()) {
                        pendingGaplessTrack?.let { track ->
                            currentTitle = track.title
                            currentTrackUri = track.uri.toString()
                            val nextDurationMs = try { audioEngine.getDurationMs().toLong() } catch (_: Exception) { 0L }
                            mediaSession.setMetadata(
                                MediaMetadataCompat.Builder()
                                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nextDurationMs)
                                    .build()
                            )
                            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                            showNotification(track.title, true)
                        }
                        pendingGaplessTrack = null
                        onGaplessAdvanced?.invoke()
                        preloaded = false
                        delay(3000L)
                    }

                    if (!preloaded) {
                        val duration = audioEngine.getDurationMs()
                        val position = audioEngine.getPositionMs()
                        if (duration > 0 && (duration - position) in 2000.0..9000.0) {
                            val nextTrack = nextTrackProvider?.invoke()
                            if (nextTrack != null) {
                                withContext(Dispatchers.IO) {
                                    if (audioEngine.loadNextTrack(nextTrack.uri, this@PlaybackService)) {
                                        pendingGaplessTrack = nextTrack
                                        preloaded = true
                                    }
                                }
                            }
                        }
                    }
                    delay(400L)
                }
            }
        }
    }

    fun pausePlayback() {
        playbackStateMachine.onUserPause()
        syncStateFromMachine()
    }

    fun resumePlayback() {
        if (currentTitle.isEmpty()) return
        playbackStateMachine.onPlayTrackStarted()
        syncStateFromMachine()
        if (!requestAudioFocus()) {
            playbackStateMachine.onUserStop()
            syncStateFromMachine()
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            showNotification(currentTitle, false)
            return
        }
        handleFocusGainResume()
    }

    fun setPlaybackSpeed(speed: Double) {
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.setSpeed(speed)
            }
        }
    }

    fun setPlaybackSpeedMode(mode: PlaybackSpeedMode) {
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.setSpeedMode(mode.id)
            }
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.setEqEnabled(enabled)
            }
        }
    }

    fun setEqBandGain(bandIndex: Int, gainDb: Double) {
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.setEqBandGain(bandIndex, gainDb)
            }
        }
    }

    fun resetEqBands() {
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.resetEqBands()
            }
        }
    }

    fun setVolume(volume: Double) {
        currentVolume = volume
        serviceScope.launch {
            if (awaitAudioEngineReady()) {
                audioEngine.setVolume(volume)
            }
        }
    }

    fun pauseForSleepTimer() {
        playbackStateMachine.onUserStop()
        syncStateFromMachine()
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            fadeOutAndPause(PlaybackTransitions.SLEEP_TIMER_FINAL_FADE_MS)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
        }
    }

    private fun requestAudioFocus(): Boolean {
        playbackStateMachine.beforeRequestFocus()
        syncStateFromMachine()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    playbackStateMachine.onAudioFocusChange(change)
                    syncStateFromMachine()
                }
                .build()
            return audioManager.requestAudioFocus(audioFocusRequest!!) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        @Suppress("DEPRECATION")
        return audioManager.requestAudioFocus(
            { _ -> },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun updatePlaybackState(state: Int) {
        if (!mediaSession.isActive) return
        val positionMs = if (isAudioEngineReady) {
            try { audioEngine.getPositionMs().toLong() } catch (_: Exception) { 0L }
        } else {
            0L
        }
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
        try {
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(state, positionMs, speed, SystemClock.elapsedRealtime())
                    .build()
            )
        } catch (_: IllegalStateException) {
        }
    }

    private val CHANNEL_ID = "MusicPlayerPro"
    private val NOTIFICATION_ID = 1

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    fun showNotification(title: String, isPlaying: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_STOP
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "HiFi Player" })
            .setContentText("64-bit Hi-Fi Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
