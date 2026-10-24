package pt.tiago.nightfleet.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Material colour scheme must take every role from the Night Fleet tokens, in all four themes and every
 * colour-blind palette. A role left on a Material default (purple containers, black content) shows as a wrong colour.
 */
class FleetColorSchemeTest {

    @Test
    fun everyRoleComesFromTheThemeTokensInAllThemes() {
        for (themeId in ThemeId.entries) {
            for (palette in ColorblindPalette.entries) {
                val colors = fleetColors(themeId, palette)
                val s = fleetColorScheme(colors)
                val where = "$themeId/$palette"
                assertRole("primary", s.primary, colors.accent, where)
                assertRole("onPrimary", s.onPrimary, colors.onAccent, where)
                assertRole("primaryContainer", s.primaryContainer, colors.accent, where)
                assertRole("secondaryContainer", s.secondaryContainer, colors.surface2, where)
                assertRole("onSecondaryContainer", s.onSecondaryContainer, colors.text, where)
                assertRole("background", s.background, colors.bg, where)
                assertRole("onBackground", s.onBackground, colors.text, where)
                assertRole("surface", s.surface, colors.surface, where)
                assertRole("onSurface", s.onSurface, colors.text, where)
                assertRole("surfaceVariant", s.surfaceVariant, colors.surface2, where)
                assertRole("onSurfaceVariant", s.onSurfaceVariant, colors.textMuted, where)
                assertRole("outline", s.outline, colors.line, where)
                assertRole("error", s.error, colors.danger, where)
            }
        }
    }

    @Test
    fun grandAdmiralGoldReachesThePrimaryRole() {
        val colors = fleetColors(ThemeId.CHART, grandAdmiral = true)
        assertRole("primary", fleetColorScheme(colors).primary, colors.accent, "CHART/grandAdmiral")
    }

    private fun assertRole(role: String, actual: Color, expected: Color, where: String) {
        assertEquals("$where: $role must come from the tokens", expected, actual)
    }
}
