package com.aiproject.musicplayer

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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat

class PlaybackService : Service() {
    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var audioEngine: AudioEngine
    private var audioFocusRequest: AudioFocusRequest? = null

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
        showNotification("Ready")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerProSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (requestAudioFocus()) {
                        audioEngine.play()
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    }
                }

                override fun onPause() {
                    audioEngine.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }

                override fun onStop() {
                    audioEngine.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    abandonAudioFocus()
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    audioEngine.seekTo(pos.toDouble())
                }
            })
            isActive = true
        }
    }

    fun playTrack(uri: Uri, context: Context, title: String) {
        if (requestAudioFocus()) {
            audioEngine.playTrack(uri, context)

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .build()
            mediaSession.setMetadata(metadata)

            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            showNotification(title)
        } else {
            // Even without focus, attempt to play local explicitly if requested by user manually
            audioEngine.playTrack(uri, context)
            showNotification(title)
        }
    }

    fun setPlaybackSpeed(speed: Double) {
        // We will call native method here once implemented
        // audioEngine.setSpeed(speed)
    }

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
                        AudioManager.AUDIOFOCUS_LOSS -> mediaSession.controller.transportControls.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaSession.controller.transportControls.pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> audioEngine.setVolume(0.2)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            audioEngine.setVolume(1.0)
                            mediaSession.controller.transportControls.play()
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
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private val CHANNEL_ID = "MusicPlayerPro"
    private val NOTIFICATION_ID = 1

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (title == "Ready") "Player Ready" else "Playing: ")
            .setContentText("MusicPlayerPro 64-bit Engine")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.shutdownEngine()
        mediaSession.isActive = false
        mediaSession.release()
        abandonAudioFocus()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }
}
