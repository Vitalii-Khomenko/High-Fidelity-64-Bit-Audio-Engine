package com.aiproject.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.db.MusicDatabase
import com.aiproject.musicplayer.db.PlaylistStore
import com.aiproject.musicplayer.playback.ContentMode
import com.aiproject.musicplayer.playback.FormatText
import com.aiproject.musicplayer.playback.PlaybackPosition
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.PlayerState
import com.aiproject.musicplayer.playback.RepeatMode
import com.aiproject.musicplayer.playback.SortMode
import com.aiproject.musicplayer.playback.TimeFormat
import com.aiproject.musicplayer.playback.Track
import com.aiproject.musicplayer.ui.components.AwButton
import com.aiproject.musicplayer.ui.components.EmptyNote
import com.aiproject.musicplayer.ui.components.Eyebrow
import com.aiproject.musicplayer.ui.components.PixelIcons
import com.aiproject.musicplayer.ui.components.PixelSlider
import com.aiproject.musicplayer.ui.components.PixelSpectrum
import com.aiproject.musicplayer.ui.components.SectionHeader
import com.aiproject.musicplayer.ui.components.SquareIconButton
import com.aiproject.musicplayer.ui.components.ToneChip
import com.aiproject.musicplayer.ui.components.pixelGlow
import com.aiproject.musicplayer.ui.theme.Aw
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    state: PlayerState,
    position: PlaybackPosition,
    commands: PlayerCommands,
    onOpenLibrary: () -> Unit,
    onOpenSound: () -> Unit,
) {
    val aw = Aw.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askSave by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DurationProbe(state.tracks, commands)

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "now") { NowPlaying(state, position, commands, onOpenSound) }
        item(key = "queue-header") {
            SectionHeader(
                index = "01",
                title = stringResource(R.string.queue),
                tone = aw.cyan,
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 24.dp),
            ) {
                if (state.tracks.isNotEmpty()) {
                    Text(
                        listOf(
                            context.resources.getQuantityString(R.plurals.track_count, state.tracks.size, state.tracks.size),
                            TimeFormat.span(state.totalDurationMs),
                        ).filter { it.isNotEmpty() }.joinToString("  ·  "),
                        style = Aw.mono, color = aw.muted,
                    )
                }
            }
        }
        if (state.tracks.isEmpty()) {
            item(key = "empty") {
                EmptyNote(
                    stringResource(R.string.queue_empty_title),
                    stringResource(R.string.queue_empty_text),
                    Modifier.padding(horizontal = 20.dp),
                ) {
                    AwButton(stringResource(R.string.open_library), onOpenLibrary, tone = aw.violet)
                }
            }
        } else {
            item(key = "queue-actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AwButton(stringResource(R.string.add), onOpenLibrary, Modifier.weight(1f))
                    AwButton(
                        stringResource(
                            when (state.settings.sortMode) {
                                SortMode.NAME -> R.string.sort_name
                                SortMode.NUMBER -> R.string.sort_number
                                SortMode.ALBUM -> R.string.sort_album
                            },
                        ),
                        { commands.sortQueue(state.settings.sortMode.next()) }, Modifier.weight(1f),
                    )
                    AwButton(stringResource(R.string.save), { askSave = true }, Modifier.weight(1f))
                    AwButton(stringResource(R.string.clear), { askClear = true }, Modifier.weight(1f))
                }
            }
            itemsIndexed(state.tracks, key = { _, t -> t.uri }) { index, track ->
                QueueRow(
                    index = index,
                    track = track,
                    isCurrent = index == state.currentIndex,
                    isPlaying = state.isPlaying,
                    played = state.settings.contentMode == ContentMode.BOOKS && track.uri in state.playedUris,
                    onClick = { commands.playIndex(index) },
                    onRemove = { commands.removeAt(index) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (askSave) {
        TextInputDialog(
            title = stringResource(R.string.save_playlist),
            label = stringResource(R.string.playlist_name),
            confirm = stringResource(R.string.save),
            onDismiss = { askSave = false },
            onConfirm = { name ->
                askSave = false
                scope.launch {
                    val message = try {
                        PlaylistStore(MusicDatabase.getDatabase(context)).save(name, state.tracks, state.shuffle)
                        context.getString(R.string.playlist_saved, name)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        context.getString(R.string.playlist_error)
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    if (askClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_queue_title),
            text = stringResource(R.string.clear_queue_text),
            confirm = stringResource(R.string.clear),
            onDismiss = { askClear = false },
            onConfirm = { askClear = false; commands.clearQueue() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlaying(state: PlayerState, position: PlaybackPosition, commands: PlayerCommands, onOpenSound: () -> Unit) {
    val aw = Aw.colors
    val track = state.current
    val output = rememberOutputInfo()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(
                if (track != null) {
                    stringResource(R.string.now_playing) + "  ·  %02d / %02d".format(state.currentIndex + 1, state.tracks.size)
                } else {
                    stringResource(R.string.ready)
                },
                Modifier.weight(1f),
            )
            if (state.isLoading) Eyebrow(stringResource(R.string.loading), color = aw.amber)
            else state.importing?.let { Eyebrow(stringResource(R.string.scanning, it), color = aw.violet) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            track?.title ?: stringResource(R.string.nothing_playing),
            style = Aw.title, color = aw.head, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (!track?.folder.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(track!!.folder, style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.format?.let { format ->
                FormatText.chips(format).forEach { chip ->
                    ToneChip(chip, if (chip.startsWith("DSD")) aw.amber else aw.cyan)
                }
                FormatText.replayGain(format.replayGainDb).takeIf { it.isNotEmpty() }?.let { ToneChip(it, aw.violet) }
                FormatText.output(format).takeIf { it.isNotEmpty() }?.let { ToneChip(it, aw.muted) }
            }
            if (state.settings.speed != 1f) ToneChip("%.2f×".format(state.settings.speed), aw.amber)
        }
        output.device?.let { device ->
            Spacer(Modifier.height(8.dp))
            Text(
                listOf(device.name, device.summary, output.bluetoothCodec).filter { it.isNotBlank() }.distinct().joinToString("  ·  "),
                style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        SpectrumView(state.isPlaying, commands)
        Spacer(Modifier.height(12.dp))
        SeekBar(position, commands)
        Spacer(Modifier.height(10.dp))
        Transport(state, commands)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AwButton("%.2f×".format(state.settings.speed), onOpenSound, Modifier.weight(1f))
            AwButton(
                stringResource(if (state.settings.contentMode == ContentMode.BOOKS) R.string.mode_books else R.string.mode_music),
                { commands.setContentMode(if (state.settings.contentMode == ContentMode.BOOKS) ContentMode.MUSIC else ContentMode.BOOKS) },
                Modifier.weight(1f),
            )
            AwButton(
                stringResource(if (state.settings.eq.enabled) R.string.eq_on else R.string.eq_off),
                onOpenSound, Modifier.weight(1f), tone = if (state.settings.eq.enabled) aw.amber else null,
            )
        }
    }
}

@Composable
private fun SpectrumView(isPlaying: Boolean, commands: PlayerCommands) {
    var bands by remember { mutableStateOf(FloatArray(BANDS).also(commands::readSpectrum)) }
    LaunchedEffect(isPlaying) {
        val buffer = FloatArray(BANDS)
        // Keep polling briefly after a pause so the bars fall instead of freezing.
        var tail = 45
        while (isActive && (isPlaying || tail-- > 0)) {
            commands.readSpectrum(buffer)
            bands = buffer.copyOf()
            delay(33L)
        }
    }
    PixelSpectrum(bands, Modifier.fillMaxWidth().height(64.dp))
}

@Composable
private fun SeekBar(position: PlaybackPosition, commands: PlayerCommands) {
    val aw = Aw.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = position.durationMs.coerceAtLeast(1L).toFloat()
    val shown = dragging ?: position.positionMs.toFloat()
    PixelSlider(
        value = shown,
        onValueChange = { dragging = it },
        valueRange = 0f..duration,
        onValueChangeFinished = {
            dragging?.let { commands.seekTo(it.toLong()) }
            dragging = null
        },
        enabled = position.durationMs > 0L,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(TimeFormat.clock(shown.toLong()), style = Aw.mono, color = aw.text)
        Spacer(Modifier.weight(1f))
        Text(TimeFormat.clock(position.durationMs), style = Aw.mono, color = aw.muted)
    }
}

@Composable
private fun Transport(state: PlayerState, commands: PlayerCommands) {
    val aw = Aw.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SquareIconButton(
            Icons.Outlined.Shuffle, stringResource(R.string.shuffle), { commands.setShuffle(!state.shuffle) },
            tint = if (state.shuffle) aw.cyan else aw.muted,
        )
        SquareIconButton(PixelIcons.Previous, stringResource(R.string.action_previous), commands::previous, size = 52.dp, iconSize = 26.dp)
        SquareIconButton(
            if (state.isPlaying) PixelIcons.Pause else PixelIcons.Play,
            stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
            commands::togglePlayPause,
            size = 72.dp, iconSize = 32.dp, outlined = true, glow = aw.cyan,
            enabled = state.tracks.isNotEmpty(),
        )
        SquareIconButton(PixelIcons.Next, stringResource(R.string.action_next), commands::next, size = 52.dp, iconSize = 26.dp)
        SquareIconButton(
            if (state.settings.repeat == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            stringResource(
                when (state.settings.repeat) {
                    RepeatMode.OFF -> R.string.repeat_off
                    RepeatMode.ALL -> R.string.repeat_all
                    RepeatMode.ONE -> R.string.repeat_one
                },
            ),
            commands::cycleRepeat,
            tint = if (state.settings.repeat == RepeatMode.OFF) aw.muted else aw.cyan,
        )
    }
}

@Composable
private fun QueueRow(
    index: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    played: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aw = Aw.colors
    Box(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (isCurrent) {
            Box(Modifier.align(Alignment.CenterStart).width(2.dp).height(36.dp).pixelGlow(aw.cyan, aw.glow, 4.dp).background(aw.cyan))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(36.dp)) {
                if (isCurrent) {
                    Icon(if (isPlaying) PixelIcons.Play else PixelIcons.Pause, null, tint = aw.cyan, modifier = Modifier.size(14.dp))
                } else {
                    Text("%02d".format(index + 1), style = Aw.mono, color = aw.muted)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = if (isCurrent) Aw.bodyStrong else Aw.body,
                    color = when {
                        isCurrent -> aw.head
                        played -> aw.muted
                        else -> aw.text
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val meta = listOf(track.folder, TimeFormat.span(track.durationMs).takeIf { track.durationMs > 0 }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, style = Aw.small, color = aw.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (played) Icon(Icons.Outlined.Check, stringResource(R.string.finished), tint = aw.muted, modifier = Modifier.size(16.dp))
            SquareIconButton(Icons.Outlined.Close, stringResource(R.string.remove), onRemove, size = 40.dp, iconSize = 16.dp, tint = aw.muted)
        }
    }
}

private const val BANDS = 32
