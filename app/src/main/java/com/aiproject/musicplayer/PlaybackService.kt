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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PlaybackService : Service() {

    private val binder       = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val loadMutex    = Mutex()          // serialises concurrent loadTrack calls
    private var loadJob: Job? = null            // cancel previous load on new request

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var audioEngine: AudioEngine
    private var audioFocusRequest: AudioFocusRequest? = null

    /** Currently loaded track title — survives Activity recreation. */
    var currentTitle: String = ""
        private set

    private var pausedByFocusLoss = false

    /** Set by MainActivity after bind — let service drive playlist navigation. */
    var skipToNextCallback:     (() -> Unit)? = null
    var skipToPreviousCallback: (() -> Unit)? = null

    /** Called on Main thread when a track ends naturally (not paused/stopped by user). */
    var onTrackCompleted: (() -> Unit)? = null

    /** Provides the next track URI for gapless pre-loading. Returns null if no next track. */
    var nextTrackProvider: (() -> Uri?)? = null

    /** Called on Main thread when a gapless track advance occurs (no stop/start — just metadata update). */
    var onGaplessAdvanced: (() -> Unit)? = null

    private var gaplessJob: Job? = null
    private var duckJob:    Job? = null

    private var isManualStop  = false
    private var completionJob: Job? = null
    private var fadeJob:       Job? = null

    fun getEngine(): AudioEngine = audioEngine

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine()
        audioEngine.initEngine()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, audioEngine.isPlaying())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        completionJob?.cancel()
        serviceScope.cancel()
        audioEngine.shutdownEngine()
        mediaSession.isActive = false
        mediaSession.release()
        abandonAudioFocus()
    }

    /** Stop playback and service when user swipes app away from recents. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        fadeJob?.cancel(); completionJob?.cancel(); loadJob?.cancel()
        audioEngine.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── MediaSession ─────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerProSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (requestAudioFocus()) {
                        pausedByFocusLoss = false
                        audioEngine.play()
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                        if (currentTitle.isNotEmpty()) showNotification(currentTitle, true)
                    }
                }
                override fun onPause() { pausePlayback() }
                override fun onStop()  { stopPlayback() }
                override fun onSkipToNext()      { skipToNextCallback?.invoke() }
                override fun onSkipToPrevious()  { skipToPreviousCallback?.invoke() }
                override fun onSeekTo(pos: Long) { audioEngine.seekTo(pos.toDouble()) }
            })
            isActive = true
        }
    }

    // ── Public transport ─────────────────────────────────────────────────────

    fun stopPlayback() {
        isManualStop = true
        completionJob?.cancel()
        gaplessJob?.cancel()
        loadJob?.cancel()
        fadeJob?.cancel()
        audioEngine.clearNextTrack()
        fadeJob = serviceScope.launch {
            fadeOut()
            audioEngine.seekTo(0.0)
            abandonAudioFocus()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** Smooth volume ramp to 0, then call audioEngine.pause(). ~200 ms. */
    private suspend fun fadeOut() {
        val steps = 10
        for (step in steps - 1 downTo 0) {
            audioEngine.setVolume(currentVolume * step / steps)
            delay(20)
        }
        audioEngine.pause()
        audioEngine.setVolume(currentVolume) // restore level for next play
    }

    /**
     * Load and play a track.
     * The heavy file-scan (especially MP3 VBR) runs on the IO dispatcher so
     * the main thread is never blocked.  Any in-flight load is cancelled first,
     * but the C++ decoder is serialised via [loadMutex] so only one native
     * load runs at a time.
     */
    fun playTrack(uri: Uri, context: Context, title: String, startPositionMs: Double = 0.0) {
        isManualStop = false
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        // Do NOT cut volume here — abrupt setVolume(0) causes an audible click.
        // The coroutine below fades out the old track smoothly first.
        currentTitle = title
        pausedByFocusLoss = false
        requestAudioFocus()
        // Signal intent immediately — don't wait for fade to finish
        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
        showNotification(title, true)

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            // ── Step 1: Smooth fade-out of the current track (200 ms, 10 × 20 ms) ──
            // Ramps volume currentVolume → 0 without a hard click.
            // GainProcessor's anti-zipper smoothing is fully at 0 by the time we finish.
            for (step in 9 downTo 0) {
                audioEngine.setVolume(currentVolume * step / 10.0)
                delay(20)
            }
            audioEngine.pause()   // engine stops cleanly at silence

            // ── Step 2: Load new decoder on IO thread ────────────────────────────
            loadMutex.withLock {
                withContext(Dispatchers.IO) {
                    audioEngine.playTrack(uri, context)
                }
            }
            // Seek to saved chapter position before fade-in
            if (startPositionMs > 2000.0) {
                audioEngine.seekTo(startPositionMs)
                delay(40)
            }
            // ── Step 3: Smooth fade-in of the new track (300 ms, 15 × 20 ms) ─────
            val fadeSteps = 15
            for (step in 1..fadeSteps) {
                audioEngine.setVolume(currentVolume * step / fadeSteps)
                delay(20)
            }
            val durationMs = try { audioEngine.getDurationMs().toLong() } catch (_: Exception) { 0L }
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(title, true)

            // Monitor for natural track completion
            completionJob = serviceScope.launch {
                delay(800) // wait for playback to actually start
                var wasPlaying = false
                while (true) {
                    val playing = audioEngine.isPlaying()
                    if (playing) wasPlaying = true
                    if (wasPlaying && !playing && !isManualStop) {
                        // Track ended naturally — notify on Main thread
                        onTrackCompleted?.invoke()
                        break
                    }
                    delay(300)
                }
            }

            // Gapless pre-load: start loading next track when ~8 s remain
            gaplessJob?.cancel()
            gaplessJob = serviceScope.launch {
                delay(3000L) // wait for track to settle
                var preloaded = false
                while (!isManualStop) {
                    // Check for gapless advance (C++ switched decoder seamlessly)
                    if (audioEngine.pollGaplessAdvanced()) {
                        onGaplessAdvanced?.invoke()
                        preloaded = false
                        delay(3000L)
                    }
                    // Pre-load next track when ~8 s remain
                    if (!preloaded) {
                        val dur = audioEngine.getDurationMs()
                        val pos = audioEngine.getPositionMs()
                        if (dur > 0 && (dur - pos) in 2000.0..9000.0) {
                            val nextUri = nextTrackProvider?.invoke()
                            if (nextUri != null) {
                                withContext(Dispatchers.IO) {
                                    audioEngine.loadNextTrack(nextUri, this@PlaybackService)
                                }
                                preloaded = true
                            }
                        }
                    }
                    delay(400L)
                }
            }
        }
    }

    private var currentVolume = 1.0

    fun pausePlayback() {
        isManualStop = true
        completionJob?.cancel()
        gaplessJob?.cancel()
        fadeJob?.cancel()
        // Update MediaSession and notification IMMEDIATELY — user intent is clear,
        // no need to wait 200 ms for the fade to finish.
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
        fadeJob = serviceScope.launch {
            fadeOut()
            pausedByFocusLoss = false
            abandonAudioFocus()
        }
    }

    fun resumePlayback() {
        if (currentTitle.isEmpty()) return
        isManualStop = false
        requestAudioFocus()
        // Signal intent immediately
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        showNotification(currentTitle, true)
        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            audioEngine.play()
            for (step in 1..15) {
                audioEngine.setVolume(currentVolume * step / 15.0)
                delay(20)
            }
        }
    }

    fun setPlaybackSpeed(speed: Double) { audioEngine.setSpeed(speed) }
    fun setVolume(volume: Double)        { currentVolume = volume; audioEngine.setVolume(volume) }

    // ── Audio focus ──────────────────────────────────────────────────────────

    private fun requestAudioFocus(): Boolean {
        // Silently drop the old request before creating a new one.
        // Without this, Android fires AUDIOFOCUS_LOSS on the OLD listener when
        // the new AUDIOFOCUS_GAIN request arrives — causing the new track to stop.
        abandonAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)   // we handle ducking ourselves via listener
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            // Permanent loss (voice-message app, call, another player).
                            // Update UI immediately, then fade audio — keeps notification
                            // visible and the service alive as a foreground service.
                            // Keep focus request alive so we get AUDIOFOCUS_GAIN when
                            // the other app finishes — then auto-resume.
                            pausedByFocusLoss = true
                            isManualStop = true
                            completionJob?.cancel()
                            gaplessJob?.cancel()
                            fadeJob?.cancel()
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
                            fadeJob = serviceScope.launch {
                                fadeOut()
                                // Do NOT abandonAudioFocus — we want AUDIOFOCUS_GAIN when they finish
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Transient loss (microphone, navigation, call):
                            // pause quickly so the recording app gets silence immediately,
                            // keep focus request alive so AUDIOFOCUS_GAIN auto-resumes us.
                            if (audioEngine.isPlaying()) {
                                pausedByFocusLoss = true
                                isManualStop = true
                                completionJob?.cancel()
                                gaplessJob?.cancel()
                                fadeJob?.cancel()
                                // 3-step fade (≈60 ms) — stop audio fast so mic gets clean input
                                fadeJob = serviceScope.launch {
                                    for (step in 2 downTo 0) {
                                        audioEngine.setVolume(currentVolume * step / 3.0)
                                        delay(20)
                                    }
                                    audioEngine.pause()
                                    audioEngine.setVolume(currentVolume) // restore for next play
                                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                                    if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
                                    // Do NOT call abandonAudioFocus() — we rely on AUDIOFOCUS_GAIN
                                }
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            // Smooth duck to 20% over 150 ms (notification sound, GPS, etc.)
                            duckJob?.cancel()
                            duckJob = serviceScope.launch {
                                for (step in 10 downTo 2) {
                                    audioEngine.setVolume(currentVolume * step / 10.0 * 0.3)
                                    delay(15)
                                }
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (pausedByFocusLoss) {
                                // Resume playback after transient interruption
                                pausedByFocusLoss = false
                                isManualStop = false
                                fadeJob?.cancel()
                                fadeJob = serviceScope.launch {
                                    audioEngine.setVolume(0.0)
                                    audioEngine.play()
                                    for (step in 1..10) {
                                        audioEngine.setVolume(currentVolume * step / 10.0)
                                        delay(20)
                                    }
                                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                    if (currentTitle.isNotEmpty()) showNotification(currentTitle, true)
                                    // Restart completion monitoring (was cancelled on focus loss)
                                    completionJob?.cancel()
                                    completionJob = serviceScope.launch {
                                        delay(500)
                                        var wasPlaying = false
                                        while (true) {
                                            val playing = audioEngine.isPlaying()
                                            if (playing) wasPlaying = true
                                            if (wasPlaying && !playing && !isManualStop) {
                                                onTrackCompleted?.invoke()
                                                break
                                            }
                                            delay(300)
                                        }
                                    }
                                }
                            } else if (!audioEngine.isPlaying() && !isManualStop && currentTitle.isNotEmpty()) {
                                // Safety net: engine unexpectedly stopped (race/duck edge case)
                                // Resync audio and UI
                                duckJob?.cancel()
                                fadeJob?.cancel()
                                fadeJob = serviceScope.launch {
                                    audioEngine.setVolume(0.0)
                                    audioEngine.play()
                                    for (step in 1..10) {
                                        audioEngine.setVolume(currentVolume * step / 10.0)
                                        delay(15)
                                    }
                                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                    completionJob?.cancel()
                                    completionJob = serviceScope.launch {
                                        delay(500)
                                        var wasPlaying = false
                                        while (true) {
                                            val playing = audioEngine.isPlaying()
                                            if (playing) wasPlaying = true
                                            if (wasPlaying && !playing && !isManualStop) {
                                                onTrackCompleted?.invoke(); break
                                            }
                                            delay(300)
                                        }
                                    }
                                }
                            } else {
                                // Restore from duck — smooth ramp back to full volume
                                duckJob?.cancel()
                                duckJob = serviceScope.launch {
                                    for (step in 3..10) {
                                        audioEngine.setVolume(currentVolume * step / 10.0)
                                        delay(15)
                                    }
                                }
                            }
                        }
                    }
                }.build()
            return audioManager.requestAudioFocus(audioFocusRequest!!) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(
                { _ -> }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
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

    // ── PlaybackState (drives notification progress bar) ─────────────────────

    private fun updatePlaybackState(state: Int) {
        val posMs  = try { audioEngine.getPositionMs().toLong() } catch (_: Exception) { 0L }
        val speed  = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
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
                // Passing updateTime lets Android auto-advance the seekbar without
                // polling — it interpolates position using (now - updateTime) * speed.
                .setState(state, posMs, speed, SystemClock.elapsedRealtime())
                .build()
        )
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private val CHANNEL_ID      = "MusicPlayerPro"
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_STOP)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "HiFi Player" })
            .setContentText("64-bit Hi-Fi Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else           android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()

        // Always keep the service as a foreground service while a track is loaded.
        // Never call stopForeground() here — Xiaomi/MIUI kills background services
        // the moment they lose audio focus.  Only stopPlayback() removes foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
