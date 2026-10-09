package com.aiproject.musicplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aiproject.musicplayer.R

/**
 * Design tokens shared with airwitech.com: near-black "ink", warm "paper",
 * violet / amber / cyan tones, hairline rules, square corners and soft glow.
 */
@Immutable
data class AwColors(
    val isDark: Boolean,
    val ink: Color,
    val raised: Color,
    val paper: Color,
    val head: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val violet: Color,
    val amber: Color,
    val cyan: Color,
    val washA: Color,
    val washB: Color,
    /** Glow strength: full in the dark theme, subdued in the light one. */
    val glow: Float,
) {
    val accent: Color get() = cyan
}

val DarkTokens = AwColors(
    isDark = true,
    ink = Color(0xFF06070C),
    raised = Color(0xFF0D0F17),
    paper = Color(0xFFF4F0E9),
    head = Color(0xFFFFFAF0),
    text = Color(0xFFC9C6D3),
    muted = Color(0xFF8F8CA0),
    line = Color(0x24F4F0E9),
    violet = Color(0xFFAB9DFF),
    amber = Color(0xFFFFB95F),
    cyan = Color(0xFF62E4FF),
    washA = Color(0x17AB9DFF),
    washB = Color(0x1262E4FF),
    glow = 1f,
)

val LightTokens = AwColors(
    isDark = false,
    ink = Color(0xFFF6F3ED),
    raised = Color(0xFFFFFDF8),
    paper = Color(0xFF17151F),
    head = Color(0xFF17151F),
    text = Color(0xFF3D3A49),
    muted = Color(0xFF625F72),
    line = Color(0x2917151F),
    violet = Color(0xFF5B46E0),
    amber = Color(0xFFB35A00),
    cyan = Color(0xFF04779A),
    washA = Color(0x125B46E0),
    washB = Color(0x0F04779A),
    glow = 0.32f,
)

val LocalAw = staticCompositionLocalOf { DarkTokens }

object Aw {
    val colors: AwColors @Composable get() = LocalAw.current

    val Display = FontFamily(
        Font(R.font.sora_400, FontWeight.Normal),
        Font(R.font.sora_500, FontWeight.Medium),
        Font(R.font.sora_600, FontWeight.SemiBold),
    )
    val Body = FontFamily(
        Font(R.font.source_sans_400, FontWeight.Normal),
        Font(R.font.source_sans_600, FontWeight.SemiBold),
    )

    /** Uppercase, widely tracked label ("eyebrow" on the site). */
    val eyebrow = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.22.em)
    val navLabel = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.18.em)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.03).em)
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 23.sp, letterSpacing = (-0.02).em)
    val wordmark = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = (-0.02).em)
    val mono = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.04.em)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyStrong = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 18.sp)
}

enum class ThemeMode(val id: Int) {
    SYSTEM(0), DARK(1), LIGHT(2);

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

@Composable
fun HiFiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val aw = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(
            primary = aw.cyan, onPrimary = aw.ink, secondary = aw.violet, onSecondary = aw.ink,
            tertiary = aw.amber, background = aw.ink, onBackground = aw.text, surface = aw.raised,
            onSurface = aw.text, surfaceVariant = aw.raised, onSurfaceVariant = aw.muted,
            surfaceContainerHigh = aw.raised, surfaceContainer = aw.raised, surfaceContainerHighest = aw.raised,
            outline = aw.line, outlineVariant = aw.line, error = Color(0xFFFF8A80),
        )
    } else {
        lightColorScheme(
            primary = aw.cyan, onPrimary = aw.ink, secondary = aw.violet, onSecondary = aw.ink,
            tertiary = aw.amber, background = aw.ink, onBackground = aw.text, surface = aw.raised,
            onSurface = aw.text, surfaceVariant = aw.raised, onSurfaceVariant = aw.muted,
            surfaceContainerHigh = aw.raised, surfaceContainer = aw.raised, surfaceContainerHighest = aw.raised,
            outline = aw.line, outlineVariant = aw.line, error = Color(0xFFB3261E),
        )
    }
    val square = RoundedCornerShape(0.dp)
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = Aw.body, bodyMedium = Aw.small, bodySmall = Aw.small,
            titleLarge = Aw.heading, titleMedium = Aw.bodyStrong, labelLarge = Aw.eyebrow,
            labelMedium = Aw.navLabel, labelSmall = Aw.navLabel, headlineSmall = Aw.heading,
        ),
        shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square),
    ) {
        CompositionLocalProvider(LocalAw provides aw, content = content)
    }
}
