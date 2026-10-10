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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.aiproject.musicplayer.library.AutoEqCatalog
import com.aiproject.musicplayer.library.AutoEqEntry
import com.aiproject.musicplayer.library.AutoEqParser
import com.aiproject.musicplayer.playback.VolumeCurve
import com.aiproject.musicplayer.playback.CrossfeedMode
import com.aiproject.musicplayer.playback.EqDefaults
import com.aiproject.musicplayer.playback.EqMode
import com.aiproject.musicplayer.playback.EqProfile
import kotlinx.coroutines.launch
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
            Text(VolumeCurve.label(settings.volume), style = Aw.heading, color = aw.paper)
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
        SectionHeader("03", stringResource(R.string.equalizer), aw.violet) {
            if (settings.eq.enabled && settings.eq.mode == EqMode.PARAMETRIC) {
                settings.eq.profile?.let { Text(it.name, style = Aw.mono, color = aw.violet, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Segmented(
            listOf(false, true), settings.eq.enabled,
            label = { stringResource(if (it) R.string.on else R.string.off) },
            onSelect = { commands.setEq(settings.eq.copy(enabled = it)) }, tone = aw.violet,
        )
        Spacer(Modifier.height(8.dp))
        Segmented(
            EqMode.entries, settings.eq.mode,
            label = { stringResource(if (it == EqMode.GRAPHIC) R.string.eq_graphic else R.string.eq_parametric) },
            onSelect = { commands.setEq(settings.eq.copy(mode = it)) }, tone = aw.violet,
        )
        Spacer(Modifier.height(10.dp))
        if (settings.eq.mode == EqMode.GRAPHIC) {
            EqBands(settings.eq, enabled = settings.eq.enabled, onChange = commands::setEq)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.eq_hint), style = Aw.small, color = aw.muted, modifier = Modifier.weight(1f))
                AwButton(stringResource(R.string.reset), { commands.setEq(settings.eq.copy(bandGainsDb = EqSettings().bandGainsDb)) })
            }
        } else {
            ParametricPanel(settings.eq, commands)
        }

        // 04 Crossfeed
        Spacer(Modifier.height(24.dp))
        SectionHeader("04", stringResource(R.string.crossfeed), aw.cyan)
        Segmented(
            CrossfeedMode.entries, settings.crossfeed,
            label = {
                stringResource(
                    when (it) {
                        CrossfeedMode.OFF -> R.string.off
                        CrossfeedMode.DEFAULT -> R.string.crossfeed_default
                        CrossfeedMode.CHU_MOY -> R.string.crossfeed_cmoy
                        CrossfeedMode.JAN_MEIER -> R.string.crossfeed_meier
                    },
                )
            },
            onSelect = commands::setCrossfeed, tone = aw.cyan,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.crossfeed_hint), style = Aw.small, color = aw.muted)

        // 05 Loudness
        Spacer(Modifier.height(24.dp))
        SectionHeader("05", stringResource(R.string.loudness), aw.paper) {
            state.format?.replayGainDb?.takeIf { it != 0f }?.let {
                Text("%+.1f dB".format(it), style = Aw.mono, color = aw.violet)
            }
        }
        Eyebrow(stringResource(R.string.replaygain))
        Spacer(Modifier.height(6.dp))
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
        Spacer(Modifier.height(12.dp))
        Eyebrow(stringResource(R.string.analyze_untagged))
        Spacer(Modifier.height(6.dp))
        Segmented(
            listOf(false, true), settings.autoAnalyze,
            label = { stringResource(if (it) R.string.on else R.string.off) },
            onSelect = commands::setAutoAnalyze,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.analyze_hint), style = Aw.small, color = aw.muted)
        Spacer(Modifier.height(8.dp))
        val analysis = state.analysis
        if (analysis == null) {
            AwButton(stringResource(R.string.analyze_library), commands::analyzeLibrary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analyzing, analysis.first, analysis.second), style = Aw.mono, color = aw.paper, modifier = Modifier.weight(1f))
                AwButton(stringResource(R.string.cancel), commands::cancelAnalysis, tone = aw.amber)
            }
        }
        Spacer(Modifier.height(12.dp))
        Eyebrow(stringResource(R.string.limiter))
        Spacer(Modifier.height(6.dp))
        Segmented(
            listOf(false, true), settings.limiter,
            label = { stringResource(if (it) R.string.on else R.string.off) },
            onSelect = commands::setLimiter,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.limiter_hint), style = Aw.small, color = aw.muted)
        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametricPanel(eq: EqSettings, commands: PlayerCommands) {
    val aw = Aw.colors
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    fun apply(profile: EqProfile) = commands.setEq(eq.copy(enabled = true, mode = EqMode.PARAMETRIC, profile = profile))
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val profile = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val text = input.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8)
                AutoEqParser.parse(text, displayName(context, uri))
            }
        }.getOrNull()
        if (profile != null) apply(profile)
        else Toast.makeText(context, context.getString(R.string.eq_import_failed), Toast.LENGTH_LONG).show()
    }
    val profile = eq.profile
    if (profile == null) {
        Text(stringResource(R.string.eq_no_profile), style = Aw.small, color = aw.muted)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profile.name, style = Aw.bodyStrong, color = aw.paper, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("%+.1f dB".format(profile.preampDb), style = Aw.mono, color = aw.muted)
        }
        Spacer(Modifier.height(6.dp))
        profile.bands.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(b.filter.label, style = Aw.mono, color = aw.violet, modifier = Modifier.width(36.dp))
                Text(frequencyText(b.frequency), style = Aw.mono, color = aw.text, modifier = Modifier.weight(1f))
                Text("%+.1f dB".format(b.gainDb), style = Aw.mono, color = aw.paper, modifier = Modifier.width(80.dp))
                Text("Q %.2f".format(b.q), style = Aw.mono, color = aw.muted, modifier = Modifier.width(64.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AwButton(stringResource(R.string.eq_autoeq), { searching = true }, tone = aw.violet)
        AwButton(stringResource(R.string.eq_import), { importFile.launch(arrayOf("text/*", "application/octet-stream")) })
        if (profile != null) AwButton(stringResource(R.string.remove), { commands.setEq(eq.copy(profile = null)) })
    }
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.eq_parametric_hint), style = Aw.small, color = aw.muted)
    if (searching) AutoEqDialog(onDismiss = { searching = false }, onPick = { searching = false; apply(it) })
}

