package com.hubidubitask.mobile.ui

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
import com.hubidubitask.mobile.R

// The palette: five earth tones, dark to light. Every colour below is one of
// these or a lighter/darker step of one, so the app reads as a single family.
//   Moss #56594D · Sage #848072 · Clay #B4997E · Sand #CFC2AB · Cream #D3C9AF
//
// Light is "Piasek": sand paper, clay accent. Dark is "Mech": moss taken nearly
// to black, cream ink, clay accent. Both keep text at 4.5:1 or better.

/**
 * The roles Material's scheme has no name for. Screens read these through
 * [HubidubiTaskTheme.colors] rather than hard-coding hex, so light and dark stay
 * one decision each.
 */
@Immutable
data class AppColors(
    val paper: Color,      // the page
    val raised: Color,     // cards and grouped rows sitting on the page
    val line: Color,       // hairlines between rows, card edges
    val ink: Color,        // body text
    val muted: Color,      // secondary text
    val accent: Color,     // project names, links, selected tab
    val frame: Color,      // outlines of secondary buttons and fields
    val glyph: Color,      // small row glyphs, dashed "optional" outlines
    val well: Color,       // thumbnail and placeholder fill
    val button: Color,     // filled buttons
    val onButton: Color,
    val onButtonMuted: Color,
    val nav: Color,        // bottom bar
    val danger: Color,
    val ok: Color,         // "connected", "synced"
)

private val Piasek = AppColors(
    paper = Color(0xFFE9E1CF),
    raised = Color(0xFFF5F0E4),
    line = Color(0xFFCFC2AB),
    ink = Color(0xFF2E3028),
    muted = Color(0xFF5E5B52),
    accent = Color(0xFF7A624C),
    frame = Color(0xFF56594D),
    glyph = Color(0xFFB4997E),
    well = Color(0xFFCFC2AB),
    button = Color(0xFF7A624C),
    onButton = Color(0xFFF8F4EB),
    onButtonMuted = Color(0xFFE8DCCB),
    nav = Color(0xFFDDD3BC),
    danger = Color(0xFF9B3A32),          // brick, not fire-engine
    ok = Color(0xFF5F7A4C),
)

private val Mech = AppColors(
    paper = Color(0xFF1C1D18),
    raised = Color(0xFF25271F),
    line = Color(0xFF3A3C33),
    ink = Color(0xFFD3C9AF),
    muted = Color(0xFF9A9686),
    accent = Color(0xFFB4997E),
    frame = Color(0xFF848072),
    glyph = Color(0xFF56594D),
    well = Color(0xFF3A3C33),
    button = Color(0xFFB4997E),
    onButton = Color(0xFF1C1D18),
    onButtonMuted = Color(0xFF3A3129),
    nav = Color(0xFF151612),
    danger = Color(0xFFD08A80),
    ok = Color(0xFF8FA877),
)

private fun schemeOf(c: AppColors, dark: Boolean) = if (dark) darkColorScheme(
    primary = c.button, onPrimary = c.onButton,
    primaryContainer = c.well, onPrimaryContainer = c.ink,
    secondary = c.frame, onSecondary = c.paper,
    secondaryContainer = c.well, onSecondaryContainer = c.ink,
    tertiary = c.accent,
    background = c.paper, onBackground = c.ink,
    surface = c.paper, onSurface = c.ink,
    surfaceVariant = c.raised, onSurfaceVariant = c.muted,
    surfaceContainerLowest = c.paper, surfaceContainerLow = c.raised,
    surfaceContainer = c.raised, surfaceContainerHigh = c.raised,
    surfaceContainerHighest = c.line,
    outline = c.frame, outlineVariant = c.line,
    error = c.danger,
) else lightColorScheme(
    primary = c.button, onPrimary = c.onButton,
    primaryContainer = c.well, onPrimaryContainer = c.ink,
    secondary = c.frame, onSecondary = c.paper,
    secondaryContainer = c.well, onSecondaryContainer = c.ink,
    tertiary = c.accent,
    background = c.paper, onBackground = c.ink,
    surface = c.paper, onSurface = c.ink,
    surfaceVariant = c.raised, onSurfaceVariant = c.muted,
    surfaceContainerLowest = c.raised, surfaceContainerLow = c.raised,
    surfaceContainer = c.raised, surfaceContainerHigh = c.raised,
    surfaceContainerHighest = c.line,
    outline = c.frame, outlineVariant = c.line,
    error = c.danger,
)

// One typeface for everything, IBM Plex Sans: titles are its semibold,
// the rest its regular and medium. Its digits are all one width, so dates and
// counts still line up in a column without a separate mono face.
private val Sans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_semibold, FontWeight.Bold),
)

private val AppTypography = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = Sans)
    Typography(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(),
        displaySmall = t.displaySmall.sans(),
        headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em),
        headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp, lineHeight = 29.sp),
        headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp, lineHeight = 27.sp),
        titleLarge = t.titleLarge.sans(),
        titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, lineHeight = 21.sp),
        bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,
            fontSize = 12.sp, lineHeight = 16.sp),
        // Section labels: small caps-style, tracked out.
        labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    )
}

// Square. Rounded corners are Material's default voice; this app is a list of
// plain text on plain paper, and hard edges suit it.
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

private val LocalAppColors = staticCompositionLocalOf { Piasek }

object HubidubiTaskTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

@Composable
fun HubidubiTaskTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Mech else Piasek
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = schemeOf(colors, dark),
            typography = AppTypography,
            shapes = SquareShapes,
            content = content,
        )
    }
}
