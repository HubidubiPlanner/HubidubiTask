package com.hubidubios.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// A muted slate accent rather than a saturated one. In a list that is mostly
// text, a bright colour reads as an alert; this is meant to mark structure —
// which project a row belongs to — without shouting about it.
private val SlateLight = Color(0xFF3F4F5F)
private val SlateDark = Color(0xFF9FB2C4)

private val InkLight = Color(0xFF1A1C1E)
private val InkDark = Color(0xFFE3E4E6)

// Paper, not white: a flat off-tone keeps large text blocks from glaring, and
// surfaces sit on it without needing borders or elevation to be legible.
private val PaperLight = Color(0xFFF7F7F5)
private val PaperDark = Color(0xFF14161A)

private val MutedLight = Color(0xFF6B7280)
private val MutedDark = Color(0xFF98A1AE)

private val Light = lightColorScheme(
    primary = SlateLight,
    onPrimary = Color.White,
    secondary = SlateLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = MutedLight,
    outline = Color(0xFFD5D5D0),
    error = Color(0xFF9B3A32),          // brick, not fire-engine
)

private val Dark = darkColorScheme(
    primary = SlateDark,
    onPrimary = Color(0xFF12171C),
    secondary = SlateDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = Color(0xFF1E2228),
    onSurfaceVariant = MutedDark,
    outline = Color(0xFF2E343C),
    error = Color(0xFFC98078),
)

// Square. Rounded corners are Material's default voice; this app is a list of
// plain text on plain paper, and hard edges suit it.
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun HubidubiOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = SquareShapes,
        content = content,
    )
}