private fun frequencyText(hz: Double): String =
    if (hz >= 1000) "%.2f kHz".format(hz / 1000).replace(".00 ", " ") else "%.0f Hz".format(hz)

private fun displayName(context: android.content.Context, uri: android.net.Uri): String = try {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }?.substringBeforeLast('.')?.removeSuffix(" ParametricEQ") ?: "Imported"
} catch (_: Exception) {
    "Imported"
}

@Composable
private fun AutoEqDialog(onDismiss: () -> Unit, onPick: (EqProfile) -> Unit) {
    val aw = Aw.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AutoEqEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            entries = AutoEqCatalog.index(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            error = context.getString(R.string.eq_autoeq_offline)
        }
    }
    val results = remember(entries, query) { AutoEqCatalog.search(entries.orEmpty(), query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = aw.raised,
        title = { Text(stringResource(R.string.eq_autoeq_title), style = Aw.heading, color = aw.paper) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, textStyle = Aw.body,
                    placeholder = { Text(stringResource(R.string.eq_autoeq_hint), style = Aw.small, color = aw.muted) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = aw.violet, unfocusedBorderColor = aw.line,
                        focusedTextColor = aw.paper, unfocusedTextColor = aw.paper, cursorColor = aw.violet,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> Text(error!!, style = Aw.small, color = aw.amber)
                    entries == null || busy -> CircularProgressIndicator(Modifier.size(18.dp), color = aw.violet, strokeWidth = 1.5.dp)
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(results, key = { it.path }) { entry ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    busy = true
                                    scope.launch {
                                        val profile = runCatching { AutoEqCatalog.profile(entry) }.getOrNull()
                                        busy = false
                                        if (profile != null) onPick(profile)
                                        else error = context.getString(R.string.eq_import_failed)
                                    }
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(entry.name, style = Aw.body, color = aw.paper)
                                Text(entry.source, style = Aw.small, color = aw.muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel).uppercase(), style = Aw.navLabel, color = aw.muted) }
        },
    )
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

