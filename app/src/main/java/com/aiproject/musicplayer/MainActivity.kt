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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.aiproject.musicplayer.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class AudioTrack(val uri: Uri, val name: String, val folder: String = "")

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
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
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

class MainActivity : ComponentActivity() {

    private var playbackService: PlaybackService? = null
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PlaybackService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                // --- Persistent state prefs ---
                val statePrefs = remember { getSharedPreferences("player_state", MODE_PRIVATE) }

                // Helper to save/load playlist as JSON
                fun savePlaylistToPrefs(tracks: List<AudioTrack>) {
                    val arr = JSONArray()
                    tracks.forEach { t ->
                        arr.put(JSONObject().put("uri", t.uri.toString()).put("name", t.name).put("folder", t.folder))
                    }
                    statePrefs.edit().putString("playlist_json", arr.toString()).apply()
                }
                fun loadPlaylistFromPrefs(): List<AudioTrack> {
                    val json = statePrefs.getString("playlist_json", null) ?: return emptyList()
                    return try {
                        val arr = JSONArray(json)
                        (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            AudioTrack(Uri.parse(o.getString("uri")), o.getString("name"), o.optString("folder", ""))
                        }
                    } catch (_: Exception) { emptyList() }
                }

                // --- State ---
                var playlist by remember { mutableStateOf(loadPlaylistFromPrefs()) }
                var currentIndex by remember { mutableStateOf(statePrefs.getInt("current_index", -1)) }
                var isPlaying by remember { mutableStateOf(false) }
                var isLoadingTrack by remember { mutableStateOf(false) }
                var positionRestored by remember { mutableStateOf(false) }
                var volume by remember { mutableFloatStateOf(1.0f) }
                var progressMs by remember { mutableStateOf(0f) }
                var durationMs by remember { mutableStateOf(1f) }
                var isSeeking by remember { mutableStateOf(false) }
                var speedMult by remember { mutableFloatStateOf(1.0f) }
                var sampleRateKhz by remember { mutableStateOf("") }
                var bitDepth by remember { mutableStateOf("") }
                var spectrumBands by remember { mutableStateOf(FloatArray(32)) }
                var replayGainDb  by remember { mutableFloatStateOf(0f) }

                // Repeat mode: 0=off  1=repeat one  2=repeat all
                var repeatMode by remember { mutableIntStateOf(0) }

