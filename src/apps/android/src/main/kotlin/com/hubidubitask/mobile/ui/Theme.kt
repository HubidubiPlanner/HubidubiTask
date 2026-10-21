package com.hubidubitask.mobile.ui

import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.hubidubitask.mobile.R

// Six colour themes, chosen in Settings ([ColorTheme]). Ivory, the default,
// is warm ivory and terracotta, light or dark as the system is. Sand is the
// app's earlier look: five earth tones and their steps (Moss #56594D · Sage
// #848072 · Clay #B4997E · Sand #CFC2AB · Cream #D3C9AF), light as "Piasek" and
// dark as "Mech", also as the system is. Paper and Forest are always light,
// Night and Ocean always dark. In each, body text is 7:1 or better on the page and on cards,
// secondary text and links 4.5:1, borders 3:1 (ThemeContrastTest).

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

// Ivory, the default, light or dark as the system is.
// Ivory paper, white cards, near-black ink, terracotta, taken a step
// darker in light (a step lighter in dark) so links keep 4.5:1.
private val IvoryLight = AppColors(
    paper = Color(0xFFF4F3EE),
    raised = Color(0xFFFFFFFF),
    line = Color(0xFF87857B),
    ink = Color(0xFF141413),
    muted = Color(0xFF3D3D3A),
    accent = Color(0xFFA84E2E),
    frame = Color(0xFF3D3D3A),
    glyph = Color(0xFF73726C),
    well = Color(0xFFE6E3D8),
    button = Color(0xFFB0532F),
    onButton = Color(0xFFFFFFFF),
    onButtonMuted = Color(0xFFF5DDD3),
    nav = Color(0xFFE9E7DE),
    danger = Color(0xFFA1271B),
    ok = Color(0xFF3D6B35),
)

// Ivory, dark: warm charcoal, ivory ink, the terracotta button with dark text.
private val IvoryDark = AppColors(
    paper = Color(0xFF262624),
    raised = Color(0xFF30302E),
    line = Color(0xFF85837A),
    ink = Color(0xFFFAF9F5),
    muted = Color(0xFFC2C0B6),
    accent = Color(0xFFE3896B),
    frame = Color(0xFFC2C0B6),
    glyph = Color(0xFF9C9A92),
    well = Color(0xFF3D3C38),
    button = Color(0xFFD97757),
    onButton = Color(0xFF141413),
    onButtonMuted = Color(0xFF4A2A1E),
    nav = Color(0xFF1F1E1D),
    danger = Color(0xFFF2A499),
    ok = Color(0xFF9CCB8A),
)

// Sand, light: sand paper, clay accent;
// darker words and borders than before, cards clearly lighter than the page.
private val Piasek = AppColors(
    paper = Color(0xFFE7DEC9),
    raised = Color(0xFFFCFAF5),
    line = Color(0xFF857558),
    ink = Color(0xFF16170F),
    muted = Color(0xFF3A3731),
    accent = Color(0xFF5A4330),
    frame = Color(0xFF2E3028),
    glyph = Color(0xFF7A6449),
    well = Color(0xFFC2B392),
    button = Color(0xFF4F3B2A),
    onButton = Color(0xFFFFFFFF),
    onButtonMuted = Color(0xFFEDE3D3),
    nav = Color(0xFFD5C9AD),
    danger = Color(0xFF7E2119),          // brick, not fire-engine
    ok = Color(0xFF3D5A2C),
)

// Sand, dark: moss taken nearly to black, cream ink, clay accent.
private val Mech = AppColors(
    paper = Color(0xFF12130F),
    raised = Color(0xFF23251D),
    line = Color(0xFF7C806E),
    ink = Color(0xFFF6F0E2),
    muted = Color(0xFFD2CDBE),
    accent = Color(0xFFE2C7A6),
    frame = Color(0xFFC4C0AE),
    glyph = Color(0xFF8F9381),
    well = Color(0xFF3E4136),
    button = Color(0xFFE2C7A6),
    onButton = Color(0xFF12130F),
    onButtonMuted = Color(0xFF3A3129),
    nav = Color(0xFF0B0C09),
    danger = Color(0xFFF4B0A6),
    ok = Color(0xFFB4CE9A),
)

// Paper, always light: black on white, a deep blue for links and buttons.
private val Paper = AppColors(
    paper = Color(0xFFF3F3F1),
    raised = Color(0xFFFFFFFF),
    line = Color(0xFF6E6E6E),
    ink = Color(0xFF000000),
    muted = Color(0xFF2E2E2E),
    accent = Color(0xFF0A3A8C),
    frame = Color(0xFF1A1A1A),
    glyph = Color(0xFF555555),
    well = Color(0xFFD9D9D6),
    button = Color(0xFF0A3A8C),
    onButton = Color(0xFFFFFFFF),
    onButtonMuted = Color(0xFFD6E0F2),
    nav = Color(0xFFE4E4E1),
    danger = Color(0xFFA1001C),
    ok = Color(0xFF17642A),
)

