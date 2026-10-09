package com.aiproject.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.aiproject.musicplayer.playback.PlaybackService
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
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.LocalBinder).service
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
        setContent {
            HiFiApp(
                service = service,
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    uiPrefs.edit().putInt(KEY_THEME, mode.id).apply()
                    applySystemBars(mode)
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        service = null
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
