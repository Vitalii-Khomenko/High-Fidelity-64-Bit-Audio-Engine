package com.aiproject.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.darkColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.aiproject.musicplayer.db.*
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import java.io.File
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AudioTrack(
    val uri: Uri,
    val name: String,
    val folder: String = "",
    val durationMs: Long = 0L,
)

data class HeadsetInfo(
    val name: String,
    val connectionType: String,   // "USB", "Bluetooth", "Wired", …
    val maxSampleRateHz: Int,     // 0 = unknown
    val maxBitDepth: Int,         // 0 = unknown
    val channels: Int             // 0 = unknown
) {
    /** Short one-line description for the UI. */
    val summary: String get() {
        val parts = mutableListOf<String>()
        if (maxSampleRateHz > 0) parts += "%.1f kHz".format(maxSampleRateHz / 1000.0)
        if (maxBitDepth > 0)     parts += "$maxBitDepth-bit"
        if (channels > 2)        parts += "${channels}ch"
        return if (parts.isEmpty()) connectionType else "$connectionType · ${parts.joinToString(" / ")}"
    }
}

/** Detect the highest-priority external audio output device and describe it. */
fun detectHeadsetInfo(audioManager: AudioManager): HeadsetInfo? {
    // Priority: USB DAC > USB headset > BT A2DP > BT LE > wired
    val typePriority = listOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            AudioDeviceInfo.TYPE_BLE_HEADSET else -1,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    )
    // On some Xiaomi/MIUI devices getDevices() throws SecurityException
    // if Bluetooth permission was denied — return null gracefully.
    val outputs = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    } catch (_: Exception) { return null }
    val device = typePriority.firstNotNullOfOrNull { t ->
        if (t < 0) null else outputs.find { it.type == t }
    } ?: return null

    val typeName = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET         -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP      -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES    -> "Wired"
        else                                     -> "Bluetooth"
    }

    val maxSR = device.sampleRates.maxOrNull() ?: 0
    val maxBits = device.encodings.maxOfOrNull { enc ->
        when (enc) {
            AudioFormat.ENCODING_PCM_8BIT         -> 8
            AudioFormat.ENCODING_PCM_16BIT        -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT        -> 32
            else -> 0
        }
    } ?: 0
    val maxCh = device.channelCounts.maxOrNull() ?: 0
    val name = device.productName?.toString()?.trim()?.ifEmpty { null } ?: typeName

    return HeadsetInfo(name, typeName, maxSR, maxBits, maxCh)
}

/** Fetch the active Bluetooth A2DP codec (LDAC / aptX HD / aptX / AAC / SBC).
 *  Requires API 29+ (BluetoothCodecConfig became public API in Q).
 *  Calls [onResult] on the main thread via the profile service listener.
 */
fun fetchBluetoothCodec(context: Context, onResult: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    fetchBluetoothCodecQ(context, onResult)
}

@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
private fun fetchBluetoothCodecQ(context: Context, onResult: (String) -> Unit) {
    val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } else {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()
    }
    if (adapter == null || !adapter.isEnabled) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return

    adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            try {
                val a2dp = proxy as BluetoothA2dp
                val device = a2dp.connectedDevices.firstOrNull()
                if (device != null) {
                    val type: Int = try {
                        // getCodecStatus is not in public SDK stubs — use reflection
                        val method = BluetoothA2dp::class.java.getMethod("getCodecStatus", device.javaClass)
                        val status = method.invoke(a2dp, device)
                        if (status != null) {
                            val getConfig = status.javaClass.getMethod("getCodecConfig")
                            val config = getConfig.invoke(status)
                            if (config != null) {
                                val getType = config.javaClass.getMethod("getCodecType")
                                (getType.invoke(config) as? Int) ?: -1
                            } else -1
                        } else -1
                    } catch (_: Exception) { -1 }
                    onResult(when (type) {
                        0 -> "SBC"
                        1 -> "AAC"
                        2 -> "aptX"
                        3 -> "aptX HD"
                        4 -> "LDAC"
                        5 -> "aptX Adaptive"
                        6 -> "LC3"
                        else -> ""
                    })
                }
            } catch (_: Exception) {}
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }
        override fun onServiceDisconnected(profile: Int) {}
    }, BluetoothProfile.A2DP)
}

class MainActivity : ComponentActivity() {

