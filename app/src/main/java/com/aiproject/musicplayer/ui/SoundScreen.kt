package com.aiproject.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.R
import com.aiproject.musicplayer.playback.EqDefaults
import com.aiproject.musicplayer.playback.EqSettings
import com.aiproject.musicplayer.playback.PlaybackSpeed
import com.aiproject.musicplayer.playback.PlayerCommands
import com.aiproject.musicplayer.playback.PlayerState
import com.aiproject.musicplayer.playback.ReplayGainMode
import com.aiproject.musicplayer.playback.SpeedMode
import com.aiproject.musicplayer.ui.components.AwButton
import com.aiproject.musicplayer.ui.components.Eyebrow
import com.aiproject.musicplayer.ui.components.PixelSlider
import com.aiproject.musicplayer.ui.components.SectionHeader
import com.aiproject.musicplayer.ui.components.Segmented
import com.aiproject.musicplayer.ui.theme.Aw
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundScreen(state: PlayerState, commands: PlayerCommands) {
    val aw = Aw.colors
    val settings = state.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))

        // 01 Volume
        SectionHeader("01", stringResource(R.string.volume), aw.cyan) {
            Text("${(settings.volume * 100).roundToInt()}%", style = Aw.heading, color = aw.paper)
        }
        PixelSlider(settings.volume, commands::setVolume, tone = aw.cyan)
        Text(stringResource(R.string.volume_hint), style = Aw.small, color = aw.muted)

        // 02 Speed
        Spacer(Modifier.height(24.dp))
        var speed by remember(settings.speed) { mutableFloatStateOf(settings.speed) }
        SectionHeader("02", stringResource(R.string.speed), aw.amber) {
            Text("%.2f×".format(speed), style = Aw.heading, color = aw.paper)
        }
        PixelSlider(
            value = speed,
            onValueChange = { speed = (it * 20).roundToInt() / 20f },
            valueRange = PlaybackSpeed.MIN..PlaybackSpeed.MAX,
            onValueChangeFinished = { commands.setSpeed(speed) },
            tone = aw.amber,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackSpeed.PRESETS.forEach { preset ->
                AwButton(
                    "%.2f×".format(preset), { commands.setSpeed(preset) },
                    tone = if (preset == settings.speed) aw.amber else null,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Eyebrow(stringResource(R.string.stretch_profile))
        Spacer(Modifier.height(6.dp))
        Segmented(
            SpeedMode.entries, settings.speedMode,
            label = { stringResource(if (it == SpeedMode.MUSIC) R.string.profile_music else R.string.profile_speech) },
            onSelect = commands::setSpeedMode, tone = aw.amber,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.speed_hint), style = Aw.small, color = aw.muted)

        // 03 Equalizer
        Spacer(Modifier.height(24.dp))
        SectionHeader("03", stringResource(R.string.equalizer), aw.violet)
        Segmented(
            listOf(false, true), settings.eq.enabled,
            label = { stringResource(if (it) R.string.on else R.string.off) },
            onSelect = { commands.setEq(settings.eq.copy(enabled = it)) }, tone = aw.violet,
        )
        Spacer(Modifier.height(10.dp))
        EqBands(settings.eq, enabled = settings.eq.enabled, onChange = commands::setEq)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eq_hint), style = Aw.small, color = aw.muted, modifier = Modifier.weight(1f))
            AwButton(stringResource(R.string.reset), { commands.setEq(EqSettings(enabled = settings.eq.enabled)) })
        }

        // 04 ReplayGain
        Spacer(Modifier.height(24.dp))
        SectionHeader("04", stringResource(R.string.replaygain), aw.paper) {
            state.format?.replayGainDb?.takeIf { it != 0f }?.let {
                Text("%+.1f dB".format(it), style = Aw.mono, color = aw.violet)
            }
        }
        Segmented(
            ReplayGainMode.entries, settings.replayGain,
            label = {
                stringResource(
                    when (it) {
                        ReplayGainMode.OFF -> R.string.off
                        ReplayGainMode.TRACK -> R.string.rg_track
                        ReplayGainMode.ALBUM -> R.string.rg_album
                    },
                )
            },
            onSelect = commands::setReplayGain,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.replaygain_hint), style = Aw.small, color = aw.muted)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun EqBands(eq: EqSettings, enabled: Boolean, onChange: (EqSettings) -> Unit) {
    val aw = Aw.colors
    EqDefaults.BANDS.forEachIndexed { index, band ->
        var gain by remember(eq.bandGainsDb[index]) { mutableFloatStateOf(eq.bandGainsDb[index]) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(band.label.uppercase(), style = Aw.navLabel, color = aw.muted)
            Text("  ·  ${band.frequencyLabel}", style = Aw.mono, color = aw.muted, modifier = Modifier.weight(1f))
            Text("%+.1f dB".format(gain), style = Aw.mono, color = if (enabled) aw.paper else aw.muted)
        }
        PixelSlider(
            value = gain,
            onValueChange = { gain = (it * 2).roundToInt() / 2f },
            valueRange = EqDefaults.MIN_GAIN_DB..EqDefaults.MAX_GAIN_DB,
            onValueChangeFinished = { onChange(eq.withBandGain(index, gain)) },
            tone = aw.violet,
            enabled = enabled,
        )
    }
}

