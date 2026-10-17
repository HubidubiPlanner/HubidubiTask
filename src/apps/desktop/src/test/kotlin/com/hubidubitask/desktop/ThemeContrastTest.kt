package com.hubidubitask.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.hubidubitask.desktop.ui.ColorTheme
import com.hubidubitask.desktop.ui.paletteOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every colour theme, in the system's light and dark mode, keeps its words and
 * borders readable: body text 7:1 on the page and on cards, secondary text,
 * links and warnings 4.5:1, borders and glyphs 3:1 (WCAG's ratios).
 */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun `every theme is readable in either system mode`() {
        val low = mutableListOf<String>()
        for (theme in ColorTheme.entries) for (systemDark in listOf(false, true)) {
            val (c, _) = paletteOf(theme, systemDark)
            fun need(name: String, fg: Color, bg: Color, min: Double) {
                val r = ratio(fg, bg)
                if (r < min) low += "$theme${if (systemDark) " (dark)" else ""} $name ${"%.2f".format(r)} < $min"
            }
            for ((bg, back) in listOf("page" to c.paper, "card" to c.raised)) {
                need("text on $bg", c.ink, back, 7.0)
                need("secondary text on $bg", c.muted, back, 4.5)
                need("links on $bg", c.accent, back, 4.5)
                need("warnings on $bg", c.danger, back, 4.5)
                need("lines on $bg", c.line, back, 3.0)
                need("outlines on $bg", c.frame, back, 3.0)
            }
            need("glyphs on card", c.glyph, c.raised, 3.0)
            need("button text", c.onButton, c.button, 4.5)
            need("tab labels", c.muted, c.nav, 4.5)
            need("synced dot", c.ok, c.paper, 3.0)
        }
        assertTrue(low.isEmpty(), low.joinToString("\n"))
    }

    @Test fun `a theme is light or dark as it says, Sand as the system is`() {
        for (theme in ColorTheme.entries) for (systemDark in listOf(false, true)) {
            val (c, dark) = paletteOf(theme, systemDark)
            assertEquals(theme.dark ?: systemDark, dark, "$theme")
            assertEquals(dark, c.paper.luminance() < 0.5f, "$theme's page")
        }
    }

    @Test fun `a kept choice comes back, an unknown one is Sand`() {
        for (theme in ColorTheme.entries) assertEquals(theme, ColorTheme.of(theme.id))
        assertEquals(ColorTheme.SAND, ColorTheme.of(null))
        assertEquals(ColorTheme.SAND, ColorTheme.of("violet"))
    }
}