    private var playbackService: PlaybackService? = null
    private var isBound by mutableStateOf(false)
    private var pendingPlaybackAction: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            pendingPlaybackAction?.let { action ->
                pendingPlaybackAction = null
                action()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            playbackService = null
            isBound = false
        }
    }

    private fun ensurePlaybackServiceConnection(action: (() -> Unit)? = null) {
        if (action != null) {
            pendingPlaybackAction = action
        }
        if (isBound) {
            pendingPlaybackAction?.let { pendingAction ->
                pendingPlaybackAction = null
                pendingAction()
            }
            return
        }
        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                pendingPlaybackAction = null
                e.printStackTrace()
            }
        }
    }

    private fun bindPlaybackServiceIfRunning() {
        if (isBound) return
        Intent(this, PlaybackService::class.java).also { intent ->
            try {
                bindService(intent, connection, 0)
            } catch (_: Exception) {
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing && !isDestroyed) {
            bindPlaybackServiceIfRunning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            // Clear all callbacks stored on the long-lived Service so it no
            // longer holds references to Compose lambdas (and thus the Activity).
            // Without this, the GC cannot collect the Activity after rotation
            // or system-initiated recreation.
            playbackService?.skipToNextCallback     = null
            playbackService?.skipToPreviousCallback = null
            playbackService?.nextTrackProvider      = null
            playbackService?.onGaplessAdvanced      = null
            playbackService?.onTrackCompleted       = null
            playbackService?.onTrackLoadFailed      = null
            unbindService(connection)
            isBound = false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind system bars so Compose controls the full screen.
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // White status-bar icons on the dark background
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // --- Persistent state prefs ---
                val statePrefs = remember { getSharedPreferences("player_state", MODE_PRIVATE) }

                // Helper to save/load playlist as JSON
                fun savePlaylistToPrefs(tracks: List<AudioTrack>) {
                    val snapshot = tracks.toList()
                    lifecycleScope.launch(Dispatchers.Default) {
                        val arr = JSONArray()
                        snapshot.forEach { t ->
                            arr.put(
                                JSONObject()
                                    .put("uri", t.uri.toString())
                                    .put("name", t.name)
                                    .put("folder", t.folder)
                                    .put("durationMs", t.durationMs)
                            )
                        }
                        statePrefs.edit().putString("playlist_json", arr.toString()).apply()
                    }
                }
                fun loadPlaylistFromPrefs(): List<AudioTrack> {
                    val json = statePrefs.getString("playlist_json", null) ?: return emptyList()
                    return try {
                        val arr = JSONArray(json)
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            AudioTrack(
                                uri = Uri.parse(o.getString("uri")),
                                name = o.getString("name"),
                                folder = o.optString("folder", ""),
                                durationMs = o.optLong("durationMs", 0L)
                            )
                        }
                    } catch (_: Exception) { emptyList() }
                }

                fun saveIntListToPrefs(key: String, values: List<Int>) {
                    val arr = JSONArray()
                    values.forEach { arr.put(it) }
                    statePrefs.edit().putString(key, arr.toString()).apply()
                }

                fun saveStringListToPrefs(key: String, values: List<String>) {
                    val arr = JSONArray()
                    values.forEach { arr.put(it) }
                    statePrefs.edit().putString(key, arr.toString()).apply()
                }

                fun loadIntListFromPrefs(key: String): List<Int> {
                    val json = statePrefs.getString(key, null) ?: return emptyList()
                    return try {
                        val arr = JSONArray(json)
                        (0 until arr.length()).map { index -> arr.optInt(index, -1) }.filter { it >= 0 }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                fun loadStringListFromPrefs(key: String): List<String> {
                    val json = statePrefs.getString(key, null) ?: return emptyList()
                    return try {
                        val arr = JSONArray(json)
                        (0 until arr.length()).mapNotNull { index -> arr.optString(index).takeIf { it.isNotBlank() } }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                fun saveLibraryFoldersToPrefs(entries: List<LibraryFolderEntry>) {
                    statePrefs.edit()
                        .putString(LibraryFolderEntry.PREF_KEY, LibraryFolderEntry.serialize(entries))
                        .remove(LibraryFolderEntry.LEGACY_URI_KEY)
                        .apply()
                }

                fun loadLibraryFoldersFromPrefs(): List<LibraryFolderEntry> {
                    if (statePrefs.contains(LibraryFolderEntry.PREF_KEY)) {
                        return LibraryFolderEntry.deserialize(
                            statePrefs.getString(LibraryFolderEntry.PREF_KEY, null)
                        )
                    }
                    val legacyUris = loadStringListFromPrefs(LibraryFolderEntry.LEGACY_URI_KEY)
                    return LibraryFolderEntry.fromLegacyUris(legacyUris) { uriString ->
                        folderNameFromTreeUri(Uri.parse(uriString))
                    }
                }

                data class StartupRestoreSnapshot(
                    val playlist: List<AudioTrack>,
                    val speedMode: PlaybackSpeedMode,
                    val playbackContentMode: PlaybackContentMode,
                    val playlistSortMode: PlaylistSortMode,
                    val eqSettings: EqSettings,
                    val shuffleEnabled: Boolean,
                    val shuffleHistory: List<Int>,
                    val shuffleQueue: List<Int>,
                    val libraryFolders: List<LibraryFolderEntry>,
                )

                fun loadStartupRestoreSnapshot(): StartupRestoreSnapshot {
                    return StartupRestoreSnapshot(
                        playlist = loadPlaylistFromPrefs(),
                        speedMode = PlaybackSpeedMode.fromId(
                            statePrefs.getInt("playback_speed_mode", PlaybackSpeedMode.MUSIC.id)
                        ),
                        playbackContentMode = PlaybackContentMode.fromId(
                            statePrefs.getInt("playback_content_mode", PlaybackContentMode.BOOKS.id)
                        ),
                        playlistSortMode = PlaylistSortMode.fromId(
                            statePrefs.getInt("playlist_sort_mode", PlaylistSortMode.NAME.id)
                        ),
                        eqSettings = EqSettings.deserialize(
                            enabled = statePrefs.getBoolean("eq_enabled", false),
                            serialized = statePrefs.getString("eq_gains", null)
                        ),
                        shuffleEnabled = statePrefs.getBoolean("shuffle_enabled", false),
                        shuffleHistory = loadIntListFromPrefs("shuffle_history_json"),
                        shuffleQueue = loadIntListFromPrefs("shuffle_queue_json"),
                        libraryFolders = loadLibraryFoldersFromPrefs(),
                    )
                }

                // --- State ---
                var playlist by remember { mutableStateOf(emptyList<AudioTrack>()) }
                var currentIndex by remember { mutableStateOf(statePrefs.getInt("current_index", -1)) }
                var isPlaying by remember { mutableStateOf(false) }
                var isLoadingTrack by remember { mutableStateOf(false) }
                var positionRestored by remember { mutableStateOf(false) }
                var volume by remember { mutableFloatStateOf(1.0f) }
                var progressMs by remember { mutableStateOf(0f) }
                var durationMs by remember { mutableStateOf(1f) }
                var isSeeking by remember { mutableStateOf(false) }
                var speedMult by remember { mutableFloatStateOf(1.0f) }
                var speedMode by remember { mutableStateOf(PlaybackSpeedMode.MUSIC) }
                var playbackContentMode by remember { mutableStateOf(PlaybackContentMode.BOOKS) }
                var playlistSortMode by remember { mutableStateOf(PlaylistSortMode.NAME) }
                var eqSettings by remember { mutableStateOf(EqSettings()) }
                var sampleRateKhz by remember { mutableStateOf("") }
                var bitDepth by remember { mutableStateOf("") }
                var spectrumBands by remember { mutableStateOf(FloatArray(32)) }
                var replayGainDb  by remember { mutableFloatStateOf(0f) }
                var dsdNativeRate by remember { mutableIntStateOf(0) }
                var bluetoothCodec by remember { mutableStateOf("") }
                var shuffleEnabled by remember { mutableStateOf(false) }
                var shuffleHistory by remember { mutableStateOf(emptyList<Int>()) }
                var shuffleQueue by remember { mutableStateOf(emptyList<Int>()) }
                var libraryFolders by remember { mutableStateOf(emptyList<LibraryFolderEntry>()) }
                var startupStateRestored by remember { mutableStateOf(false) }
                var database by remember { mutableStateOf<MusicDatabase?>(null) }

                LaunchedEffect(Unit) {
                    val restored = withContext(Dispatchers.Default) {
                        loadStartupRestoreSnapshot()
                    }
                    playlist = restored.playlist
                    if (currentIndex !in restored.playlist.indices) {
                        currentIndex = -1
                    }
                    speedMode = restored.speedMode
                    playbackContentMode = restored.playbackContentMode
                    playlistSortMode = restored.playlistSortMode
                    eqSettings = restored.eqSettings
                    shuffleEnabled = restored.shuffleEnabled
                    shuffleHistory = restored.shuffleHistory
                    shuffleQueue = restored.shuffleQueue
                    libraryFolders = restored.libraryFolders
                    startupStateRestored = true
                }

                LaunchedEffect(Unit) {
                    database = withContext(Dispatchers.IO) {
                        MusicDatabase.getDatabase(applicationContext)
                    }
                }

                // DLNA / UPnP browser state
                var dlnaShow     by remember { mutableStateOf(false) }
                var dlnaScanning by remember { mutableStateOf(false) }
                var dlnaServers  by remember { mutableStateOf<List<DlnaServer>>(emptyList()) }
                var dlnaSelected by remember { mutableStateOf<DlnaServer?>(null) }
                var dlnaTracks   by remember { mutableStateOf<List<DlnaTrack>>(emptyList()) }
                var dlnaBrowsing by remember { mutableStateOf(false) }
                var dlnaErrorMessage by remember { mutableStateOf<String?>(null) }

                // Repeat mode: 0=off  1=repeat one  2=repeat all
                var repeatMode by remember { mutableIntStateOf(0) }

                fun formatSleepTimerRemaining(remainingMs: Long): String {
                    val clamped = remainingMs.coerceAtLeast(0L)
                    val totalSeconds = (clamped + 999L) / 1000L
                    val mins = totalSeconds / 60L
                    val secs = totalSeconds % 60L
                    return "%d:%02d".format(mins, secs)
                }

                // Per-track position bookmarks (key = URI hashcode)
                fun saveTrackPosition(uri: Uri, posMs: Float) {
                    if (playbackContentMode.remembersTrackProgress && posMs > 2000f)
                        statePrefs.edit().putFloat("pos_${uri.hashCode()}", posMs).apply()
                }
                fun loadTrackPosition(uri: Uri): Float =
                    if (playbackContentMode.remembersTrackProgress) {
                        statePrefs.getFloat("pos_${uri.hashCode()}", 0f)
                    } else {
                        0f
                    }
                fun clearTrackPosition(uri: Uri) {
                    if (playbackContentMode.remembersTrackProgress) {
                        statePrefs.edit().remove("pos_${uri.hashCode()}").apply()
                    }
                }

                fun savePausedTrackState(uri: Uri, index: Int, posMs: Float) {
                    if (index !in playlist.indices || posMs <= 2000f) return
                    statePrefs.edit()
                        .putFloat("saved_position_ms", posMs)
                        .putString("saved_position_uri", uri.toString())
                        .putInt("current_index", index)
                        .apply()
                }

                fun loadPausedTrackState(uri: Uri): Float {
                    val savedUri = statePrefs.getString("saved_position_uri", null)
                    if (savedUri != uri.toString()) return 0f
                    return statePrefs.getFloat("saved_position_ms", 0f)
                }

                fun clearPausedTrackState(removeCurrentIndex: Boolean = false) {
                    val editor = statePrefs.edit()
                        .remove("saved_position_ms")
                        .remove("saved_position_uri")
                    if (removeCurrentIndex) {
                        editor.remove("current_index")
                    }
                    editor.apply()
                }

                // Headset / audio device detection
                val audioManager = remember { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
                var headsetInfo by remember { mutableStateOf(detectHeadsetInfo(audioManager)) }
                DisposableEffect(Unit) {
                    val callback = object : AudioDeviceCallback() {
                        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                            headsetInfo = detectHeadsetInfo(audioManager)
                        }
                        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                            headsetInfo = detectHeadsetInfo(audioManager)
                        }
                    }
                    audioManager.registerAudioDeviceCallback(callback, null)
                    onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
                }

                // Fetch Bluetooth codec whenever the connected audio device changes
                LaunchedEffect(headsetInfo) {
                    bluetoothCodec = ""
                    val info = headsetInfo ?: return@LaunchedEffect
                    if (info.connectionType.startsWith("Bluetooth")) {
                        fetchBluetoothCodec(this@MainActivity) { codec ->
                            bluetoothCodec = codec
                        }
                    }
                }

                LaunchedEffect(shuffleEnabled, shuffleHistory, shuffleQueue) {
                    if (!startupStateRestored) return@LaunchedEffect
                    statePrefs.edit().putBoolean("shuffle_enabled", shuffleEnabled).apply()
                    saveIntListToPrefs("shuffle_history_json", shuffleHistory)
                    saveIntListToPrefs("shuffle_queue_json", shuffleQueue)
                }

                LaunchedEffect(libraryFolders) {
                    if (!startupStateRestored) return@LaunchedEffect
                    saveLibraryFoldersToPrefs(libraryFolders)
                }

                LaunchedEffect(speedMode) {
                    if (!startupStateRestored) return@LaunchedEffect
                    statePrefs.edit().putInt("playback_speed_mode", speedMode.id).apply()
                }

                LaunchedEffect(playbackContentMode) {
                    if (!startupStateRestored) return@LaunchedEffect
                    statePrefs.edit().putInt("playback_content_mode", playbackContentMode.id).apply()
                }

                LaunchedEffect(playlistSortMode) {
                    if (!startupStateRestored) return@LaunchedEffect
                    statePrefs.edit().putInt("playlist_sort_mode", playlistSortMode.id).apply()
                }

                LaunchedEffect(eqSettings) {
                    if (!startupStateRestored) return@LaunchedEffect
                    statePrefs.edit()
                        .putBoolean("eq_enabled", eqSettings.enabled)
                        .putString("eq_gains", eqSettings.serialize())
                        .apply()
                }

                // Played-track indicator — persisted in SharedPreferences by URI
                // so it survives app restarts and Activity recreation.
                // Useful for audiobooks: shows which chapters were already heard.
                val prefs = remember { getSharedPreferences("audiobook_progress", MODE_PRIVATE) }
                var playedUris by remember {
                    mutableStateOf(prefs.getStringSet("played_uris", emptySet<String>())
                        ?.toSet() ?: emptySet())
                }
                fun markTrackPlayed(uri: Uri) {
                    if (!playbackContentMode.showsPlayedState) return
                    val uriStr = uri.toString()
                    if (uriStr !in playedUris) {
                        playedUris = playedUris + uriStr
                        prefs.edit().putStringSet("played_uris", playedUris).apply()
                    }
                }
                // Derive index-set from current playlist + persisted URIs
                val playedIndices = remember(playlist, playedUris, playbackContentMode) {
                    if (!playbackContentMode.showsPlayedState) {
                        emptySet()
                    } else {
                        playlist.indices.filter { i ->
                            playlist[i].uri.toString() in playedUris
                        }.toSet()
                    }
                }

                // Menu / Dialog state
                var showMenu by remember { mutableStateOf(false) }
                var showCreatePlaylist by remember { mutableStateOf(false) }
                var playlistName by remember { mutableStateOf("") }
                var showLoadPlaylist by remember { mutableStateOf(false) }
                var showLibraryFolders by remember { mutableStateOf(false) }
                var showBrowseLibraryFolder by remember { mutableStateOf(false) }
                var showSleepTimer by remember { mutableStateOf(false) }
                var showEqualizer by remember { mutableStateOf(false) }
                var sleepTimerEndMs by remember { mutableLongStateOf(0L) }
                var sleepTimerDisplay by remember { mutableStateOf("") }
                var browsingLibraryRoot by remember { mutableStateOf<LibraryFolderEntry?>(null) }
                var libraryBrowseStack by remember { mutableStateOf<List<LibraryBrowseLocation>>(emptyList()) }
                var libraryBrowserEntries by remember { mutableStateOf<List<LibraryBrowserEntry>>(emptyList()) }
                var libraryBrowserLoading by remember { mutableStateOf(false) }
                var libraryBrowserErrorMessage by remember { mutableStateOf<String?>(null) }

                fun applyEqSettings() {
                    val service = playbackService ?: return
                    service.setEqEnabled(eqSettings.enabled)
                    eqSettings.bandGainsDb.forEachIndexed { index, gainDb ->
                        service.setEqBandGain(index, gainDb.toDouble())
                    }
                }

                fun persistedLibraryUriSet(): Set<String> {
                    return contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.toString() }
                        .toSet()
                }

                fun availableLibraryFolders(entries: List<LibraryFolderEntry> = libraryFolders): List<LibraryFolderEntry> {
                    val persistedUris = persistedLibraryUriSet()
                    return entries.filter { entry ->
                        entry.uriString in persistedUris && entry.uriString.contains("tree")
                    }
                }

                fun buildLibraryFolderEntry(folderUri: Uri): LibraryFolderEntry {
                    return LibraryFolderEntry(
                        uriString = folderUri.toString(),
                        label = folderNameFromTreeUri(folderUri)
                    )
                }

                fun loadLibraryBrowserLocation(rootEntry: LibraryFolderEntry, stack: List<LibraryBrowseLocation>) {
                    val location = stack.lastOrNull() ?: return
                    libraryBrowserLoading = true
                    libraryBrowserErrorMessage = null
                    lifecycleScope.launch {
                        try {
                            libraryBrowserEntries = withContext(Dispatchers.IO) {
                                listLibraryFolderChildren(
                                    treeUri = Uri.parse(rootEntry.uriString),
                                    docId = location.documentId,
                                    folderLabel = location.label,
                                )
                            }
                        } catch (e: Exception) {
                            libraryBrowserEntries = emptyList()
                            libraryBrowserErrorMessage = e.message ?: "Failed to browse ${location.label}"
                        } finally {
                            libraryBrowserLoading = false
                        }
                    }
                }

                fun cancelSleepTimer() {
                    sleepTimerEndMs = 0L
                    sleepTimerDisplay = ""
                    if (isBound) playbackService?.setVolume(volume.toDouble())
                }

                // DB
                val playlistFlow = remember(database) {
                    database?.playlistDao()?.getAllPlaylists() ?: flowOf(emptyList<PlaylistEntity>())
                }
                val playlists by playlistFlow.collectAsState(initial = emptyList())

                val currentTrack = if (currentIndex in playlist.indices) playlist[currentIndex] else null
                val playlistSummary = remember(playlist) { PlaylistSummaryCalculator.fromTracks(playlist) }

                fun sortTracksForMode(tracks: List<AudioTrack>, mode: PlaylistSortMode = playlistSortMode): List<AudioTrack> {
                    return PlaylistOrdering.sortTracks(tracks, mode)
                }

                fun reindexCurrentTrack(sorted: List<AudioTrack>) {
                    val currentTrackUri = currentTrack?.uri?.toString() ?: return
                    currentIndex = sorted.indexOfFirst { it.uri.toString() == currentTrackUri }
                }

                fun addTracksToPlaylist(incoming: List<AudioTrack>): Int {
                    val updated = PlaylistMerge.appendDistinctBy(playlist, incoming) { it.uri.toString() }
                    val addedCount = updated.size - playlist.size
                    if (addedCount > 0) {
                        val sorted = sortTracksForMode(updated)
                        playlist = sorted
                        reindexCurrentTrack(sorted)
                        savePlaylistToPrefs(sorted)
                    }
                    return addedCount
                }

                fun applyPlaylistSort(mode: PlaylistSortMode) {
                    playlistSortMode = mode
                    val sorted = sortTracksForMode(playlist, mode)
                    playlist = sorted
                    reindexCurrentTrack(sorted)
                    savePlaylistToPrefs(sorted)
                }

                fun removeLibraryFolder(entry: LibraryFolderEntry) {
                    try {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(entry.uriString),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    libraryFolders = libraryFolders.filterNot { it.uriString == entry.uriString }
                }

                fun openLibraryFolderBrowser(entry: LibraryFolderEntry) {
                    if (entry !in availableLibraryFolders(listOf(entry))) {
                        Toast.makeText(
                            applicationContext,
                            "Access lost for ${entry.label}. Re-add this folder.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    val rootUri = Uri.parse(entry.uriString)
                    val rootLocation = LibraryBrowseLocation(
                        documentId = DocumentsContract.getTreeDocumentId(rootUri),
                        label = entry.label
                    )
                    browsingLibraryRoot = entry
                    libraryBrowseStack = listOf(rootLocation)
                    showBrowseLibraryFolder = true
                    loadLibraryBrowserLocation(entry, listOf(rootLocation))
                }

                fun navigateIntoLibraryFolder(browserEntry: LibraryBrowserEntry) {
                    val rootEntry = browsingLibraryRoot ?: return
                    if (!browserEntry.isDirectory) return
                    val nextStack = libraryBrowseStack + LibraryBrowseLocation(
                        documentId = browserEntry.documentId,
                        label = browserEntry.name
                    )
                    libraryBrowseStack = nextStack
                    loadLibraryBrowserLocation(rootEntry, nextStack)
                }

                fun navigateUpLibraryFolder() {
                    val rootEntry = browsingLibraryRoot ?: return
                    if (libraryBrowseStack.size <= 1) return
                    val nextStack = libraryBrowseStack.dropLast(1)
                    libraryBrowseStack = nextStack
                    loadLibraryBrowserLocation(rootEntry, nextStack)
                }

                fun addCurrentLibraryFolderToPlaylist() {
                    val rootEntry = browsingLibraryRoot ?: return
                    val currentLocation = libraryBrowseStack.lastOrNull() ?: return
                    lifecycleScope.launch {
                        val newTracks = withContext(Dispatchers.IO) {
                            loadTracksFromTree(
                                treeUri = Uri.parse(rootEntry.uriString),
                                docId = currentLocation.documentId,
                                folderLabel = currentLocation.label,
                            )
                        }
                        val addedCount = addTracksToPlaylist(newTracks)
                        Toast.makeText(
                            applicationContext,
                            "Added $addedCount tracks from ${currentLocation.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                fun addLibraryTrackToPlaylist(browserEntry: LibraryBrowserEntry) {
                    val track = browserEntry.track ?: return
                    val addedCount = addTracksToPlaylist(listOf(track))
                    Toast.makeText(
                        applicationContext,
                        if (addedCount > 0) "Added: ${track.name}" else "Track already in playlist",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                fun resolveNextIndex(): Int {
                    return if (shuffleEnabled) {
                        PlaybackShuffle.previewNextIndex(
                            currentIndex = currentIndex,
                            playlistSize = playlist.size,
                            repeatMode = repeatMode,
                            queue = shuffleQueue,
                        )
                    } else {
                        PlaybackQueueFlow.nextIndex(
                            currentIndex = currentIndex,
                            playlistSize = playlist.size,
                            repeatMode = repeatMode
                        )
                    }
                }

                fun browseDlnaServer(server: DlnaServer) {
                    dlnaSelected = server
                    dlnaBrowsing = true
                    dlnaTracks = emptyList()
                    dlnaErrorMessage = null
                    lifecycleScope.launch {
                        try {
                            dlnaTracks = DlnaDiscovery.browse(server)
                            if (dlnaTracks.isEmpty()) {
                                dlnaErrorMessage = "No audio tracks found on ${server.friendlyName}."
                            }
                        } catch (e: Exception) {
                            dlnaTracks = emptyList()
                            dlnaErrorMessage = e.message ?: "Failed to browse ${server.friendlyName}."
                        } finally {
                            dlnaBrowsing = false
                        }
                    }
                }

                fun scanDlnaServers() {
                    dlnaScanning = true
                    dlnaServers = emptyList()
                    dlnaSelected = null
                    dlnaTracks = emptyList()
                    dlnaErrorMessage = null
                    lifecycleScope.launch {
                        try {
                            dlnaServers = DlnaDiscovery.discoverServers()
                            if (dlnaServers.isEmpty()) {
                                dlnaErrorMessage = "No DLNA media servers found on the current network."
                            }
                        } catch (e: Exception) {
                            dlnaServers = emptyList()
                            dlnaErrorMessage = e.message ?: "DLNA scan failed."
                        } finally {
                            dlnaScanning = false
                        }
                    }
                }

                // Helper to play a track by index.
                // The heavy part (file scan + decode init) runs on the IO dispatcher
                // so the main thread is never frozen.  A loadMutex in PlaybackService
                // ensures only one native load runs at a time.
                fun playAtIndex(
                    index: Int,
                    rememberShuffleCurrent: Boolean = true,
                    shuffleQueueOverride: List<Int>? = null,
                ) {
                    if (index !in playlist.indices) return
                    if (isLoadingTrack) return
                    if (!isBound) {
                        ensurePlaybackServiceConnection {
                            playAtIndex(index, rememberShuffleCurrent, shuffleQueueOverride)
                        }
                        return
                    }
                    // Same track tapped while playing — just restart from beginning (no reload)
                    if (index == currentIndex && playbackService?.getEngine()?.isPlaying() == true) {
                        if (!playbackContentMode.remembersTrackProgress) {
                            clearPausedTrackState()
                        }
                        playbackService?.getEngine()?.seekTo(0.0)
                        progressMs = 0f
                        return
                    }
                    if (shuffleEnabled && rememberShuffleCurrent && currentIndex in playlist.indices && index != currentIndex) {
                        shuffleHistory = PlaybackShuffle.pushHistory(shuffleHistory, currentIndex)
                    }
                    shuffleQueue = if (shuffleEnabled) {
                        shuffleQueueOverride ?: PlaybackShuffle.buildQueue(index, playlist.size)
                    } else {
                        emptyList()
                    }
                    // Save position of the track we're leaving
                    if (currentIndex in playlist.indices && progressMs > 2000f)
                        saveTrackPosition(playlist[currentIndex].uri, progressMs)
                    clearPausedTrackState()
                    currentIndex = index
                    isPlaying = true
                    isLoadingTrack = true
                    progressMs = 0f
                    durationMs = 1f
                    positionRestored = true
                    statePrefs.edit().putInt("current_index", index).apply()
                    val track = playlist[index]
                    // Load saved chapter position for this track
                    val resumePos = loadTrackPosition(track.uri)
                    lifecycleScope.launch {
                        playbackService?.playTrack(
                            track.uri, this@MainActivity, track.name, resumePos.toDouble()
                        )
                        playbackService?.setPlaybackSpeedMode(speedMode)
                        playbackService?.setPlaybackSpeed(PlaybackSpeed.clamp(speedMult).toDouble())
                        applyEqSettings()
                        playbackService?.setVolume(volume.toDouble())
                        isLoadingTrack = false
                    }
                }

                fun playPreviousFromUser() {
                    if (shuffleEnabled) {
                        val (prev, remaining) = PlaybackShuffle.popHistory(shuffleHistory)
                        if (prev in playlist.indices) {
                            shuffleHistory = remaining
                            playAtIndex(prev, rememberShuffleCurrent = false)
                        }
                    } else {
                        val prev = currentIndex - 1
                        if (prev >= 0) playAtIndex(prev)
                    }
                }

                fun playNextFromUser() {
                    var next = resolveNextIndex()
                    if (shuffleEnabled && next !in playlist.indices && currentIndex in playlist.indices && playlist.size > 1) {
                        next = PlaybackShuffle.buildQueue(currentIndex, playlist.size).firstOrNull() ?: -1
                    }
                    if (next in playlist.indices) {
                        val nextQueue = if (shuffleEnabled) {
                            PlaybackShuffle.queueAfterAdvance(
                                currentIndex = currentIndex,
                                nextIndex = next,
                                playlistSize = playlist.size,
                                repeatMode = repeatMode,
                                queue = shuffleQueue,
                            )
                        } else {
                            null
                        }
                        playAtIndex(next, shuffleQueueOverride = nextQueue)
                    }
                }

                // Wire up notification buttons and track-end callback.
                // SideEffect runs after every recompose so callbacks always capture latest state.
                SideEffect {
                    if (isBound) {
                        playbackService?.skipToNextCallback = {
                            lifecycleScope.launch(Dispatchers.Main) { playNextFromUser() }
                        }
                        playbackService?.skipToPreviousCallback = {
                            lifecycleScope.launch(Dispatchers.Main) { playPreviousFromUser() }
                        }
                        playbackService?.nextTrackProvider = {
                            val next = resolveNextIndex()
                            playlist.getOrNull(next)?.let { track ->
                                QueuedTrack(track.uri, track.name)
                            }
                        }
                        playbackService?.onGaplessAdvanced = {
                            val next = resolveNextIndex()
                            if (next in playlist.indices) {
                                // Save position of old track (it finished)
                                if (currentIndex in playlist.indices) {
                                    if (shuffleEnabled && next != currentIndex) {
                                        shuffleHistory = PlaybackShuffle.pushHistory(shuffleHistory, currentIndex)
                                    }
                                    markTrackPlayed(playlist[currentIndex].uri)
                                    clearTrackPosition(playlist[currentIndex].uri)
                                    clearPausedTrackState()
                                }
                                if (shuffleEnabled) {
                                    shuffleQueue = PlaybackShuffle.queueAfterAdvance(
                                        currentIndex = currentIndex,
                                        nextIndex = next,
                                        playlistSize = playlist.size,
                                        repeatMode = repeatMode,
                                        queue = shuffleQueue,
                                    )
                                }
                                currentIndex = next
                                statePrefs.edit().putInt("current_index", next).apply()
                                progressMs = 0f
                            }
                        }
                        // Track finished naturally → advance (respecting repeat mode)
                        playbackService?.onTrackCompleted = {
                            if (currentIndex in playlist.indices) {
                                markTrackPlayed(playlist[currentIndex].uri)
                                clearTrackPosition(playlist[currentIndex].uri)
                                clearPausedTrackState()
                            }
                            val completion = if (shuffleEnabled) {
                                val next = resolveNextIndex()
                                PlaybackCompletionDecision(
                                    nextIndex = next,
                                    replayCurrent = repeatMode == 1,
                                    shouldStop = next !in playlist.indices
                                )
                            } else {
                                PlaybackQueueFlow.completionDecision(
                                    currentIndex = currentIndex,
                                    playlistSize = playlist.size,
                                    repeatMode = repeatMode
                                )
                            }
                            when {
                                completion.replayCurrent -> lifecycleScope.launch(Dispatchers.Main) {
                                    playAtIndex(completion.nextIndex)
                                }
                                completion.nextIndex in playlist.indices -> {
                                    val nextQueue = if (shuffleEnabled) {
                                        PlaybackShuffle.queueAfterAdvance(
                                            currentIndex = currentIndex,
                                            nextIndex = completion.nextIndex,
                                            playlistSize = playlist.size,
                                            repeatMode = repeatMode,
                                            queue = shuffleQueue,
                                        )
                                    } else {
                                        null
                                    }
                                    if (shuffleEnabled && currentIndex in playlist.indices && completion.nextIndex != currentIndex) {
                                        shuffleHistory = PlaybackShuffle.pushHistory(shuffleHistory, currentIndex)
                                    }
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        playAtIndex(
                                            completion.nextIndex,
                                            shuffleQueueOverride = nextQueue,
                                        )
                                    }
                                }
                                completion.shouldStop -> {
                                    shuffleQueue = emptyList()
                                    isPlaying = false
                                    currentIndex = -1
                                    progressMs = 0f
                                    clearPausedTrackState(removeCurrentIndex = true)
                                }
                            }
                        }
                        playbackService?.onTrackLoadFailed = { failedTitle ->
                            lifecycleScope.launch(Dispatchers.Main) {
                                isPlaying = false
                                isLoadingTrack = false
                                Toast.makeText(
                                    applicationContext,
                                    "Failed to load $failedTitle",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                LaunchedEffect(playlist, currentIndex, shuffleEnabled) {
                    val sanitizedHistory = shuffleHistory.filter { it in playlist.indices }
                    val sanitizedQueue = PlaybackShuffle.sanitizeQueue(shuffleQueue, currentIndex, playlist.size)
                    if (sanitizedHistory != shuffleHistory) {
                        shuffleHistory = sanitizedHistory
                    }
                    if (sanitizedQueue != shuffleQueue) {
                        shuffleQueue = sanitizedQueue
                    }
                }

                LaunchedEffect(playlist) {
                    val snapshot = playlist
                    val pending = snapshot.withIndex().filter { indexed ->
                        indexed.value.durationMs <= 0L && indexed.value.uri.scheme in setOf("content", "file")
                    }
                    if (pending.isEmpty()) return@LaunchedEffect
                    val updates = withContext(Dispatchers.IO) {
                        pending.mapNotNull { indexed ->
                            val duration = probeDurationMs(indexed.value.uri)
                            if (duration > 0L) indexed.index to duration else null
                        }
                    }
                    if (updates.isEmpty()) return@LaunchedEffect
                    if (playlist.map { it.uri.toString() } != snapshot.map { it.uri.toString() }) {
                        return@LaunchedEffect
                    }
                    val durationMap = updates.toMap()
                    val hydrated = playlist.mapIndexed { index, track ->
                        durationMap[index]?.let { duration ->
                            if (track.durationMs == duration) track else track.copy(durationMs = duration)
                        } ?: track
                    }
                    if (hydrated != playlist) {
                        val currentTrackUri = currentTrack?.uri?.toString()
                        val sorted = sortTracksForMode(hydrated)
                        playlist = sorted
                        currentIndex = currentTrackUri?.let { uri ->
                            sorted.indexOfFirst { it.uri.toString() == uri }
                        } ?: currentIndex
                        savePlaylistToPrefs(sorted)
                    }
                }

                // Restore currentIndex after Activity recreation.
                LaunchedEffect(isBound, playlist.size) {
                    if (isBound && currentIndex == -1 && playlist.isNotEmpty()) {
                        val currentUri = playbackService?.currentTrackUri
                        val idx = PlaybackRestore.findTrackIndexByUri(
                            playlist.map { it.uri.toString() },
                            currentUri
                        )
                        if (idx >= 0) {
                            currentIndex = idx
                            isPlaying = playbackService?.getEngine()?.isPlaying() == true
                        }
                    }
                }

                LaunchedEffect(isBound, eqSettings) {
                    if (isBound) {
                        applyEqSettings()
                    }
                }

                // Restore saved playback position once after binding (audiobook resume).
                LaunchedEffect(isBound) {
                    if (isBound && !positionRestored && currentIndex >= 0) {
                        positionRestored = true
                        val engine = playbackService?.getEngine() ?: return@LaunchedEffect
                        // Only restore if engine is near the start — if the service is already
                        // playing mid-track (activity recreated while service was live), seeking
                        // to the stale saved position would cause an audible 1-second rollback.
                        if (engine.getPositionMs() < 3000.0) {
                            val trackUri = playlist.getOrNull(currentIndex)?.uri
                            val savedPos = trackUri?.let { uri ->
                                loadTrackPosition(uri).takeIf { it > 2000f } ?: loadPausedTrackState(uri)
                            } ?: 0f
                            if (savedPos > 2000f) {
                                delay(800) // let the engine settle after binding
                                engine.seekTo(savedPos.toDouble())
                            }
                        }
                    }
                }

                // --- Continuous update loop: position, playing state, metadata ---
                LaunchedEffect(isBound, currentIndex) {
                    var saveCounter = 0
                    while (true) {
                        if (isBound) {
                            val engine = playbackService?.getEngine()
                            if (engine != null) {
                                isPlaying = engine.isPlaying()
                                if (!isSeeking) {
                                    val pos = engine.getPositionMs().toFloat()
                                    val dur = engine.getDurationMs().toFloat()
                                    if (dur > 0f) {
                                        durationMs = dur
                                        if (pos in 0f..dur) progressMs = pos
                                    }
                                }
                                val sr = engine.getSampleRateNative()
                                if (sr > 0) sampleRateKhz = "%.1f kHz".format(sr / 1000.0)
                                val bd = engine.getBitsPerSample()
                                if (bd > 0) bitDepth = "$bd-bit"

                                // (spectrum polled in dedicated 30-fps loop below)
                                // Read ReplayGain once per track load
                                val rg = engine.getReplayGainDb()
                                if (rg != replayGainDb) replayGainDb = rg
                                // DSD native rate (0 = not a DSD track)
                                val dsr = engine.getDsdNativeRate()
                                if (dsr != dsdNativeRate) dsdNativeRate = dsr

                                // Save position every ~5 seconds while playing (audiobook resume)
                                if (playbackContentMode.remembersTrackProgress &&
                                    isPlaying &&
                                    currentIndex in playlist.indices &&
                                    progressMs > 2000f
                                ) {
                                    saveCounter++
                                    if (saveCounter >= 10) { // 10 × 500ms = 5s
                                        saveCounter = 0
                                        savePausedTrackState(
                                            uri = playlist[currentIndex].uri,
                                            index = currentIndex,
                                            posMs = progressMs,
                                        )
                                    }
                                } else {
                                    saveCounter = 0
                                }
                            }
                        }
                        delay(500)
                    }
                }

                // --- Spectrum / equalizer: dedicated 30-fps poll ---
                // Kept separate from the 500 ms position loop so the visualiser
                // animates smoothly without blocking other state updates.
                // When paused/stopped, bars decay to zero instead of freezing
                // on the last live frame.
                LaunchedEffect(isBound) {
                    while (true) {
                        if (isBound) {
                            if (isPlaying) {
                                val bands = FloatArray(32)
                                playbackService?.getEngine()?.getSpectrum(bands)
                                spectrumBands = bands
                            } else if (spectrumBands.any { it > 0.001f }) {
                                // Smooth decay so bars animate down rather than snap to 0
                                spectrumBands = FloatArray(32) { i -> spectrumBands[i] * 0.75f }
                            }
                        }
                        delay(33) // ~30 fps
                    }
                }

                // --- Sleep timer countdown and fade-out ---
                // Battery optimisation: poll at 500 ms only during the last 30 s
                // (fade window). Before that, sleep until ~30 s before the deadline
                // so the CPU is not woken every 500 ms while the screen is off.
                LaunchedEffect(sleepTimerEndMs) {
                    if (sleepTimerEndMs <= 0L) {
                        sleepTimerDisplay = ""
                        return@LaunchedEffect
                    }

                    val fadeStartMs = 30_000L
                    while (sleepTimerEndMs > 0L) {
                        val remaining = sleepTimerEndMs - System.currentTimeMillis()
                        if (remaining <= 0L) {
                            if (isBound) {
                                playbackService?.pauseForSleepTimer()
                            }
                            sleepTimerEndMs = 0L
                            sleepTimerDisplay = ""
                            break
                        }
                        sleepTimerDisplay = formatSleepTimerRemaining(remaining)
                        if (remaining < fadeStartMs) {
                            val fade = remaining.toDouble() / fadeStartMs.toDouble()
                            if (isBound) playbackService?.getEngine()?.setVolume(volume * fade)
                            delay(500L)
                        } else {
                            delay(1000L)
                        }
                    }
                }

                // --- Permissions ---
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    delay(250L)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.POST_NOTIFICATIONS,
                                Manifest.permission.READ_MEDIA_AUDIO,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    } else {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        )
                    }
                }

                val playlistFolderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let { folderUri ->
                        contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        lifecycleScope.launch {
                            val newTracks = withContext(Dispatchers.IO) { loadTracksFromTree(folderUri) }
                            val addedCount = addTracksToPlaylist(newTracks)
                            Toast.makeText(applicationContext, "Added $addedCount tracks from ${folderNameFromTreeUri(folderUri)}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val libraryFolderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let { folderUri ->
                        contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val entry = buildLibraryFolderEntry(folderUri)
                        libraryFolders = LibraryFolderEntry.normalize(libraryFolders + entry)
                        Toast.makeText(
                            applicationContext,
                            "Saved library access for ${entry.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // --- Dialogs ---

                // Create Playlist dialog
                if (showCreatePlaylist) {
                    AlertDialog(
                        onDismissRequest = { showCreatePlaylist = false },
                        title = { Text("Create Playlist") },
                        text = {
                            OutlinedTextField(
                                value = playlistName,
                                onValueChange = { playlistName = it },
                                label = { Text("Playlist Name") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val name = playlistName.trim()
                                if (name.isNotEmpty()) {
                                    val db = database
                                        if (db == null) {
                                            Toast.makeText(applicationContext, "Playlist database is still loading", Toast.LENGTH_SHORT).show()
                                        } else {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                db.withTransaction {
                                                    val id = db.playlistDao().insertPlaylist(
                                                        PlaylistEntity(name = name, shuffleEnabled = shuffleEnabled)
                                                    )
                                                    playlist.forEachIndexed { order, track ->
                                                        db.trackDao().insertTrack(
                                                            PlaylistTrackEntity(
                                                                playlistId = id.toInt(),
                                                                uriString = track.uri.toString(),
                                                                title = track.name,
                                                                folder = track.folder,
                                                                durationMs = track.durationMs,
                                                                playOrder = order,
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            Toast.makeText(applicationContext, "Playlist \"$name\" saved", Toast.LENGTH_SHORT).show()
                                        }
                                }
                                showCreatePlaylist = false
                                playlistName = ""
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCreatePlaylist = false; playlistName = "" }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Load / Manage Playlists dialog
                if (showLoadPlaylist) {
                    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
                    var renameText by remember { mutableStateOf("") }

                    if (renameTarget != null) {
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            title = { Text("Rename Playlist") },
                            text = {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    label = { Text("New name") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val newName = renameText.trim()
                                    if (newName.isNotEmpty()) {
                                        val db = database
                                        val id = renameTarget!!.id
                                        if (db == null) {
                                            Toast.makeText(applicationContext, "Playlist database is still loading", Toast.LENGTH_SHORT).show()
                                        } else {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                db.playlistDao().renamePlaylist(id, newName)
                                            }
                                        }
                                    }
                                    renameTarget = null
                                }) { Text("Rename") }
                            },
                            dismissButton = {
                                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
                            }
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = { showLoadPlaylist = false },
                            title = { Text("Playlists") },
                            text = {
                                if (database == null) {
                                    Text("Playlists are loading...")
                                } else if (playlists.isEmpty()) {
                                    Text("No playlists saved yet.")
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                        itemsIndexed(playlists) { _, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        val db = database ?: run {
                                                            Toast.makeText(applicationContext, "Playlist database is still loading", Toast.LENGTH_SHORT).show()
                                                            return@clickable
                                                        }
                                                        showLoadPlaylist = false
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            val tracks = db.trackDao()
                                                                .getTracksForPlaylist(item.id).first()
                                                            var skippedCount = 0
                                                            val loaded = tracks.mapNotNull { t ->
                                                                val uri = Uri.parse(t.uriString)
                                                                if (!canReadTrackUri(uri)) {
                                                                    skippedCount += 1
                                                                    null
                                                                } else {
                                                                    AudioTrack(
                                                                        uri = uri,
                                                                        name = t.title,
                                                                        folder = t.folder,
                                                                        durationMs = t.durationMs,
                                                                    )
                                                                }
                                                            }
                                                            val sorted = sortTracksForMode(loaded)
                                                            lifecycleScope.launch(Dispatchers.Main) {
                                                                playlist = sorted
                                                                currentIndex = -1
                                                                shuffleEnabled = item.shuffleEnabled
                                                                shuffleHistory = emptyList()
                                                                shuffleQueue = emptyList()
                                                                savePlaylistToPrefs(sorted)
                                                                clearPausedTrackState(removeCurrentIndex = true)
                                                                Toast.makeText(
                                                                    this@MainActivity,
                                                                    if (skippedCount > 0) {
                                                                        "Loaded \"${item.name}\" — ${sorted.size} tracks, skipped $skippedCount unavailable"
                                                                    } else {
                                                                        "Loaded \"${item.name}\" — ${sorted.size} tracks"
                                                                    },
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Filled.PlayArrow,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    item.name,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                // Rename button
                                                IconButton(
                                                    onClick = { renameTarget = item; renameText = item.name },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(Icons.Filled.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                                                }
                                                // Delete button
                                                IconButton(
                                                    onClick = {
                                                        val db = database ?: run {
                                                            Toast.makeText(applicationContext, "Playlist database is still loading", Toast.LENGTH_SHORT).show()
                                                            return@IconButton
                                                        }
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            db.withTransaction {
                                                                db.playlistDao().deletePlaylist(item)
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Delete,
                                                        contentDescription = "Delete",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showLoadPlaylist = false }) { Text("Close") }
                            }
                        )
                    }
                }

                // Sleep Timer dialog
                if (showSleepTimer) {
                    val options = listOf(5, 10, 15, 30, 60)
                    AlertDialog(
                        onDismissRequest = { showSleepTimer = false },
                        title = { Text("Sleep Timer") },
                        text = {
                            Column {
                                if (sleepTimerEndMs > 0L) {
                                    Text("Active — remaining: $sleepTimerDisplay")
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = {
                                        cancelSleepTimer()
                                        showSleepTimer = false
                                    }) { Text("Cancel Timer") }
                                } else {
                                    Text("Stop playback after:")
                                    Spacer(Modifier.height(8.dp))
                                    options.forEach { mins ->
                                        TextButton(
                                            onClick = {
                                                sleepTimerEndMs =
                                                    System.currentTimeMillis() + mins * 60_000L
                                                sleepTimerDisplay = formatSleepTimerRemaining(mins * 60_000L.toLong())
                                                showSleepTimer = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("$mins minutes")
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSleepTimer = false }) { Text("Close") }
                        }
                    )
                }

                EqualizerDialog(
                    show = showEqualizer,
                    settings = eqSettings,
                    onDismiss = { showEqualizer = false },
                    onEnabledChange = { enabled ->
                        eqSettings = eqSettings.copy(enabled = enabled).normalized()
                    },
                    onBandGainChange = { bandIndex, gainDb ->
                        eqSettings = eqSettings.withBandGain(bandIndex, gainDb)
                    },
                    onReset = {
                        eqSettings = EqSettings()
                        if (isBound) {
                            playbackService?.resetEqBands()
                        }
                    }
                )

                DlnaBrowserDialog(
                    show = dlnaShow,
                    dlnaScanning = dlnaScanning,
                    dlnaBrowsing = dlnaBrowsing,
                    dlnaServers = dlnaServers,
                    dlnaSelected = dlnaSelected,
                    dlnaTracks = dlnaTracks,
                    dlnaErrorMessage = dlnaErrorMessage,
                    onDismiss = { dlnaShow = false },
                    onRescan = { scanDlnaServers() },
                    onBack = {
                        dlnaSelected = null
                        dlnaTracks = emptyList()
                        dlnaErrorMessage = null
                    },
                    onBrowseServer = { server -> browseDlnaServer(server) },
                    onAddTrack = { track ->
                        val selectedServer = dlnaSelected ?: return@DlnaBrowserDialog
                        val newTrack = AudioTrack(
                            uri = Uri.parse(track.url),
                            name = track.title,
                            folder = selectedServer.friendlyName,
                            durationMs = track.durationMs
                        )
                        val addedCount = addTracksToPlaylist(listOf(newTrack))
                        Toast.makeText(
                            applicationContext,
                            if (addedCount > 0) "Added: ${track.title}" else "Track already in playlist",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onAddAllTracks = {
                        val selectedServer = dlnaSelected ?: return@DlnaBrowserDialog
                        val newTracks = dlnaTracks.map { track ->
                            AudioTrack(
                                uri = Uri.parse(track.url),
                                name = track.title,
                                folder = selectedServer.friendlyName,
                                durationMs = track.durationMs
                            )
                        }
                        val addedCount = addTracksToPlaylist(newTracks)
                        Toast.makeText(applicationContext, "Added $addedCount tracks", Toast.LENGTH_SHORT).show()
                        dlnaShow = false
                    }
                )

                val availableLibraryFolderUris = remember(libraryFolders) {
                    availableLibraryFolders().map { it.uriString }.toSet()
                }

                LibraryFoldersDialog(
                    show = showLibraryFolders,
                    folders = libraryFolders,
                    availableFolderUris = availableLibraryFolderUris,
                    onDismiss = { showLibraryFolders = false },
                    onAddFolder = { libraryFolderPickerLauncher.launch(null) },
                    onBrowseFolder = { entry -> openLibraryFolderBrowser(entry) },
                    onRemoveFolder = { entry -> removeLibraryFolder(entry) }
                )

                BrowseLibraryFolderDialog(
                    show = showBrowseLibraryFolder,
                    rootLabel = browsingLibraryRoot?.label.orEmpty(),
                    currentPath = libraryBrowseStack.map { it.label },
                    entries = libraryBrowserEntries,
                    loading = libraryBrowserLoading,
                    errorMessage = libraryBrowserErrorMessage,
                    canNavigateUp = libraryBrowseStack.size > 1,
                    onDismiss = {
                        showBrowseLibraryFolder = false
                        browsingLibraryRoot = null
                        libraryBrowseStack = emptyList()
                        libraryBrowserEntries = emptyList()
                        libraryBrowserErrorMessage = null
                    },
                    onNavigateUp = { navigateUpLibraryFolder() },
                    onOpenFolder = { entry -> navigateIntoLibraryFolder(entry) },
                    onAddCurrentFolder = { addCurrentLibraryFolderToPlaylist() },
                    onAddTrack = { entry -> addLibraryTrackToPlaylist(entry) }
                )

                // --- Main UI ---
                Scaffold(
                    topBar = {
                        MainTopBar(
                            sleepTimerDisplay = sleepTimerDisplay,
                            showMenu = showMenu,
                            sortMode = playlistSortMode,
                            libraryFolderCount = libraryFolders.size,
                            onToggleMenu = { showMenu = !showMenu },
                            onDismissMenu = { showMenu = false },
                            onAddFolder = { showMenu = false; playlistFolderPickerLauncher.launch(null) },
                            onManageLibraryFolders = { showMenu = false; showLibraryFolders = true },
                            onScanMediaStore = {
                                showMenu = false
                                lifecycleScope.launch {
                                    val tracks = withContext(Dispatchers.IO) { scanMediaStore() }
                                    val sorted = sortTracksForMode(tracks)
                                    playlist = sorted
                                    shuffleHistory = emptyList()
                                    shuffleQueue = emptyList()
                                    savePlaylistToPrefs(sorted)
                                    clearPausedTrackState(removeCurrentIndex = true)
                                    Toast.makeText(this@MainActivity, "Found ${sorted.size} tracks", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSortByName = { showMenu = false; applyPlaylistSort(PlaylistSortMode.NAME) },
                            onSortByNumber = { showMenu = false; applyPlaylistSort(PlaylistSortMode.NUMBER) },
                            onSavePlaylist = { showMenu = false; showCreatePlaylist = true },
                            onLoadPlaylist = { showMenu = false; showLoadPlaylist = true },
                            onShowEqualizer = { showMenu = false; showEqualizer = true },
                            onShowSleepTimer = { showMenu = false; showSleepTimer = true },
                            onCancelSleepTimer = { cancelSleepTimer() },
                            onOpenDlna = {
                                showMenu = false
                                dlnaShow = true
                                if (dlnaServers.isEmpty() && !dlnaScanning) {
                                    scanDlnaServers()
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        PlayerCardSection(
                            currentTrack = currentTrack,
                            sampleRateKhz = sampleRateKhz,
                            bitDepth = bitDepth,
                            replayGainDb = replayGainDb,
                            dsdNativeRate = dsdNativeRate,
                            headsetInfo = headsetInfo,
                            bluetoothCodec = bluetoothCodec,
                            isPlaying = isPlaying,
                            spectrumBands = spectrumBands,
                            progressMs = progressMs,
                            durationMs = durationMs,
                            speedMult = speedMult,
                            speedMode = speedMode,
                            playbackContentMode = playbackContentMode,
                            volume = volume,
                            currentIndex = currentIndex,
                            playlistSize = playlist.size,
                            shuffleEnabled = shuffleEnabled,
                            shuffleHasHistory = shuffleHistory.isNotEmpty(),
                            onPrevious = { playPreviousFromUser() },
                            onTogglePlayPause = {
                                if (isPlaying) {
                                    isPlaying = false
                                    if (isBound) {
                                        playbackService?.pausePlayback()
                                    } else {
                                        ensurePlaybackServiceConnection {
                                            playbackService?.pausePlayback()
                                        }
                                    }
                                    if (currentIndex in playlist.indices && progressMs > 2000f) {
                                        val currentUri = playlist[currentIndex].uri
                                        saveTrackPosition(currentUri, progressMs)
                                        savePausedTrackState(currentUri, currentIndex, progressMs)
                                    }
                                } else if (currentTrack != null) {
                                    isPlaying = true
                                    if (!playbackContentMode.remembersTrackProgress) {
                                        clearPausedTrackState()
                                    }
                                    if (isBound) {
                                        playbackService?.resumePlayback()
                                    } else {
                                        ensurePlaybackServiceConnection {
                                            playbackService?.resumePlayback()
                                        }
                                    }
                                } else if (playlist.isNotEmpty()) {
                                    playAtIndex(0)
                                }
                            },
                            onNext = { playNextFromUser() },
                            onStop = {
                                if (currentIndex in playlist.indices && progressMs > 2000f) {
                                    saveTrackPosition(playlist[currentIndex].uri, progressMs)
                                }
                                clearPausedTrackState()
                                if (isBound) playbackService?.stopPlayback()
                                isPlaying = false
                                progressMs = 0f
                            },
                            onPlaybackContentModeChanged = { newMode ->
                                playbackContentMode = newMode
                            },
                            repeatMode = repeatMode,
                            onRepeatModeChange = { repeatMode = (repeatMode + 1) % 3 },
                            onToggleShuffle = {
                                shuffleEnabled = !shuffleEnabled
                                shuffleHistory = emptyList()
                                shuffleQueue = if (shuffleEnabled && currentIndex in playlist.indices) {
                                    PlaybackShuffle.buildQueue(currentIndex, playlist.size)
                                } else {
                                    emptyList()
                                }
                            },
                            onSeekChanged = {
                                progressMs = it
                                isSeeking = true
                            },
                            onSeekFinished = {
                                if (isBound) playbackService?.getEngine()?.seekTo(progressMs.toDouble())
                                isSeeking = false
                            },
                            onSpeedChanged = { newVal ->
                                speedMult = PlaybackSpeed.clamp(newVal)
                                if (isBound) playbackService?.setPlaybackSpeed(speedMult.toDouble())
                            },
                            onSpeedModeChanged = { newMode ->
                                speedMode = newMode
                                if (isBound) playbackService?.setPlaybackSpeedMode(newMode)
                            },
                            onSpeedReset = {
                                speedMult = 1.0f
                                if (isBound) playbackService?.setPlaybackSpeed(1.0)
                            },
                            onVolumeChanged = { newVal ->
                                volume = newVal
                                if (sleepTimerEndMs == 0L && isBound) {
                                    playbackService?.setVolume(newVal.toDouble())
                                }
                            }
                        )

                        PlaylistSection(
                            modifier = Modifier.weight(1f),
                            playlist = playlist,
                            playlistSummary = playlistSummary,
                            currentIndex = currentIndex,
                            isPlaying = isPlaying,
                            playedIndices = playedIndices,
                            onClear = {
                                if (isBound) playbackService?.stopPlayback()
                                playlist = emptyList()
                                currentIndex = -1
                                shuffleHistory = emptyList()
                                shuffleQueue = emptyList()
                                isPlaying = false
                                progressMs = 0f
                                playedUris = emptySet()
                                prefs.edit().remove("played_uris").apply()
                                statePrefs.edit().remove("playlist_json").apply()
                                clearPausedTrackState(removeCurrentIndex = true)
                            },
                            onSelectTrack = { index -> playAtIndex(index) }
                        )
                    }
                }
            }
        }
    }

    // --- Helpers ---

    private fun formatTime(ms: Float): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun folderNameFromTreeUri(treeUri: Uri): String {
        return SafTreeScanner.folderNameFromTreeUri(treeUri)
    }

    private fun canReadTrackUri(uri: Uri): Boolean {
        return try {
            when (uri.scheme) {
                "content" -> contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                "file" -> uri.path?.let { File(it).canRead() } ?: false
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Recursively scans [treeUri] (and all subdirectories up to [depth] levels deep).
     * One call from the top — Android's persisted tree permission covers all children,
     * so no extra permission prompts for subfolders.
     *
     * [docId]        — current directory document ID (default = tree root)
     * [folderLabel]  — display name shown in the playlist for tracks in this directory
     * [depth]        — recursion guard (max 10 levels)
     */
    private fun loadTracksFromTree(
        treeUri: Uri,
        docId: String = DocumentsContract.getTreeDocumentId(treeUri),
        folderLabel: String = folderNameFromTreeUri(treeUri),
        depth: Int = 0
    ): List<AudioTrack> {
        return SafTreeScanner.loadTracksFromTree(contentResolver, treeUri, docId, folderLabel, depth)
    }

    private fun listLibraryFolderChildren(
        treeUri: Uri,
        docId: String,
        folderLabel: String,
    ): List<LibraryBrowserEntry> {
        return SafTreeScanner.listLibraryFolderChildren(contentResolver, treeUri, docId, folderLabel)
    }

    private fun scanMediaStore(): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        try {
            val folderCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.RELATIVE_PATH
            else
                MediaStore.Audio.Media.DATA
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                folderCol
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.dsf' OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.dff'"
            val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { cursor ->
                val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val folderColIdx = cursor.getColumnIndexOrThrow(folderCol)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Track $id"
                    val durationMs = cursor.getLong(durationCol)
                    val uri  = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val folderStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getString(folderColIdx)?.trimEnd('/')?.substringAfterLast('/') ?: ""
                    } else {
                        cursor.getString(folderColIdx)?.substringBeforeLast('/')?.substringAfterLast('/') ?: ""
                    }
                    result.add(AudioTrack(uri, name, folderStr, durationMs))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun probeDurationMs(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.use {
                it.setDataSource(this, uri)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
