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
import android.support.v4.media.MediaMetadataCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class PlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var audioEngine: AudioEngine
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentTitle: String = ""

    /**
     * True only when audio was paused due to a transient focus loss (phone call,
     * notification, etc.) — NOT when the user pressed Stop.  Used to decide
     * whether AUDIOFOCUS_GAIN should resume playback automatically.
     */
    private var pausedByFocusLoss = false

    /** Callbacks wired by MainActivity after binding — handle playlist navigation. */
    var skipToNextCallback: (() -> Unit)? = null
    var skipToPreviousCallback: (() -> Unit)? = null

    fun getEngine(): AudioEngine = audioEngine

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine()
        audioEngine.initEngine()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Let MediaButtonReceiver handle hardware media button events
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (currentTitle.isNotEmpty()) showNotification(currentTitle, audioEngine.isPlaying())
        // START_NOT_STICKY: do NOT auto-restart the service if Android kills it.
        return START_NOT_STICKY
    }

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

                override fun onPause() {
                    pausedByFocusLoss = false
                    audioEngine.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    abandonAudioFocus()
                    if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
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

    /**
     * Called when the user explicitly presses Stop.
     */
    fun stopPlayback() {
        pausedByFocusLoss = false
        audioEngine.pause()
        audioEngine.seekTo(0.0)
        abandonAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun playTrack(uri: Uri, context: Context, title: String) {
        currentTitle = title
        pausedByFocusLoss = false
        requestAudioFocus()
        audioEngine.playTrack(uri, context)

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .build()
        mediaSession.setMetadata(metadata)
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        showNotification(title, true)
    }

    fun setPlaybackSpeed(speed: Double) { audioEngine.setSpeed(speed) }
    fun setVolume(volume: Double)        { audioEngine.setVolume(volume) }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            pausedByFocusLoss = false
                            audioEngine.pause()
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (audioEngine.isPlaying()) {
                                pausedByFocusLoss = true
                                audioEngine.pause()
                                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                                if (currentTitle.isNotEmpty()) showNotification(currentTitle, false)
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            audioEngine.setVolume(0.3)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (pausedByFocusLoss) {
                                pausedByFocusLoss = false
                                audioEngine.setVolume(1.0)
                                audioEngine.play()
                                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                                if (currentTitle.isNotEmpty()) showNotification(currentTitle, true)
                            } else {
                                audioEngine.setVolume(1.0)
                            }
                        }
                    }
                }.build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { _ -> },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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

    private fun updatePlaybackState(state: Int) {
        val positionMs = try { audioEngine.getPositionMs().toLong() } catch (_: Exception) { 0L }
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, positionMs, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private val CHANNEL_ID      = "MusicPlayerPro"
    private val NOTIFICATION_ID = 1

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showNotification(title: String, isPlaying: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_PLAY_PAUSE
        )
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this, PlaybackStateCompat.ACTION_STOP
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "MusicPlayerPro" })
            .setContentText("64-bit Hi-Fi Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            // ── action buttons: Previous | Play/Pause | Next ──────────────
            .addAction(
                android.R.drawable.ic_media_previous, "Previous", prevIntent
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else           android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next", nextIntent
            )
            // ── MediaStyle: shows lock-screen / notification shade controls ─
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, next
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // When paused, detach from foreground so the notification is dismissable
        // but keep it visible so the user can resume.
        if (!isPlaying) {
            @Suppress("DEPRECATION")
            stopForeground(false)   // false = don't remove notification
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.shutdownEngine()
        mediaSession.isActive = false
        mediaSession.release()
        abandonAudioFocus()
    }

    override fun onBind(intent: Intent): IBinder = binder
}
