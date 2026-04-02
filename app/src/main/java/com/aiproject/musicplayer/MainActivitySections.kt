package com.aiproject.musicplayer

import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    sleepTimerDisplay: String,
    showMenu: Boolean,
    sortMode: PlaylistSortMode,
    libraryFolderCount: Int,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAddFolder: () -> Unit,
    onManageLibraryFolders: () -> Unit,
    onScanMediaStore: () -> Unit,
    onSortByName: () -> Unit,
    onSortByNumber: () -> Unit,
    onSavePlaylist: () -> Unit,
    onLoadPlaylist: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onShowEqualizer: () -> Unit,
    onOpenDlna: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.Center) {
                Text("HiFi Player")
                Text(
                    text = "v$versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            if (sleepTimerDisplay.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = sleepTimerDisplay,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(2.dp))
                        IconButton(
                            onClick = onCancelSleepTimer,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel sleep timer",
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onToggleMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Add Folder")
                            Text(
                                "Subfolders included automatically",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = onAddFolder
                )
                DropdownMenuItem(
                    text = { Text("Scan MediaStore") },
                    leadingIcon = { Icon(Icons.Filled.LibraryMusic, null) },
                    onClick = onScanMediaStore
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Library Folders")
                            Text(
                                "$libraryFolderCount folder(s) configured",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = onManageLibraryFolders
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Sort by Name")
                            if (sortMode == PlaylistSortMode.NAME) {
                                Text(
                                    "Current mode",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.SortByAlpha, null) },
                    onClick = onSortByName
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Sort by Number")
                            Text(
                                "Detects numbers at the start, middle, or end",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sortMode == PlaylistSortMode.NUMBER) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                    onClick = onSortByNumber
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Save Playlist") },
                    leadingIcon = { Icon(Icons.Filled.Save, null) },
                    onClick = onSavePlaylist
                )
                DropdownMenuItem(
                    text = { Text("Load Playlist") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = onLoadPlaylist
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Equalizer") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                    onClick = onShowEqualizer
                )
                DropdownMenuItem(
                    text = { Text("Sleep Timer") },
                    leadingIcon = { Icon(Icons.Filled.Timer, null) },
                    onClick = onShowSleepTimer
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Browse Network (DLNA)") },
                    leadingIcon = { Icon(Icons.Filled.Wifi, null) },
                    onClick = onOpenDlna
                )
            }
        }
    )
}

@Composable
fun PlayerCardSection(
    currentTrack: AudioTrack?,
    sampleRateKhz: String,
    bitDepth: String,
    replayGainDb: Float,
    dsdNativeRate: Int,
    headsetInfo: HeadsetInfo?,
    bluetoothCodec: String,
    isPlaying: Boolean,
    spectrumBands: FloatArray,
    progressMs: Float,
    durationMs: Float,
    speedMult: Float,
    speedMode: PlaybackSpeedMode,
    playbackContentMode: PlaybackContentMode,
    volume: Float,
    currentIndex: Int,
    playlistSize: Int,
    shuffleEnabled: Boolean,
    shuffleHasHistory: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    repeatMode: Int,
    onRepeatModeChange: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSeekChanged: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSpeedModeChanged: (PlaybackSpeedMode) -> Unit,
    onPlaybackContentModeChanged: (PlaybackContentMode) -> Unit,
    onSpeedReset: () -> Unit,
    onVolumeChanged: (Float) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                        text = listOf(sampleRateKhz, bitDepth).filter { it.isNotEmpty() }.joinToString("·"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (replayGainDb != 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (replayGainDb > 0f) "+%.1f dB".format(replayGainDb) else "%.1f dB".format(replayGainDb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (dsdNativeRate > 0) {
                    Spacer(Modifier.width(6.dp))
                    val dsdLabel = DsdInfo.label(dsdNativeRate) ?: "DSD"
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFFD700).copy(alpha = 0.18f),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            text = dsdLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB8860B),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            headsetInfo?.let { info ->
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        info.connectionType.startsWith("USB") -> Icons.Filled.Usb to MaterialTheme.colorScheme.tertiary
                        info.connectionType.startsWith("Bluetooth") -> Icons.Filled.BluetoothConnected to MaterialTheme.colorScheme.primary
                        else -> Icons.Filled.Headphones to MaterialTheme.colorScheme.secondary
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = tint)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = buildString {
                            append(info.name)
                            append("  ·  ")
                            append(info.summary)
                            if (bluetoothCodec.isNotEmpty()) {
                                append("  ·  ")
                                append(bluetoothCodec)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
                val barWidth = size.width / spectrumBands.size
                spectrumBands.forEachIndexed { index, value ->
                    val barHeight = (value * size.height).coerceAtLeast(2f)
                    val hue = 200f * (1f - value)
                    drawRect(
                        color = Color.hsv(hue, 0.75f, 0.85f),
                        topLeft = Offset(index * barWidth + 1f, size.height - barHeight),
                        size = Size(barWidth - 2f, barHeight)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatPlayerTime(progressMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                Slider(
                    value = progressMs,
                    onValueChange = onSeekChanged,
                    onValueChangeFinished = onSeekFinished,
                    valueRange = 0f..durationMs.coerceAtLeast(1f),
                    modifier = Modifier.weight(1f).height(28.dp)
                )
                Text(
                    formatPlayerTime(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = if (shuffleEnabled) shuffleHasHistory else currentIndex > 0
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(30.dp))
                }
                FilledIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = if (shuffleEnabled) playlistSize > 1 else currentIndex < playlistSize - 1
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        modifier = Modifier.size(22.dp),
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
                IconButton(onClick = onRepeatModeChange) {
                    Icon(
                        imageVector = if (repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Repeat",
                        modifier = Modifier.size(22.dp),
                        tint = if (repeatMode > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Mode: ${playbackContentMode.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PlaybackContentMode.entries.forEach { mode ->
                            val selected = mode == playbackContentMode
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(999.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                }
                            ) {
                                TextButton(
                                    onClick = { onPlaybackContentModeChanged(mode) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp).fillMaxWidth()
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            LocalContentColor.current
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            var showAdvanced by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth().height(28.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    if (showAdvanced) "▲ speed / volume" else "▼ speed / volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            if (showAdvanced) {
                Text(text = "Speed: ${"%.2f".format(speedMult)}x", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = speedMult,
                    onValueChange = onSpeedChanged,
                    valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
                    steps = 0
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlaybackSpeedMode.entries.forEach { mode ->
                        OutlinedButton(
                            onClick = { onSpeedModeChanged(mode) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (mode == speedMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaybackSpeed.PRESETS.take(4).forEach { preset ->
                            OutlinedButton(
                                onClick = { onSpeedChanged(preset) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text("${"%.2f".format(preset)}x", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaybackSpeed.PRESETS.drop(4).forEach { preset ->
                            OutlinedButton(
                                onClick = { onSpeedChanged(preset) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                            ) {
                                Text("${"%.2f".format(preset)}x", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onSpeedReset, modifier = Modifier.align(Alignment.End)) {
                    Text("Reset speed")
                }
                Spacer(Modifier.height(4.dp))
                Text(text = "Volume: ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(value = volume, onValueChange = onVolumeChanged, valueRange = 0f..1f)
            }
        }
    }
}

@Composable
fun PlaylistSection(
    modifier: Modifier = Modifier,
    playlist: List<AudioTrack>,
    playlistSummary: PlaylistSummary,
    currentIndex: Int,
    isPlaying: Boolean,
    playedIndices: Set<Int>,
    onClear: () -> Unit,
    onSelectTrack: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildString {
                append("Playlist · ${playlistSummary.trackCount} tracks")
                if (playlistSummary.formattedDuration.isNotEmpty()) {
                    append(" · ")
                    append(playlistSummary.formattedDuration)
                }
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (playlist.isNotEmpty()) {
            TextButton(onClick = onClear) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (playlist.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex in playlist.indices) {
            listState.animateScrollToItem(index = currentIndex, scrollOffset = -120)
        }
    }
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(playlist) { index, track ->
            val isCurrentTrack = index == currentIndex
            val isPlayed = index in playedIndices && !isCurrentTrack
            val textColor = when {
                isCurrentTrack -> MaterialTheme.colorScheme.primary
                isPlayed -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.onSurface
            }
            val rowBackground = when {
                isCurrentTrack -> Modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                isPlayed -> Modifier.background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f))
                else -> Modifier
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
                    {
                        Text(
                            track.folder,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                modifier = Modifier.clickable { onSelectTrack(index) }.then(rowBackground)
            )
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
fun DlnaBrowserDialog(
    show: Boolean,
    dlnaScanning: Boolean,
    dlnaBrowsing: Boolean,
    dlnaServers: List<DlnaServer>,
    dlnaSelected: DlnaServer?,
    dlnaTracks: List<DlnaTrack>,
    dlnaErrorMessage: String?,
    onDismiss: () -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    onBrowseServer: (DlnaServer) -> Unit,
    onAddTrack: (DlnaTrack) -> Unit,
    onAddAllTracks: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network Sources (DLNA)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    dlnaScanning -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Scanning network…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    dlnaBrowsing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Loading tracks…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    dlnaSelected != null && dlnaTracks.isNotEmpty() -> {
                        Text(
                            "Server: ${dlnaSelected.friendlyName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${dlnaTracks.size} audio tracks found",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            itemsIndexed(dlnaTracks) { _, track ->
                                ListItem(
                                    headlineContent = {
                                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    },
                                    supportingContent = if (track.durationMs > 0) {
                                        {
                                            val seconds = track.durationMs / 1000
                                            Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else null,
                                    modifier = Modifier.clickable { onAddTrack(track) }
                                )
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onAddAllTracks, modifier = Modifier.fillMaxWidth()) {
                            Text("Add all ${dlnaTracks.size} tracks to playlist")
                        }
                    }
                    dlnaSelected != null && !dlnaBrowsing -> {
                        Text(
                            dlnaErrorMessage ?: "No audio tracks found on ${dlnaSelected.friendlyName}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (dlnaErrorMessage != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onBack) { Text("← Back") }
                    }
                    dlnaServers.isEmpty() && !dlnaScanning -> {
                        Text(
                            (dlnaErrorMessage ?: "No DLNA media servers found.") + "\n\nMake sure your media server (e.g. Plex, Emby, Jellyfin, Windows Media Player, Kodi) is running on the same Wi-Fi network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (dlnaErrorMessage != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                    else -> {
                        Text("Found ${dlnaServers.size} server(s):", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            itemsIndexed(dlnaServers) { _, server ->
                                ListItem(
                                    headlineContent = {
                                        Text(server.friendlyName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Filled.Wifi,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.clickable { onBrowseServer(server) }
                                )
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (dlnaSelected != null && !dlnaBrowsing) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRescan) { Text(if (dlnaScanning) "Scanning…" else "Scan") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
fun LibraryFoldersDialog(
    show: Boolean,
    folders: List<LibraryFolderEntry>,
    availableFolderUris: Set<String>,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onBrowseFolder: (LibraryFolderEntry) -> Unit,
    onRemoveFolder: (LibraryFolderEntry) -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library Folders") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "These folders keep persistent access only. Adding folders to the playlist is a separate action via Add Folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (folders.isEmpty()) {
                    Text(
                        "No library folders configured yet.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(folders) { _, folder ->
                            val hasAccess = folder.uriString in availableFolderUris
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = if (hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        folder.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (hasAccess) folder.uriString else "Access lost: ${folder.uriString}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasAccess) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onBrowseFolder(folder) },
                                    enabled = hasAccess
                                ) {
                                    Icon(
                                        Icons.Filled.LibraryMusic,
                                        contentDescription = "Browse folder",
                                        tint = if (hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveFolder(folder) },
                                    enabled = true
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Remove folder",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onAddFolder) { Text("Add Folder") }
        }
    )
}

@Composable
fun BrowseLibraryFolderDialog(
    show: Boolean,
    rootLabel: String,
    currentPath: List<String>,
    entries: List<LibraryBrowserEntry>,
    loading: Boolean,
    errorMessage: String?,
    canNavigateUp: Boolean,
    onDismiss: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenFolder: (LibraryBrowserEntry) -> Unit,
    onAddCurrentFolder: () -> Unit,
    onAddTrack: (LibraryBrowserEntry) -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rootLabel.isNotBlank()) "Browse $rootLabel" else "Browse Library Folder") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Navigate inside the authorized folder, then explicitly add the current folder or an individual track to the playlist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (currentPath.isNotEmpty()) {
                    Text(
                        currentPath.joinToString(" > "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (canNavigateUp) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Up")
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Loading folder…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    errorMessage != null -> {
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    entries.isEmpty() -> {
                        Text(
                            "No subfolders or audio files found here.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            itemsIndexed(entries) { _, entry ->
                                ListItem(
                                    headlineContent = {
                                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(
                                            if (entry.isDirectory) "Open folder" else "Tap to add this track to the playlist",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = if (entry.isDirectory) Icons.Filled.FolderOpen else Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        if (entry.isDirectory) {
                                            onOpenFolder(entry)
                                        } else {
                                            onAddTrack(entry)
                                        }
                                    }
                                )
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onAddCurrentFolder, enabled = !loading && errorMessage == null) {
                Text("Add Folder To Playlist")
            }
        }
    )
}

private fun formatPlayerTime(ms: Float): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun EqualizerDialog(
    show: Boolean,
    settings: EqSettings,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    onReset: () -> Unit,
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("5-band Equalizer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Enable EQ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Live native DSP, saved between launches",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.enabled, onCheckedChange = onEnabledChange)
                }
                EqDefaults.BANDS.forEachIndexed { index, band ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${band.label} · ${band.frequencyLabel}", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "%+.1f dB".format(settings.bandGainsDb[index]),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = settings.bandGainsDb[index],
                            onValueChange = { onBandGainChange(index, it) },
                            valueRange = EqDefaults.MIN_GAIN_DB..EqDefaults.MAX_GAIN_DB,
                            enabled = settings.enabled
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("Reset") }
        }
    )
}