// Night, always dark: white on black, amber for links and buttons.
private val Night = AppColors(
    paper = Color(0xFF000000),
    raised = Color(0xFF161616),
    line = Color(0xFF8C8C8C),
    ink = Color(0xFFFFFFFF),
    muted = Color(0xFFD6D6D6),
    accent = Color(0xFFFFD24D),
    frame = Color(0xFFE0E0E0),
    glyph = Color(0xFFA0A0A0),
    well = Color(0xFF2A2A2A),
    button = Color(0xFFFFD24D),
    onButton = Color(0xFF000000),
    onButtonMuted = Color(0xFF4A3C10),
    nav = Color(0xFF0A0A0A),
    danger = Color(0xFFFF8F85),
    ok = Color(0xFF8FE38F),
)

// Forest, always light: deep green on pale green.
private val Forest = AppColors(
    paper = Color(0xFFE7F0E5),
    raised = Color(0xFFFAFDF8),
    line = Color(0xFF6E8C73),
    ink = Color(0xFF08160C),
    muted = Color(0xFF263D2C),
    accent = Color(0xFF174A2A),
    frame = Color(0xFF14261A),
    glyph = Color(0xFF557560),
    well = Color(0xFFC8DCC6),
    button = Color(0xFF174A2A),
    onButton = Color(0xFFFFFFFF),
    onButtonMuted = Color(0xFFD2E6D5),
    nav = Color(0xFFD3E3D1),
    danger = Color(0xFF981F14),
    ok = Color(0xFF17602E),
)

// Ocean, always dark: white on deep navy, sky blue for links and buttons.
private val Ocean = AppColors(
    paper = Color(0xFF0A1525),
    raised = Color(0xFF13233A),
    line = Color(0xFF6A89B2),
    ink = Color(0xFFF4F8FF),
    muted = Color(0xFFC3D1E6),
    accent = Color(0xFF86CBFF),
    frame = Color(0xFFB8C8DE),
    glyph = Color(0xFF7894B8),
    well = Color(0xFF1E3352),
    button = Color(0xFF86CBFF),
    onButton = Color(0xFF06111F),
    onButtonMuted = Color(0xFF1E3A5A),
    nav = Color(0xFF060E19),
    danger = Color(0xFFFFA397),
    ok = Color(0xFF96E0A6),
)

/**
 * The colour themes Settings offers, in its order. Ivory and Sand follow the
 * system's light or dark mode ([dark] null); the others are always the one
 * they are.
 */
enum class ColorTheme(val label: String, val dark: Boolean?, private val key: String? = null) {
    // Kept as "claude", the name it first shipped under, so a choice made then stays.
    IVORY("Ivory", null, key = "claude"),
    SAND("Sand", null),
    PAPER("Paper", false),
    NIGHT("Night", true),
    FOREST("Forest", false),
    OCEAN("Ocean", true);

    /** Kept under this name. */
    val id: String get() = key ?: name.lowercase()

    /** What Settings says of it, on its right in the list. */
    val mode: String get() = when (dark) { null -> "Follows the system"; true -> "Dark"; false -> "Light" }

    companion object {
        /** A kept choice; Ivory when none, or one this version doesn't know, was kept. */
        fun of(id: String?): ColorTheme = entries.firstOrNull { it.id == id } ?: IVORY
    }
}

/** [theme]'s colours and whether they are dark; [systemDark] matters to Ivory and Sand alone. */
internal fun paletteOf(theme: ColorTheme, systemDark: Boolean): Pair<AppColors, Boolean> = when (theme) {
    ColorTheme.IVORY -> if (systemDark) IvoryDark to true else IvoryLight to false
    ColorTheme.SAND -> if (systemDark) Mech to true else Piasek to false
    ColorTheme.PAPER -> Paper to false
    ColorTheme.NIGHT -> Night to true
    ColorTheme.FOREST -> Forest to false
    ColorTheme.OCEAN -> Ocean to true
}

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

private val LocalAppColors = staticCompositionLocalOf { IvoryLight }

object HubidubiTaskTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

/** [theme]'s colours, light or dark as it says (Ivory and Sand as the system is). */
@Composable
fun HubidubiTaskTheme(theme: ColorTheme = ColorTheme.IVORY, content: @Composable () -> Unit) {
    val (colors, dark) = paletteOf(theme, isSystemInDarkTheme())
    // The status and navigation bar icons follow the theme, not the system:
    // light icons on Night's page even when the phone is in light mode.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = schemeOf(colors, dark),
            typography = AppTypography,
            shapes = SquareShapes,
            content = content,
        )
    }
}
