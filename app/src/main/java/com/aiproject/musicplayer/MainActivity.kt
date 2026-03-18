package com.aiproject.musicplayer

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class AudioTrack(val uri: Uri, val name: String)

class MainActivity : ComponentActivity() {

    private val audioEngine = AudioEngine()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        audioEngine.initEngine()

        setContent {
            MaterialTheme {
                var playlist by remember { mutableStateOf(listOf<AudioTrack>()) }
                var currentTrack by remember { mutableStateOf<AudioTrack?>(null) }

                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    val newTracks = uris.map { uri ->
                        AudioTrack(uri, getFileName(uri))
                    }
                    playlist = playlist + newTracks
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("MusicPlayerPro (64-bit Core)") },
                            actions = {
                                IconButton(onClick = { filePickerLauncher.launch(arrayOf("audio/*", "application/octet-stream")) }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add Tracks")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        // Playlist view
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(playlist) { track ->
                                TrackItem(
                                    track = track,
                                    isPlaying = track == currentTrack,
                                    onClick = {
                                        currentTrack = track
                                        // Pass the Context to resolve the URI into a native File Descriptor
                                        audioEngine.playTrack(track.uri, this@MainActivity)
                                    }
                                )
                                Divider()
                            }
                        }

                        // Player controls
                        PlayerControls(audioEngine, currentTrack)
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result = "Unknown Track"
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.shutdownEngine()
    }
}

@Composable
fun TrackItem(track: AudioTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        Text(
            text = track.name,
            color = color,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlayerControls(engine: AudioEngine, currentTrack: AudioTrack?) {
    var volume by remember { mutableStateOf(1f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = currentTrack?.name ?: "No track selected",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Volume (64-bit precise): ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = volume,
                onValueChange = { newVal -> 
                    volume = newVal
                    engine.setVolume(newVal.toDouble())
                },
                valueRange = 0f..1f
            )

            Row {
                Button(onClick = { engine.play() }, enabled = currentTrack != null) {
                    Text("Play")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(onClick = { engine.pause() }, enabled = currentTrack != null) {
                    Text("Pause")
                }
            }
        }
    }
}
