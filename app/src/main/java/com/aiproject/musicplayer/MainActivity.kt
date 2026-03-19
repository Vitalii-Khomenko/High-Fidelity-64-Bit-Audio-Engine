package com.aiproject.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
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
                var progressMs by remember { mutableStateOf(0f) } // Placeholder for actual progress

                LaunchedEffect(Unit) {
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
                                        if (mimeType.startsWith("audio/")) {
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
                                if (mimeType.startsWith("audio/")) {
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

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("ZionAudioPro 64-bit") },
                            actions = {
                                Button(onClick = { folderPickerLauncher.launch(null) }) {
                                    Text("Add Folder +")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                        
                        // Player Controls Base
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
                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Button(onClick = {
                                        if (isBound) {
                                            playbackService?.getEngine()?.pause()
                                        }
                                    }) {
                                        Text("Stop/Pause")
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Seek Slider Mock
                                Text(text = "Seek (ms)", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = progressMs,
                                    onValueChange = { newVal ->
                                        progressMs = newVal
                                    },
                                    onValueChangeFinished = {
                                        if (isBound) {
                                            playbackService?.getEngine()?.seekTo(progressMs.toDouble())
                                        }
                                    },
                                    valueRange = 0f..300000f // Hardcoded up to 5 min for UI testing
                                )

                                Spacer(modifier = Modifier.height(16.dp))

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
                                        if (isBound) {
                                            playbackService?.playTrack(track.uri, this@MainActivity, track.name)
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
