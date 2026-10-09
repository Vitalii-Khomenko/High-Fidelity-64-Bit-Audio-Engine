package com.aiproject.musicplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.BuildConfig
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.playback.ContentMode
import com.aiproject.musicplayer.playback.FormatText
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.PlayerState
import com.aiproject.musicplayer.playback.TimeFormat
import com.aiproject.musicplayer.ui.components.AwButton
import com.aiproject.musicplayer.ui.components.SectionHeader
import com.aiproject.musicplayer.ui.components.Segmented
import com.aiproject.musicplayer.ui.theme.Aw
import com.aiproject.musicplayer.ui.theme.ThemeMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: PlayerState,
    commands: PlayerCommands,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val aw = Aw.colors
    val context = LocalContext.current
    val output = rememberOutputInfo()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))

        SectionHeader("01", stringResource(R.string.theme), aw.cyan)
        Segmented(
            ThemeMode.entries, themeMode,
            label = {
                stringResource(
                    when (it) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.DARK -> R.string.theme_dark
                        ThemeMode.LIGHT -> R.string.theme_light
                    },
                )
            },
            onSelect = onThemeModeChange,
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader("02", stringResource(R.string.listening_mode), aw.violet)
        Segmented(
            ContentMode.entries, state.settings.contentMode,
            label = { stringResource(if (it == ContentMode.MUSIC) R.string.mode_music else R.string.mode_books) },
            onSelect = commands::setContentMode, tone = aw.violet,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.listening_mode_hint), style = Aw.small, color = aw.muted)
        if (state.settings.contentMode == ContentMode.BOOKS && state.playedUris.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AwButton(stringResource(R.string.clear_finished), commands::clearPlayedMarks)
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("03", stringResource(R.string.sleep_timer), aw.amber) {
            if (state.sleepTimerEndsAt > 0L) {
                var left by remember { mutableLongStateOf(0L) }
                LaunchedEffect(state.sleepTimerEndsAt) {
                    while (true) {
                        left = (state.sleepTimerEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        delay(1000L)
                    }
                }
                Text(TimeFormat.clock(left), style = Aw.heading, color = aw.amber)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                AwButton(stringResource(R.string.minutes, minutes), { commands.startSleepTimer(minutes * 60_000L) })
            }
            if (state.sleepTimerEndsAt > 0L) AwButton(stringResource(R.string.cancel), commands::cancelSleepTimer, tone = aw.amber)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.sleep_hint), style = Aw.small, color = aw.muted)

        Spacer(Modifier.height(24.dp))
        SectionHeader("04", stringResource(R.string.signal_path), aw.paper)
        val format = state.format
        InfoLine(stringResource(R.string.path_source), format?.let { FormatText.short(it) } ?: "—")
        InfoLine(stringResource(R.string.path_engine), stringResource(R.string.path_engine_value))
        InfoLine(stringResource(R.string.path_output), format?.let { FormatText.output(it) }?.takeIf { it.isNotEmpty() } ?: "—")
        InfoLine(
            stringResource(R.string.path_device),
            output.device?.let { listOf(it.name, it.summary, output.bluetoothCodec).filter { s -> s.isNotBlank() }.distinct().joinToString(" · ") }
                ?: stringResource(R.string.device_speaker),
        )
        if ((format?.underruns ?: 0) > 0) InfoLine(stringResource(R.string.underruns), format!!.underruns.toString())
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.signal_path_hint), style = Aw.small, color = aw.muted)

        Spacer(Modifier.height(24.dp))
        SectionHeader("05", stringResource(R.string.renderer), aw.cyan)
        Segmented(
            listOf(false, true), state.settings.renderer,
            label = { stringResource(if (it) R.string.on else R.string.off) },
            onSelect = commands::setRenderer, tone = aw.cyan,
        )
        Spacer(Modifier.height(6.dp))
        state.rendererName?.let { Text(stringResource(R.string.renderer_visible, it), style = Aw.small, color = aw.cyan) }
        Text(stringResource(R.string.renderer_hint), style = Aw.small, color = aw.muted)

        Spacer(Modifier.height(24.dp))
        SectionHeader("06", stringResource(R.string.about), aw.paper)
        InfoLine(stringResource(R.string.version), BuildConfig.VERSION_NAME)
        Text(stringResource(R.string.about_text), style = Aw.small, color = aw.text)
        Spacer(Modifier.height(10.dp))
        AwButton("GitHub", {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val aw = Aw.colors
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label.uppercase(), style = Aw.navLabel, color = aw.muted, modifier = Modifier.weight(0.38f))
        Text(value, style = Aw.small, color = aw.paper, modifier = Modifier.weight(0.62f))
    }
}

private const val REPOSITORY_URL = "https://github.com/Vitalii-Khomenko/High-Fidelity-64-Bit-Audio-Engine"
