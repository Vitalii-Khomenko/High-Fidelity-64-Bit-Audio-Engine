package com.aiproject.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.aiproject.musicplayer.db.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.items
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
                var playlist by remember { mutableStateOf(listOf<AudioTrack>()) }
                var currentTrack by remember { mutableStateOf<AudioTrack?>(null) }
                var volume by remember { mutableFloatStateOf(1.0f) }
                
                var progressMs by remember { mutableStateOf(0f) }
                var durationMs by remember { mutableStateOf(1f) }
                var isSeeking by remember { mutableStateOf(false) }

                var speedMult by remember { mutableFloatStateOf(1.0f) }
                var showMenu by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var showLoadPlaylist by remember { mutableStateOf(false) }
    
    val db = remember { MusicDatabase.getDatabase(applicationContext) }
    val playlists by db.playlistDao().getAllPlaylists().collectAsState(initial = emptyList())

                // Continuous UI update loop for duration/progress
                LaunchedEffect(isBound, currentTrack) {
                    while (true) {
                        if (isBound && !isSeeking) {
                            val engine = playbackService?.getEngine()
                            if (engine != null) {
                                val current = engine.getPositionMs().toFloat()
                                val total = engine.getDurationMs().toFloat()
                                if (total > 0f) {
                                    durationMs = total
                                    if (current in 0f..total) {
                                        progressMs = current
                                    }
                                }
                            }
                        }
                        delay(500)
                    }
                }

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
                                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                                    permission.uri,
                                    DocumentsContract.getTreeDocumentId(permission.uri)
                                )
                                contentResolver.query(childrenUri, arrayOf(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE
                                ), null, null, null)?.use { cursor ->
                                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                    val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                                    while (cursor.moveToNext()) {
                                        val mimeType = cursor.getString(mimeColumn)
                                        if (mimeType != null && mimeType.startsWith("audio/")) {
                                            val docId = cursor.getString(idColumn)
                                            val name = cursor.getString(nameColumn)
                                            val trackUri = DocumentsContract.buildDocumentUriUsingTree(permission.uri, docId)
                                            loadedTracks.add(AudioTrack(trackUri, name))
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    playlist = loadedTracks
                }

                val folderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    uri?.let {
                        contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                            it,
                            DocumentsContract.getTreeDocumentId(it)
                        )
                        val newTracks = mutableListOf<AudioTrack>()
                        contentResolver.query(childrenUri, arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                        ), null, null, null)?.use { cursor ->
                            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                            while (cursor.moveToNext()) {
                                val mimeType = cursor.getString(mimeColumn)
                                if (mimeType != null && mimeType.startsWith("audio/")) {
                                    val docId = cursor.getString(idColumn)
                                    val name = cursor.getString(nameColumn)
                                    val trackUri = DocumentsContract.buildDocumentUriUsingTree(it, docId)
                                    newTracks.add(AudioTrack(trackUri, name))
                                }
                            }
                        }
                        playlist = playlist + newTracks
                    }
                }

                    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Playlist Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = playlistName.trim()
                    if (name.isNotEmpty()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = name))
                            val currentTracks = playlist
                            currentTracks.forEach { track ->
                                db.trackDao().insertTrack(PlaylistTrackEntity(
                                    playlistId = playlistId.toInt(),
                                    uriString = track.uri.toString(),
                                    title = track.name
                                ))
                            }
                        }
                    }
                    showCreatePlaylist = false
                    playlistName = ""
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (showLoadPlaylist) {
        AlertDialog(
            onDismissRequest = { showLoadPlaylist = false },
            title = { Text("Load Playlist") },
            text = {
                LazyColumn {
                    items(playlists) { playlistItem ->
                        Text(
                            text = playlistItem.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                                                .clickable {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val tracksList = db.trackDao().getTracksForPlaylist(playlistItem.id).first()
                                        val newPlaylist = tracksList.map { dbTrack ->
                                            AudioTrack(
                                                uri = Uri.parse(dbTrack.uriString),
                                                name = dbTrack.title
                                            )
                                        }
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            playlist = newPlaylist
                                            showLoadPlaylist = false
                                        }
                                    }
                                }
                                .padding(16.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoadPlaylist = false }) {
                    Text("Close")
                }
            }
        )
    }
    Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("64-Bit Audiophile Player") },
                            actions = {
                                IconButton(onClick = { showMenu = !showMenu }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Add Folder") },
                                        onClick = {
                                            showMenu = false
                                            folderPickerLauncher.launch(null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Create Playlist") },
                                        onClick = {
                                            showMenu = false
                                            Toast.makeText(this@MainActivity, "Room DB Playlist Creation Coming", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = {
                                            showMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) }
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = currentTrack?.name ?: "No track playing",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(onClick = {
                                        if (isBound) { playbackService?.getEngine()?.play() }
                                    }) { Text("Play") }
                                    
                                    Button(onClick = {
                                        if (isBound) { playbackService?.getEngine()?.pause() }
                                    }) { Text("Pause") }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Seek Slider
                                val progStr = String.format("%02d:%02d", (progressMs / 60000).toInt(), ((progressMs / 1000) % 60).toInt())
                                val durStr = String.format("%02d:%02d", (durationMs / 60000).toInt(), ((durationMs / 1000) % 60).toInt())
                                Text(text = "Position:  / ", style = MaterialTheme.typography.labelSmall)
                                
                                Slider(
                                    value = progressMs,
                                    onValueChange = { newVal ->
                                        progressMs = newVal
                                        isSeeking = true
                                    },
                                    onValueChangeFinished = {
                                        if (isBound) {
                                            playbackService?.getEngine()?.seekTo(progressMs.toDouble())
                                        }
                                        isSeeking = false
                                    },
                                    valueRange = 0f..durationMs
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(text = "Speed: ", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = speedMult,
                                    onValueChange = { newVal ->
                                        speedMult = newVal
                                    },
                                    valueRange = 0.5f..2.5f
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(text = "Volume (64-bit precise): %", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = volume,
                                    onValueChange = { newVal ->
                                        volume = newVal
                                        if (isBound) {
                                            playbackService?.getEngine()?.setVolume(newVal.toDouble())
                                        }
                                    },
                                    valueRange = 0f..1f
                                )
                            }
                        }

                        // Playlist
                        Text(
                            text = "Playlist ( tracks)",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(playlist) { track ->
                                ListItem(
                                    headlineContent = {
                                        Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                                    },
                                    modifier = Modifier.clickable {
                                        currentTrack = track
                                        try {
                                            if (isBound) {
                                                playbackService?.playTrack(track.uri, this@MainActivity, track.name)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            Toast.makeText(this@MainActivity, "Error playing track", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}






