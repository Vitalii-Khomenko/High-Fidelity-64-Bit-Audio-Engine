package com.aiproject.musicplayer

import android.Manifest
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.aiproject.musicplayer.playback.ExternalAudio
import com.aiproject.musicplayer.playback.PlaybackService
import com.aiproject.musicplayer.playback.Track
import com.aiproject.musicplayer.ui.HiFiApp
import com.aiproject.musicplayer.ui.theme.DarkTokens
import com.aiproject.musicplayer.ui.theme.LightTokens
import com.aiproject.musicplayer.ui.theme.ThemeMode

/**
 * Hosts the Compose UI and binds to [PlaybackService], which owns playback.
 * The activity holds no playback state of its own.
 */
class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<PlaybackService?>(null)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var pendingSearch: String? = null   // voice query waiting for the service
    private var pendingOpen: List<Track>? = null // files opened from another app, waiting for the service
    private var playerRequests by mutableIntStateOf(0)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.LocalBinder).service
            pendingSearch?.let { query ->
                pendingSearch = null
                service?.playFromSearch(query)
            }
            pendingOpen?.let { tracks ->
                pendingOpen = null
                service?.setQueue(tracks, 0, true)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uiPrefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        themeMode = ThemeMode.fromId(uiPrefs.getInt(KEY_THEME, ThemeMode.SYSTEM.id))
        applySystemBars(themeMode)
        requestPermissionsOnce(uiPrefs)
        handleVoiceSearch(intent)
        // Not again when the activity is recreated (rotation): that would restart the file.
        if (savedInstanceState == null) handleOpen(intent)
        // Bound for the activity's whole life, not just while visible: system
        // pickers (folder chooser) stop this activity, and dropping the service
        // there tore down the UI that was waiting for the picker's result.
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent {
            HiFiApp(
                service = service,
                themeMode = themeMode,
                playerRequests = playerRequests,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    uiPrefs.edit().putInt(KEY_THEME, mode.id).apply()
                    applySystemBars(mode)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceSearch(intent)
        handleOpen(intent)
    }

    /** "Open with" / "Share" from another app: play those files now and show the player. */
    private fun handleOpen(intent: Intent?) {
        val uris = ExternalAudio.uris(intent)
        if (uris.isEmpty()) return
        val tracks = ExternalAudio.tracks(contentResolver, uris)
        if (tracks.isEmpty()) {
            Toast.makeText(this, R.string.open_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        playerRequests++
        val bound = service
        if (bound != null) bound.setQueue(tracks, 0, true) else pendingOpen = tracks
    }

    private fun handleVoiceSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
        val bound = service
        if (bound != null) bound.playFromSearch(query) else pendingSearch = query
    }

    override fun onDestroy() {
        unbindService(connection)
        service = null
        super.onDestroy()
    }

    /** Notifications (Android 13+) and Bluetooth codec info (Android 12+), asked once. */
    private fun requestPermissionsOnce(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_ASKED, false)) return
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        if (wanted.isNotEmpty()) permissionRequest.launch(wanted.toTypedArray())
    }

    private fun applySystemBars(mode: ThemeMode) {
        val dark = DarkTokens.ink.toArgb()
        val light = LightTokens.ink.toArgb()
        when (mode) {
            ThemeMode.SYSTEM -> enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(light, dark),
                navigationBarStyle = SystemBarStyle.auto(light, dark),
            )
            ThemeMode.DARK -> enableEdgeToEdge(SystemBarStyle.dark(dark), SystemBarStyle.dark(dark))
            ThemeMode.LIGHT -> enableEdgeToEdge(SystemBarStyle.light(light, dark), SystemBarStyle.light(light, dark))
        }
    }

    companion object {
        const val UI_PREFS = "ui"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ASKED = "permissions_asked"
    }
}
