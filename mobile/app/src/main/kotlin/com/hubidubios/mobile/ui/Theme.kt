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

// The palette: five earth tones, dark to light. Everything below is one of
// these or a lighter/darker step of one, so the app reads as a single family.
private val Moss = Color(0xFF56594D)
private val Sage = Color(0xFF848072)
private val Clay = Color(0xFFB4997E)
private val Sand = Color(0xFFCFC2AB)
private val Cream = Color(0xFFD3C9AF)

// Clay is the only warm tone, so it is the accent. It marks structure —
// which project a row belongs to — without shouting; on light paper it is
// taken a step darker so accent text still clears 4.5:1.
private val ClayDeep = Color(0xFF7A624C)

// Ink is Moss taken nearly to black, so text stays in the same olive family.
private val InkLight = Color(0xFF2E3028)

// Paper, not white: a flat off-tone keeps large text blocks from glaring, and
// surfaces sit on it without needing borders or elevation to be legible.
// Light paper is Cream lifted; dark paper is Moss pushed down.
private val PaperLight = Color(0xFFF4F0E6)
private val PaperDark = Color(0xFF1C1D18)

// Sage taken a step darker/lighter so secondary text is still readable.
private val MutedLight = Color(0xFF65625A)
private val MutedDark = Color(0xFF9A9686)

private val Light = lightColorScheme(
    primary = ClayDeep,
    onPrimary = PaperLight,
    secondary = Moss,
    onSecondary = PaperLight,
    secondaryContainer = Sand,
    onSecondaryContainer = InkLight,
    tertiary = Sage,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = Color(0xFFE8E1D0),
    onSurfaceVariant = MutedLight,
    outline = Sand,
    outlineVariant = Cream,
    error = Color(0xFF9B3A32),          // brick, not fire-engine
)

private val Dark = darkColorScheme(
    primary = Clay,
    onPrimary = PaperDark,
    secondary = Sand,
    onSecondary = PaperDark,
    secondaryContainer = Moss,
    onSecondaryContainer = Cream,
    tertiary = Sage,
    background = PaperDark,
    onBackground = Cream,
    surface = PaperDark,
    onSurface = Cream,
    surfaceVariant = Color(0xFF2A2C25),
    onSurfaceVariant = MutedDark,
    outline = Moss,
    outlineVariant = Color(0xFF3A3C33),
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
