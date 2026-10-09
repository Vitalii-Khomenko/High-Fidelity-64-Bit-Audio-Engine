package com.aiproject.musicplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.ui.theme.Aw
import com.aiproject.musicplayer.ui.theme.AwColors

/** Stepped square glow, like the site's box-shadow halos. */
fun Modifier.pixelGlow(color: Color, strength: Float, spread: Dp = 10.dp): Modifier = drawBehind {
    if (strength <= 0f) return@drawBehind
    val s = spread.toPx()
    for (i in 1..4) {
        val k = i / 4f
        drawRect(
            color = color.copy(alpha = 0.075f * strength * (1.15f - k)),
            topLeft = Offset(-s * k, -s * k),
            size = Size(size.width + 2 * s * k, size.height + 2 * s * k),
        )
    }
}

/** Ink background with the two radial colour washes from the site. */
fun Modifier.inkBackground(aw: AwColors): Modifier = background(aw.ink).drawBehind {
    drawRect(
        Brush.radialGradient(listOf(aw.washA, Color.Transparent), Offset(size.width * 0.08f, size.height * 0.05f), size.maxDimension * 0.75f),
    )
    drawRect(
        Brush.radialGradient(listOf(aw.washB, Color.Transparent), Offset(size.width * 0.96f, size.height * 0.96f), size.maxDimension * 0.8f),
    )
}

@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Aw.colors.amber) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 20f
        fun cell(x: Int, y: Int, s: Int, a: Float) =
            drawRect(color.copy(alpha = a), Offset(x * u, y * u), Size(s * u, s * u))
        cell(1, 14, 5, 1f)
        cell(7, 10, 4, 0.7f)
        cell(12, 6, 3, 0.45f)
        cell(16, 3, 2, 0.25f)
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Aw.colors.muted) {
    Text(text.uppercase(), modifier = modifier, style = Aw.eyebrow, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** "01  FOLDERS" with a glowing tone bar on the hairline above, as on the site's chapters. */
@Composable
fun SectionHeader(index: String, title: String, tone: Color, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val aw = Aw.colors
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(aw.line)) {
            Box(Modifier.width(72.dp).height(2.dp).pixelGlow(tone, aw.glow, 6.dp).background(tone))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(index, style = Aw.eyebrow, color = aw.muted)
            Spacer(Modifier.width(12.dp))
            Text(title.uppercase(), style = Aw.eyebrow, color = tone, modifier = Modifier.weight(1f), maxLines = 1)
            trailing()
        }
    }
}

@Composable
fun AwButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Color? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val aw = Aw.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border = when {
        !enabled -> aw.line
        pressed -> aw.paper
        tone != null -> tone.copy(alpha = 0.6f)
        else -> aw.line
    }
    val content = if (enabled) tone ?: aw.paper else aw.muted.copy(alpha = 0.5f)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .border(1.dp, border)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = Aw.navLabel, color = content, maxLines = 1)
    }
}

@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = Aw.colors.paper,
    outlined: Boolean = false,
    glow: Color? = null,
    enabled: Boolean = true,
) {
    val aw = Aw.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var m = modifier.size(size)
    if (glow != null) m = m.pixelGlow(glow, aw.glow, 10.dp).background(aw.ink)
    if (outlined || pressed) m = m.border(1.dp, if (pressed) aw.paper else glow ?: aw.line)
    Box(
        m.clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else aw.muted.copy(alpha = 0.4f), modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ToneChip(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, tone.copy(alpha = 0.45f))
            .background(tone.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = Aw.mono, color = tone, maxLines = 1)
    }
}

/** Equal-width options with a tone-coloured selection. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tone: Color = Aw.colors.cyan,
) {
    val aw = Aw.colors
    Row(modifier.fillMaxWidth().border(1.dp, aw.line)) {
        options.forEachIndexed { i, option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(if (isSelected) tone.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .drawBehind {
                        if (i > 0) drawRect(aw.line, Offset.Zero, Size(1.dp.toPx(), size.height))
                        if (isSelected) drawRect(tone, Offset.Zero, Size(size.width, 2.dp.toPx()))
                    }
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option).uppercase(), style = Aw.navLabel, color = if (isSelected) tone else aw.muted, maxLines = 1)
            }
        }
    }
}

/** Hairline track, tone fill and a square glowing "pixel" thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    tone: Color = Aw.colors.cyan,
    enabled: Boolean = true,
) {
    val aw = Aw.colors
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(32.dp),
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(12.dp).pixelGlow(aw.paper, aw.glow, 5.dp).background(if (enabled) aw.paper else aw.muted))
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) (state.value - state.valueRange.start) / span else 0f
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val y = size.height / 2
                drawRect(aw.line, Offset(0f, y - 0.5.dp.toPx()), Size(size.width, 1.dp.toPx()))
                drawRect(if (enabled) tone else aw.muted, Offset(0f, y - 1.dp.toPx()), Size(size.width * fraction, 2.dp.toPx()))
            }
        },
        colors = SliderDefaults.colors(),
    )
}

/** Visualiser: stacked square blocks, tone at the bottom fading to paper at the top. */
@Composable
fun PixelSpectrum(bands: FloatArray, modifier: Modifier = Modifier, tone: Color = Aw.colors.cyan) {
    val aw = Aw.colors
    Canvas(modifier) {
        val n = bands.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val block = 4.dp.toPx()
        val barWidth = (size.width - gap * (n - 1)) / n
        val rows = ((size.height + gap) / (block + gap)).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val lit = (bands[i].coerceIn(0f, 1f) * rows).toInt()
            val x = i * (barWidth + gap)
            for (r in 0 until rows) {
                val y = size.height - (r + 1) * block - r * gap
                val color = when {
                    r < lit - 1 -> tone.copy(alpha = 0.35f + 0.65f * (r + 1f) / rows)
                    r == lit - 1 -> aw.paper
                    else -> aw.line.copy(alpha = aw.line.alpha * 0.35f)
                }
                drawRect(color, Offset(x, y), Size(barWidth, block))
            }
        }
    }
}

@Composable
fun EmptyNote(title: String, text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val aw = Aw.colors
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp)) {
        Text(title, style = Aw.heading, color = aw.paper)
        Spacer(Modifier.height(8.dp))
        Text(text, style = Aw.body, color = aw.text)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
