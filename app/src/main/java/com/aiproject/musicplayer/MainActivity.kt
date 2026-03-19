package com.aiproject.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

data class AudioTrack(val uri: Uri, val name: String)

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
                // --- State ---
                var playlist by remember { mutableStateOf(listOf<AudioTrack>()) }
                var currentIndex by remember { mutableStateOf(-1) }
                var isPlaying by remember { mutableStateOf(false) }
                var volume by remember { mutableFloatStateOf(1.0f) }
                var progressMs by remember { mutableStateOf(0f) }
                var durationMs by remember { mutableStateOf(1f) }
                var isSeeking by remember { mutableStateOf(false) }
                var speedMult by remember { mutableFloatStateOf(1.0f) }
                var sampleRateKhz by remember { mutableStateOf("") }
                var bitDepth by remember { mutableStateOf("") }

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

                // Helper to play a track by index
                fun playAtIndex(index: Int) {
                    if (index !in playlist.indices) return
                    currentIndex = index
                    isPlaying = true   // optimistic: engine confirms via poll loop
                    val track = playlist[index]
                    if (isBound) {
                        playbackService?.playTrack(track.uri, this@MainActivity, track.name)
                        playbackService?.setPlaybackSpeed(speedMult.toDouble())
                        playbackService?.setVolume(volume.toDouble())
                    }
                }

                // --- Continuous update loop: position, playing state, metadata ---
                LaunchedEffect(isBound, currentIndex) {
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

                                // Auto-advance to next track when current finishes
                                if (!isPlaying && currentIndex >= 0 && progressMs > 0f
                                    && durationMs > 0f
                                    && (durationMs - progressMs) < 1000f
                                ) {
                                    val nextIdx = currentIndex + 1
                                    if (nextIdx in playlist.indices) {
                                        playAtIndex(nextIdx)
                                    } else {
                                        // End of playlist — reset
                                        currentIndex = -1
                                        progressMs = 0f
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
                    if (loadedTracks.isNotEmpty()) playlist = loadedTracks
                }

                val folderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let {
                        contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val newTracks = loadTracksFromTree(it)
                        playlist = playlist + newTracks
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
                            title = { Text("64-Bit Audiophile Player") },
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {

                                // Track name
                                Text(
                                    text = currentTrack?.name ?: "No track selected",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Metadata: sample rate + bit depth
                                if (sampleRateKhz.isNotEmpty() || bitDepth.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = listOf(sampleRateKhz, bitDepth)
                                            .filter { it.isNotEmpty() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                // Progress bar and timestamps
                                val progStr = formatTime(progressMs)
                                val durStr = formatTime(durationMs)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(progStr, style = MaterialTheme.typography.labelSmall)
                                    Text(durStr, style = MaterialTheme.typography.labelSmall)
                                }
                                Slider(
                                    value = progressMs,
                                    onValueChange = { progressMs = it; isSeeking = true },
                                    onValueChangeFinished = {
                                        if (isBound) playbackService?.getEngine()?.seekTo(progressMs.toDouble())
                                        isSeeking = false
                                    },
                                    valueRange = 0f..durationMs.coerceAtLeast(1f),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(Modifier.height(4.dp))

                                // Transport controls: Prev | Play/Pause | Next | Stop
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Previous
                                    IconButton(
                                        onClick = {
                                            val prev = currentIndex - 1
                                            if (prev >= 0) playAtIndex(prev)
                                        },
                                        enabled = currentIndex > 0
                                    ) {
                                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                                    }

                                    // Play / Pause
                                    FilledIconButton(
                                        onClick = {
                                            if (isBound) {
                                                if (isPlaying) {
                                                    playbackService?.getEngine()?.pause()
                                                } else if (currentTrack != null) {
                                                    playbackService?.getEngine()?.play()
                                                } else if (playlist.isNotEmpty()) {
                                                    playAtIndex(0)
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }

                                    // Next
                                    IconButton(
                                        onClick = {
                                            val next = currentIndex + 1
                                            if (next in playlist.indices) playAtIndex(next)
                                        },
                                        enabled = currentIndex < playlist.size - 1
                                    ) {
                                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                                    }

                                    // Stop
                                    IconButton(onClick = {
                                        if (isBound) {
                                            playbackService?.stopPlayback()
                                        }
                                        isPlaying = false
                                        progressMs = 0f
                                    }) {
                                        Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(28.dp))
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

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

                        // --- Playlist ---
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
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
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                itemsIndexed(playlist) { index, track ->
                                    val isCurrentTrack = index == currentIndex
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                track.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        leadingContent = {
                                            if (isCurrentTrack && isPlaying) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = "Playing",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                Text(
                                                    "${index + 1}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                    modifier = Modifier.width(24.dp)
                                                )
                                            }
                                        },
                                        modifier = Modifier
                                            .clickable { playAtIndex(index) }
                                            .then(
                                                if (isCurrentTrack)
                                                    Modifier.background(
                                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                                    )
                                                else Modifier
                                            )
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

    private fun loadTracksFromTree(treeUri: Uri): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
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
                        result.add(AudioTrack(uri, name))
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
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, sortOrder
            )?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Track $id"
                    val uri  = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    result.add(AudioTrack(uri, name))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