                // Per-track position bookmarks (key = URI hashcode)
                fun saveTrackPosition(uri: Uri, posMs: Float) {
                    if (posMs > 2000f)
                        statePrefs.edit().putFloat("pos_${uri.hashCode()}", posMs).apply()
                }
                fun loadTrackPosition(uri: Uri): Float =
                    statePrefs.getFloat("pos_${uri.hashCode()}", 0f)
                fun clearTrackPosition(uri: Uri) {
                    statePrefs.edit().remove("pos_${uri.hashCode()}").apply()
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

                // Played-track indicator — persisted in SharedPreferences by URI
                // so it survives app restarts and Activity recreation.
                // Useful for audiobooks: shows which chapters were already heard.
                val prefs = remember { getSharedPreferences("audiobook_progress", MODE_PRIVATE) }
                var playedUris by remember {
                    mutableStateOf(prefs.getStringSet("played_uris", emptySet<String>())
                        ?.toSet() ?: emptySet())
                }
                // Derive index-set from current playlist + persisted URIs
                val playedIndices = remember(playlist, playedUris) {
                    playlist.indices.filter { i ->
                        playlist[i].uri.toString() in playedUris
                    }.toSet()
                }

                // Menu / Dialog state
                var showMenu by remember { mutableStateOf(false) }
                var showCreatePlaylist by remember { mutableStateOf(false) }
                var playlistName by remember { mutableStateOf("") }
                var showLoadPlaylist by remember { mutableStateOf(false) }
                var showSleepTimer by remember { mutableStateOf(false) }
                var sleepTimerEndMs by remember { mutableLongStateOf(0L) }
                var sleepTimerDisplay by remember { mutableStateOf("") }

                // DB
                val db = remember { MusicDatabase.getDatabase(applicationContext) }
                val playlists by db.playlistDao().getAllPlaylists()
                    .collectAsState(initial = emptyList())

                val currentTrack = if (currentIndex in playlist.indices) playlist[currentIndex] else null

                // Helper to play a track by index.
                // The heavy part (file scan + decode init) runs on the IO dispatcher
                // so the main thread is never frozen.  A loadMutex in PlaybackService
                // ensures only one native load runs at a time.
                fun playAtIndex(index: Int) {
                    if (index !in playlist.indices) return
                    if (isLoadingTrack) return
                    // Save position of the track we're leaving
                    if (currentIndex in playlist.indices && progressMs > 2000f)
                        saveTrackPosition(playlist[currentIndex].uri, progressMs)
                    currentIndex = index
                    isPlaying = true
                    isLoadingTrack = true
                    progressMs = 0f
                    durationMs = 1f
                    positionRestored = true
                    statePrefs.edit().putInt("current_index", index).apply()
                    val track = playlist[index]
                    val uriStr = track.uri.toString()
                    if (uriStr !in playedUris) {
                        playedUris = playedUris + uriStr
                        prefs.edit().putStringSet("played_uris", playedUris).apply()
                    }
                    // Load saved chapter position for this track
                    val resumePos = loadTrackPosition(track.uri)
                    if (isBound) {
                        lifecycleScope.launch {
                            try {
                                playbackService?.playTrack(
                                    track.uri, this@MainActivity, track.name, resumePos.toDouble()
                                )
                                playbackService?.setPlaybackSpeed(speedMult.toDouble())
                                playbackService?.setVolume(volume.toDouble())
                            } finally {
                                isLoadingTrack = false
                            }
                        }
                    } else {
                        isLoadingTrack = false
                    }
                }

                // Wire up notification buttons and track-end callback.
                // SideEffect runs after every recompose so callbacks always capture latest state.
                SideEffect {
                    if (isBound) {
                        playbackService?.skipToNextCallback = {
                            val next = currentIndex + 1
                            if (next in playlist.indices)
                                lifecycleScope.launch(Dispatchers.Main) { playAtIndex(next) }
                        }
                        playbackService?.skipToPreviousCallback = {
                            val prev = currentIndex - 1
                            if (prev >= 0)
                                lifecycleScope.launch(Dispatchers.Main) { playAtIndex(prev) }
                        }
                        playbackService?.nextTrackProvider = {
                            val next = currentIndex + 1
                            if (next in playlist.indices) playlist[next].uri else null
                        }
                        playbackService?.onGaplessAdvanced = {
                            val next = currentIndex + 1
                            if (next in playlist.indices) {
                                // Save position of old track (it finished)
                                if (currentIndex in playlist.indices)
                                    clearTrackPosition(playlist[currentIndex].uri)
                                currentIndex = next
                                statePrefs.edit().putInt("current_index", next).apply()
                                progressMs = 0f
                                val track = playlist[next]
                                if (track.uri.toString() !in playedUris) {
                                    playedUris = playedUris + track.uri.toString()
                                    prefs.edit().putStringSet("played_uris", playedUris).apply()
                                }
                            }
                        }
                        // Track finished naturally → advance (respecting repeat mode)
                        playbackService?.onTrackCompleted = {
                            // Clear saved position for this track since it completed
                            if (currentIndex in playlist.indices)
                                clearTrackPosition(playlist[currentIndex].uri)
                            when (repeatMode) {
                                1 -> lifecycleScope.launch(Dispatchers.Main) {
                                    // Repeat one — replay same track from start
                                    playAtIndex(currentIndex)
                                }
                                2 -> lifecycleScope.launch(Dispatchers.Main) {
                                    // Repeat all — loop around
                                    val next = if (currentIndex + 1 < playlist.size) currentIndex + 1 else 0
                                    playAtIndex(next)
                                }
                                else -> {
                                    val next = currentIndex + 1
                                    if (next in playlist.indices) {
                                        lifecycleScope.launch(Dispatchers.Main) { playAtIndex(next) }
                                    } else {
                                        isPlaying = false; currentIndex = -1; progressMs = 0f
                                        statePrefs.edit().remove("current_index").apply()
                                    }
                                }
                            }
                        }
                    }
                }

                // Restore currentIndex after Activity recreation.
                LaunchedEffect(isBound, playlist.size) {
                    if (isBound && currentIndex == -1 && playlist.isNotEmpty()) {
                        val title = playbackService?.currentTitle
                        if (!title.isNullOrEmpty()) {
                            val idx = playlist.indexOfFirst { it.name == title }
                            if (idx >= 0) {
                                currentIndex = idx
                                isPlaying = playbackService?.getEngine()?.isPlaying() == true
                            }
                        }
                    }
                }

                // Restore saved playback position once after binding (audiobook resume).
                LaunchedEffect(isBound) {
                    if (isBound && !positionRestored && currentIndex >= 0) {
                        positionRestored = true
                        val savedPos = statePrefs.getFloat("saved_position_ms", 0f)
                        if (savedPos > 2000f) {  // only restore if more than 2 seconds in
                            delay(800) // let the engine settle after binding
                            playbackService?.getEngine()?.seekTo(savedPos.toDouble())
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

                                // Poll spectrum (every frame when playing)
                                if (isPlaying) {
                                    val bands = FloatArray(32)
                                    engine.getSpectrum(bands)
                                    spectrumBands = bands
                                }
                                // Read ReplayGain once per track load
                                val rg = engine.getReplayGainDb()
                                if (rg != replayGainDb) replayGainDb = rg

                                // Save position every ~5 seconds while playing (audiobook resume)
                                if (isPlaying && progressMs > 2000f) {
                                    saveCounter++
                                    if (saveCounter >= 10) { // 10 × 500ms = 5s
                                        saveCounter = 0
                                        statePrefs.edit()
                                            .putFloat("saved_position_ms", progressMs)
                                            .putInt("current_index", currentIndex)
                                            .apply()
                                    }
                                }
                            }
                        }
                        delay(500)
                    }
                }

