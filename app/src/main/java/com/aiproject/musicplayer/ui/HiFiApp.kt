package com.aiproject.musicplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.playback.PlaybackPosition
import com.aiproject.musicplayer.playback.PlaybackService
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.PlayerState
import com.aiproject.musicplayer.playback.TimeFormat
import com.aiproject.musicplayer.ui.components.Cover
import com.aiproject.musicplayer.ui.components.LogoMark
import com.aiproject.musicplayer.ui.components.PixelIcons
import com.aiproject.musicplayer.ui.components.SquareIconButton
import com.aiproject.musicplayer.ui.components.inkBackground
import com.aiproject.musicplayer.ui.components.pixelGlow
import com.aiproject.musicplayer.ui.theme.Aw
import com.aiproject.musicplayer.ui.theme.HiFiTheme
import com.aiproject.musicplayer.ui.theme.ThemeMode
import kotlinx.coroutines.delay

enum class Tab(val label: Int) { PLAYER(R.string.tab_player), LIBRARY(R.string.tab_library), SOUND(R.string.tab_sound), SETTINGS(R.string.tab_settings) }

@Composable
fun HiFiApp(service: PlaybackService?, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, playerRequests: Int = 0) {
    HiFiTheme(themeMode) {
        val aw = Aw.colors
        if (service == null) {
            Box(Modifier.fillMaxSize().inkBackground(aw)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    LogoMark(size = 40.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.starting_engine).uppercase(), style = Aw.eyebrow, color = aw.muted)
                }
            }
            return@HiFiTheme
        }
        val state by service.state.collectAsState()
        val position by service.position.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(service) {
            service.messages.collect { snackbar.showSnackbar(it) }
        }
        AppContent(state, position, service, snackbar, themeMode, onThemeModeChange, playerRequests = playerRequests)
    }
}

/** The whole UI for a given state; separate from the service for previews and screenshots. */
@Composable
fun AppContent(
    state: PlayerState,
    position: PlaybackPosition,
    commands: PlayerCommands,
    snackbar: SnackbarHostState,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    initialTab: Tab = Tab.PLAYER,
    /** Raised when files were opened from another app: show the player. */
    playerRequests: Int = 0,
) {
    val aw = Aw.colors
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(playerRequests) { if (playerRequests > 0) tab = Tab.PLAYER }
    BackHandler(enabled = tab != Tab.PLAYER) { tab = Tab.PLAYER }
    Box(Modifier.fillMaxSize().inkBackground(aw)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Header(
                state = state,
                onThemeToggle = { onThemeModeChange(themeMode.next()) },
                onSleepTimerClick = { tab = Tab.SETTINGS },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.PLAYER -> PlayerScreen(state, position, commands, onOpenLibrary = { tab = Tab.LIBRARY }, onOpenSound = { tab = Tab.SOUND })
                    Tab.LIBRARY -> LibraryScreen(state, commands, onPlayed = { tab = Tab.PLAYER })
                    Tab.SOUND -> SoundScreen(state, commands)
                    Tab.SETTINGS -> SettingsScreen(state, commands, themeMode, onThemeModeChange)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (tab != Tab.PLAYER && state.current != null) {
                MiniPlayer(state, position, onClick = { tab = Tab.PLAYER }, onToggle = commands::togglePlayPause)
            }
            BottomTabs(tab) { tab = it }
        }
    }
}

@Composable
private fun Header(state: PlayerState, onThemeToggle: () -> Unit, onSleepTimerClick: () -> Unit) {
    val aw = Aw.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Text("HiFi Player", style = Aw.wordmark, color = aw.paper)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(aw.line))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.header_tag).uppercase(), style = Aw.eyebrow, color = aw.muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (state.sleepTimerEndsAt > 0L) {
            SleepChip(state.sleepTimerEndsAt, onSleepTimerClick)
            Spacer(Modifier.width(6.dp))
        }
        SquareIconButton(PixelIcons.Theme, stringResource(R.string.toggle_theme), onThemeToggle, size = 36.dp, iconSize = 14.dp, outlined = true)
    }
}

@Composable
private fun SleepChip(endsAt: Long, onClick: () -> Unit) {
    val aw = Aw.colors
    var left by remember { mutableLongStateOf(0L) }
    LaunchedEffect(endsAt) {
        while (true) {
            left = (endsAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    Box(
        Modifier.border(1.dp, aw.amber.copy(alpha = 0.6f)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("ZZ ${TimeFormat.clock(left)}", style = Aw.navLabel, color = aw.amber)
    }
}

@Composable
private fun MiniPlayer(state: PlayerState, position: PlaybackPosition, onClick: () -> Unit, onToggle: () -> Unit) {
    val aw = Aw.colors
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.current?.let { Cover(it.uri, 40.dp, Modifier.size(40.dp), seed = it.album.ifBlank { it.folder }) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.current?.title.orEmpty(), style = Aw.bodyStrong, color = aw.paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${TimeFormat.clock(position.positionMs)} / ${TimeFormat.clock(position.durationMs)}",
                    style = Aw.mono, color = aw.muted,
                )
            }
            SquareIconButton(
                if (state.isPlaying) PixelIcons.Pause else PixelIcons.Play,
                stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                onToggle,
            )
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    val aw = Aw.colors
    val tones = mapOf(Tab.PLAYER to aw.cyan, Tab.LIBRARY to aw.violet, Tab.SOUND to aw.amber, Tab.SETTINGS to aw.paper)
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                val tone = tones.getValue(tab)
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .then(if (isSelected) Modifier.pixelGlow(tone, aw.glow, 5.dp) else Modifier)
                            .background(if (isSelected) tone else Color.Transparent)
                            .border(1.dp, if (isSelected) tone else aw.muted.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(tab.label).uppercase(),
                        style = Aw.navLabel,
                        color = if (isSelected) aw.paper else aw.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
