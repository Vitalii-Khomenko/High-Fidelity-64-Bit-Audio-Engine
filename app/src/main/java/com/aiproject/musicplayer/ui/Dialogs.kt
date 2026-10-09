package com.aiproject.musicplayer.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.Track
import com.aiproject.musicplayer.ui.theme.Aw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val aw = Aw.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = aw.raised,
        title = { Text(title, style = Aw.heading, color = aw.paper) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label, style = Aw.small) },
                singleLine = true,
                textStyle = Aw.body,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = aw.cyan, unfocusedBorderColor = aw.line,
                    focusedTextColor = aw.paper, unfocusedTextColor = aw.paper, cursorColor = aw.cyan,
                    focusedLabelColor = aw.cyan, unfocusedLabelColor = aw.muted,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { text.trim().takeIf { it.isNotEmpty() }?.let(onConfirm) }, enabled = text.isNotBlank()) {
                Text(confirm.uppercase(), style = Aw.navLabel, color = if (text.isNotBlank()) aw.cyan else aw.muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel).uppercase(), style = Aw.navLabel, color = aw.muted) }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val aw = Aw.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = aw.raised,
        title = { Text(title, style = Aw.heading, color = aw.paper) },
        text = { Column { Text(text, style = Aw.body, color = aw.text) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm.uppercase(), style = Aw.navLabel, color = aw.amber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel).uppercase(), style = Aw.navLabel, color = aw.muted) }
        },
    )
}

/**
 * Fills in unknown durations (tracks added from folders) in the background,
 * in small batches, without touching tracks that already failed once.
 */
@Composable
fun DurationProbe(tracks: List<Track>, commands: PlayerCommands) {
    val context = LocalContext.current
    val attempted = remember { HashSet<String>() }
    LaunchedEffect(tracks) {
        val pending = tracks.filter {
            it.durationMs <= 0L && it.uri !in attempted && (it.uri.startsWith("content:") || it.uri.startsWith("file:"))
        }
        for (batch in pending.chunked(16)) {
            val found = withContext(Dispatchers.IO) {
                batch.mapNotNull { track ->
                    attempted += track.uri
                    probe(context, track.uri)?.let { track.uri to it }
                }.toMap()
            }
            if (found.isNotEmpty()) commands.updateDurations(found)
        }
    }
}

private fun probe(context: android.content.Context, uri: String): Long? = try {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, Uri.parse(uri))
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } finally {
        retriever.release()
    }
} catch (_: Exception) {
    null
}