                // --- Sleep timer countdown and fade-out ---
                LaunchedEffect(sleepTimerEndMs) {
                    if (sleepTimerEndMs > 0L) {
                        val fadeStartMs = 30_000L
                        while (true) {
                            val remaining = sleepTimerEndMs - System.currentTimeMillis()
                            if (remaining <= 0L) {
                                // Stop playback
                                if (isBound) {
                                    playbackService?.getEngine()?.pause()
                                    playbackService?.getEngine()?.setVolume(volume.toDouble())
                                }
                                sleepTimerEndMs = 0L
                                sleepTimerDisplay = ""
                                break
                            }
                            // Fade out in last 30 seconds
                            if (remaining < fadeStartMs) {
                                val fade = remaining.toDouble() / fadeStartMs.toDouble()
                                if (isBound) playbackService?.getEngine()?.setVolume(volume * fade)
                            }
                            val mins = remaining / 60_000L
                            val secs = (remaining % 60_000L) / 1_000L
                            sleepTimerDisplay = "%d:%02d".format(mins, secs)
                            delay(500)
                        }
                    }
                }

                // --- Permissions + load persisted folder tracks ---
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.POST_NOTIFICATIONS,
                                Manifest.permission.READ_MEDIA_AUDIO
                            )
                        )
                    } else {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        )
                    }

                    // Only load from persisted folder URIs if we don't have a saved playlist
                    if (playlist.isEmpty()) {
                        val persistedUris = contentResolver.persistedUriPermissions
                        val loadedTracks = mutableListOf<AudioTrack>()
                        for (permission in persistedUris) {
                            try {
                                if (permission.uri.toString().contains("tree")) {
                                    loadedTracks += loadTracksFromTree(permission.uri)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        if (loadedTracks.isNotEmpty()) {
                            playlist = loadedTracks
                            savePlaylistToPrefs(loadedTracks)
                        }
                    }
                }

                val folderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let {
                        contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val newTracks = loadTracksFromTree(it)
                        val updated = playlist + newTracks
                        playlist = updated
                        savePlaylistToPrefs(updated)
                        Toast.makeText(this@MainActivity, "Added ${newTracks.size} tracks", Toast.LENGTH_SHORT).show()
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
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val id = db.playlistDao().insertPlaylist(PlaylistEntity(name = name))
                                        playlist.forEach { track ->
                                            db.trackDao().insertTrack(
                                                PlaylistTrackEntity(
                                                    playlistId = id.toInt(),
                                                    uriString = track.uri.toString(),
                                                    title = track.name
                                                )
                                            )
                                        }
                                    }
                                    Toast.makeText(this@MainActivity, "Playlist \"$name\" saved", Toast.LENGTH_SHORT).show()
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
                                        val id = renameTarget!!.id
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            db.playlistDao().renamePlaylist(id, newName)
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
                                if (playlists.isEmpty()) {
                                    Text("No playlists saved yet.")
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                        itemsIndexed(playlists) { _, item ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            val tracks = db.trackDao()
                                                                .getTracksForPlaylist(item.id).first()
                                                            val loaded = tracks.map { t ->
                                                                AudioTrack(Uri.parse(t.uriString), t.title)
                                                            }
                                                            lifecycleScope.launch(Dispatchers.Main) {
                                                                playlist = loaded
                                                                currentIndex = -1
                                                                showLoadPlaylist = false
                                                                savePlaylistToPrefs(loaded)
                                                                statePrefs.edit()
                                                                    .remove("current_index")
                                                                    .remove("saved_position_ms")
                                                                    .apply()
                                                                Toast.makeText(
                                                                    this@MainActivity,
                                                                    "Loaded \"${item.name}\" — ${loaded.size} tracks",
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
                                                        lifecycleScope.launch(Dispatchers.IO) {
                                                            db.trackDao().deleteTracksForPlaylist(item.id)
                                                            db.playlistDao().deletePlaylist(item)
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
                                        sleepTimerEndMs = 0L
                                        sleepTimerDisplay = ""
                                        if (isBound) playbackService?.getEngine()?.setVolume(volume.toDouble())
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

                // --- Main UI ---
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("HiFi Player") },
                            actions = {
                                // Sleep timer indicator
                                if (sleepTimerDisplay.isNotEmpty()) {
                                    Text(
                                        "⏱ $sleepTimerDisplay",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                                IconButton(onClick = { showMenu = !showMenu }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Add Folder") },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                        onClick = { showMenu = false; folderPickerLauncher.launch(null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Scan MediaStore") },
                                        leadingIcon = { Icon(Icons.Filled.LibraryMusic, null) },
                                        onClick = {
                                            showMenu = false
                                            val tracks = scanMediaStore()
                                            playlist = tracks
                                            savePlaylistToPrefs(tracks)
                                            statePrefs.edit()
                                                .remove("current_index")
                                                .remove("saved_position_ms")
                                                .apply()
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Found ${tracks.size} tracks",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Save Playlist") },
                                        leadingIcon = { Icon(Icons.Filled.Save, null) },
                                        onClick = { showMenu = false; showCreatePlaylist = true }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Load Playlist") },
                                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                        onClick = { showMenu = false; showLoadPlaylist = true }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Sleep Timer") },
                                        leadingIcon = { Icon(Icons.Filled.Timer, null) },
                                        onClick = { showMenu = false; showSleepTimer = true }
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {

                        // --- Player card ---
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

                                // Track name + file metadata on same row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = currentTrack?.name ?: "No track selected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (sampleRateKhz.isNotEmpty() || bitDepth.isNotEmpty()) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = listOf(sampleRateKhz, bitDepth)
                                                .filter { it.isNotEmpty() }.joinToString("·"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (replayGainDb != 0f) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (replayGainDb > 0f) "+%.1f dB".format(replayGainDb)
                                                   else "%.1f dB".format(replayGainDb),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }

                                // Headset / audio device info bar
                                headsetInfo?.let { info ->
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val (icon, tint) = when {
                                            info.connectionType.startsWith("USB") ->
                                                Icons.Filled.Usb to MaterialTheme.colorScheme.tertiary
                                            info.connectionType.startsWith("Bluetooth") ->
                                                Icons.Filled.BluetoothConnected to MaterialTheme.colorScheme.primary
                                            else ->
                                                Icons.Filled.Headphones to MaterialTheme.colorScheme.secondary
                                        }
                                        Icon(icon, contentDescription = null,
                                            modifier = Modifier.size(12.dp), tint = tint)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "${info.name}  ·  ${info.summary}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Spectrum analyzer bars
                                if (isPlaying || spectrumBands.any { it > 0.02f }) {
                                    Spacer(Modifier.height(4.dp))
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                    ) {
                                        val bands = spectrumBands
                                        val barW  = size.width / bands.size
                                        bands.forEachIndexed { i, v ->
                                            val barH = (v * size.height).coerceAtLeast(2f)
                                            // Hue: 200° (blue-cyan) → 0° (red) based on amplitude
                                            val hue = 200f * (1f - v)
                                            drawRect(
                                                color = androidx.compose.ui.graphics.Color.hsv(
                                                    hue, 0.75f, 0.85f
                                                ),
                                                topLeft = Offset(i * barW + 1f, size.height - barH),
                                                size    = Size(barW - 2f, barH)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                // Progress: time labels inline with slider
                                val progStr = formatTime(progressMs)
                                val durStr  = formatTime(durationMs)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(progStr, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.width(36.dp))
                                    Slider(
                                        value = progressMs,
                                        onValueChange = { progressMs = it; isSeeking = true },
                                        onValueChangeFinished = {
                                            if (isBound) playbackService?.getEngine()?.seekTo(progressMs.toDouble())
                                            isSeeking = false
                                        },
                                        valueRange = 0f..durationMs.coerceAtLeast(1f),
                                        modifier = Modifier.weight(1f).height(28.dp)
                                    )
                                    Text(durStr, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.width(36.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                }

                                // Transport controls: Prev | Play/Pause | Next | Stop
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { val prev = currentIndex - 1; if (prev >= 0) playAtIndex(prev) },
                                        enabled = currentIndex > 0
                                    ) {
                                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(30.dp))
                                    }

                                    FilledIconButton(
                                        onClick = {
                                            if (isBound) {
                                                if (isPlaying) {
                                                    playbackService?.pausePlayback()
                                                    statePrefs.edit()
                                                        .putFloat("saved_position_ms", progressMs)
                                                        .putInt("current_index", currentIndex)
                                                        .apply()
                                                } else if (currentTrack != null) {
                                                    playbackService?.getEngine()?.play()
                                                } else if (playlist.isNotEmpty()) {
                                                    playAtIndex(0)
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { val next = currentIndex + 1; if (next in playlist.indices) playAtIndex(next) },
                                        enabled = currentIndex < playlist.size - 1
                                    ) {
                                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(30.dp))
                                    }

                                    IconButton(onClick = {
                                        if (currentIndex in playlist.indices && progressMs > 2000f)
                                            saveTrackPosition(playlist[currentIndex].uri, progressMs)
                                        if (isBound) playbackService?.stopPlayback()
                                        isPlaying = false; progressMs = 0f
                                    }) {
                                        Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(24.dp))
                                    }

                                    // Repeat mode: off → one → all → off
                                    IconButton(onClick = { repeatMode = (repeatMode + 1) % 3 }) {
                                        Icon(
                                            imageVector = if (repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                            contentDescription = "Repeat",
                                            modifier = Modifier.size(22.dp),
                                            tint = if (repeatMode > 0)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        )
                                    }
                                }

                                var showAdvanced by remember { mutableStateOf(false) }

                                TextButton(
                                    onClick = { showAdvanced = !showAdvanced },
                                    modifier = Modifier.fillMaxWidth().height(28.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text(
                                        if (showAdvanced) "▲ speed / volume" else "▼ speed / volume",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }

                                if (showAdvanced) {
                                    // Speed slider
                                    Text(
                                        text = "Speed: ${"%.2f".format(speedMult)}x",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Slider(
                                        value = speedMult,
                                        onValueChange = { newVal ->
                                            speedMult = newVal
                                            if (isBound) playbackService?.setPlaybackSpeed(newVal.toDouble())
                                        },
                                        valueRange = 0.5f..2.5f,
                                        steps = 7  // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    // Volume slider
                                    Text(
                                        text = "Volume: ${(volume * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Slider(
                                        value = volume,
                                        onValueChange = { newVal ->
                                            volume = newVal
                                            if (sleepTimerEndMs == 0L) {
                                                // Only update volume directly if sleep timer is not fading
                                                if (isBound) playbackService?.setVolume(newVal.toDouble())
                                            }
                                        },
                                        valueRange = 0f..1f
                                    )
                                }
                            }
                        }

                        // --- Playlist ---
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Playlist · ${playlist.size} tracks",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (playlist.isNotEmpty()) {
                                TextButton(onClick = {
                                    if (isBound) playbackService?.stopPlayback()
                                    playlist = emptyList()
                                    currentIndex = -1
                                    isPlaying = false
                                    progressMs = 0f
                                    playedUris = emptySet()
                                    prefs.edit().remove("played_uris").apply()
                                    statePrefs.edit()
                                        .remove("playlist_json")
                                        .remove("current_index")
                                        .remove("saved_position_ms")
                                        .apply()
                                }) {
                                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (playlist.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.LibraryMusic,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Add a folder or scan MediaStore",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        } else {
                            val listState = rememberLazyListState()
                            // Auto-scroll to current track whenever it changes
                            LaunchedEffect(currentIndex) {
                                if (currentIndex >= 0 && currentIndex < playlist.size) {
                                    listState.animateScrollToItem(
                                        index = currentIndex,
                                        scrollOffset = -120
                                    )
                                }
                            }
                            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                itemsIndexed(playlist) { index, track ->
                                    val isCurrentTrack = index == currentIndex
                                    val isPlayed = index in playedIndices && !isCurrentTrack

                                    // Color scheme:
                                    //  • Current track  → primary (blue)
                                    //  • Played track   → tertiary dimmed (green-ish = "done")
                                    //  • Unplayed track → default onSurface
                                    val textColor = when {
                                        isCurrentTrack -> MaterialTheme.colorScheme.primary
                                        isPlayed       -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                                        else           -> MaterialTheme.colorScheme.onSurface
                                    }
                                    val rowBackground = when {
                                        isCurrentTrack -> Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        )
                                        isPlayed       -> Modifier.background(
                                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                                        )
                                        else           -> Modifier
                                    }

                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                track.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
                                                color = textColor
                                            )
                                        },
                                        supportingContent = if (track.folder.isNotEmpty()) {
                                            { Text(track.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        } else null,
                                        leadingContent = {
                                            when {
                                                isCurrentTrack && isPlaying -> Icon(
                                                    Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = "Playing",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                isPlayed -> Icon(
                                                    Icons.Filled.CheckCircle,
                                                    contentDescription = "Played",
                                                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                else -> Text(
                                                    "${index + 1}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                    modifier = Modifier.width(24.dp)
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .clickable { playAtIndex(index) }
                                            .then(rowBackground)
                                    )
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                            }
                        }
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
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Music/Audiobooks"
            val decoded = Uri.decode(docId) // decode %3A etc
            // Take the last path component after the last /
            decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { decoded }
        } catch (_: Exception) { "" }
    }

    private fun loadTracksFromTree(treeUri: Uri): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        val folderName = folderNameFromTreeUri(treeUri)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeCol) ?: continue
                    if (mime.startsWith("audio/")) {
                        val docId = cursor.getString(idCol)
                        val name  = cursor.getString(nameCol) ?: docId
                        val uri   = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        result.add(AudioTrack(uri, name, folderName))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
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
                MediaStore.Audio.Media.MIME_TYPE,
                folderCol
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { cursor ->
                val idCol      = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val folderColIdx = cursor.getColumnIndexOrThrow(folderCol)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Track $id"
                    val uri  = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    val folderStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getString(folderColIdx)?.trimEnd('/')?.substringAfterLast('/') ?: ""
                    } else {
                        cursor.getString(folderColIdx)?.substringBeforeLast('/')?.substringAfterLast('/') ?: ""
                    }
                    result.add(AudioTrack(uri, name, folderStr))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
