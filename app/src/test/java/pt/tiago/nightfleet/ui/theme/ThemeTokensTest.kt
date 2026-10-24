package pt.tiago.nightfleet.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Checks the colour tokens and the WCAG contrast of the text tokens.
 */
class ThemeTokensTest {

    /** Expected hex per token, in the order of ThemeId: CHART, SONAR, PAPER, HOLO. */
    private val expected: Map<String, List<Long>> = mapOf(
        "bg" to listOf(0x0B1528L, 0x03100EL, 0x17204AL, 0x050911L),
        "bg2" to listOf(0x13233FL, 0x06221EL, 0x242E63L, 0x0B1424L),
        "surface" to listOf(0x13243FL, 0x0A201DL, 0x252F65L, 0x0C1627L),
        "surface2" to listOf(0x1A2E50L, 0x0E2C28L, 0x2E3976L, 0x122038L),
        "line" to listOf(0x2C4670L, 0x1A4A41L, 0x414D90L, 0x1F3757L),
        "text" to listOf(0xF2E8D0L, 0xD4FFEEL, 0xFFF7EAL, 0xE8F4FFL),
        "textMuted" to listOf(0xA9A58FL, 0x79B2A3L, 0xBBBEE2L, 0x7F99BAL),
        "accent" to listOf(0xE0B154L, 0x3CFFB4L, 0xFFC75AL, 0xFF8B3DL),
        "onAccent" to listOf(0x1B1306L, 0x02130CL, 0x2B1A00L, 0x1E0C00L),
        "sea" to listOf(0x102544L, 0x05201BL, 0x1F2B68L, 0x08121FL),
        "grid" to listOf(0x1B3459L, 0x0C302AL, 0x2A3780L, 0x11223AL),
        "hit" to listOf(0xD2493AL, 0xFFB23CL, 0xFF6F61L, 0xFF8B3DL),
        "miss" to listOf(0x97A6C4L, 0x3D8A77L, 0xA3B5FFL, 0x3DD6FFL),
        "ship" to listOf(0x263E64L, 0x145846L, 0xF1E3CFL, 0x0E3653L),
        "shipEdge" to listOf(0xE0B154L, 0x3CFFB4L, 0xFFFFFFL, 0x3DD6FFL),
        "island" to listOf(0x3D3524L, 0x22352BL, 0x2E7A63L, 0x18203AL),
        "danger" to listOf(0xD2493AL, 0xFF6B6BL, 0xFF6F61L, 0xFF4D6DL),
        "difficultyEasy" to listOf(0x9ECE6AL, 0x9ECE6AL, 0x9ECE6AL, 0x9ECE6AL),
        "difficultyMedium" to listOf(0xE0AF68L, 0xE0AF68L, 0xE0AF68L, 0xE0AF68L),
        "difficultyHard" to listOf(0xF7768EL, 0xF7768EL, 0xF7768EL, 0xF7768EL),
    )

    private val themes = ThemeId.entries

    private fun token(colors: FleetColors, name: String): Color = when (name) {
        "bg" -> colors.bg
        "bg2" -> colors.bg2
        "surface" -> colors.surface
        "surface2" -> colors.surface2
        "line" -> colors.line
        "text" -> colors.text
        "textMuted" -> colors.textMuted
        "accent" -> colors.accent
        "onAccent" -> colors.onAccent
        "sea" -> colors.sea
        "grid" -> colors.grid
        "hit" -> colors.hit
        "miss" -> colors.miss
        "ship" -> colors.ship
        "shipEdge" -> colors.shipEdge
        "island" -> colors.island
        "danger" -> colors.danger
        "difficultyEasy" -> colors.difficultyEasy
        "difficultyMedium" -> colors.difficultyMedium
        "difficultyHard" -> colors.difficultyHard
        else -> error("Unknown token $name")
    }

    private fun hex(color: Color): Long {
        fun channel(value: Float) = Math.round(value * 255f).toLong()
        return (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }

    private fun luminance(color: Color): Double {
        fun linear(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun everyThemeHasEveryTokenWithTheDesignHex() {
        assertEquals("Every token of DESIGN section 2 is listed", 20, expected.size)
        for ((index, themeId) in themes.withIndex()) {
            val colors = fleetColors(themeId)
            for ((name, values) in expected) {
                val actual = token(colors, name)
                assertEquals("$themeId.$name", values[index], hex(actual))
                assertEquals("$themeId.$name must be opaque", 1f, actual.alpha, 0f)
            }
        }
    }

    @Test
    fun textAndMutedTextReachAaContrastOnSurface() {
        val report = StringBuilder()
        for (themeId in themes) {
            val colors = fleetColors(themeId)
            val text = contrast(colors.text, colors.surface)
            val muted = contrast(colors.textMuted, colors.surface)
            report.append("$themeId text/surface=%.2f textMuted/surface=%.2f\n".format(text, muted))
            assertTrue("$themeId text/surface $text < 4.5", text >= 4.5)
            assertTrue("$themeId textMuted/surface $muted < 4.5", muted >= 4.5)
        }
        println("WCAG contrast:\n$report")
    }

    @Test
    fun colorblindPalettesOverrideHitMissAndAccentOnly() {
        val expectedOverrides = mapOf(
            ColorblindPalette.DEUTERANOPIA to Triple(0xF28E2BL, 0x4E9BFFL, 0xF1CE63L),
            ColorblindPalette.PROTANOPIA to Triple(0xF5C342L, 0x4E9BFFL, 0xF5C342L),
            ColorblindPalette.TRITANOPIA to Triple(0xE8505BL, 0x2EC4B6L, 0xF1CE63L),
        )
        for (themeId in themes) {
            val base = fleetColors(themeId)
            for ((palette, hexes) in expectedOverrides) {
                val colors = fleetColors(themeId, palette)
                assertEquals("$themeId $palette hit", hexes.first, hex(colors.hit))
                assertEquals("$themeId $palette miss", hexes.second, hex(colors.miss))
                assertEquals("$themeId $palette accent", hexes.third, hex(colors.accent))
                assertEquals("$themeId $palette keeps ship", base.ship, colors.ship)
                assertEquals("$themeId $palette keeps surface", base.surface, colors.surface)
            }
            assertEquals(base, fleetColors(themeId, ColorblindPalette.NORMAL))
        }
    }

    @Test
    fun grandAdmiralFinishSetsGoldAccentAndShipEdge() {
        val gold = Color(0xFFE8C66A)
        for (themeId in themes) {
            val colors = fleetColors(themeId, grandAdmiral = true)
            assertEquals("$themeId accent", gold, colors.accent)
            assertEquals("$themeId shipEdge", gold, colors.shipEdge)
            assertEquals("$themeId surface unchanged", fleetColors(themeId).surface, colors.surface)
        }
    }

    @Test
    fun shapesFollowDesignSection2() {
        assertEquals(4.dp, fleetShapes(ThemeId.CHART).card)
        assertEquals(8.dp, fleetShapes(ThemeId.SONAR).card)
        assertEquals(20.dp, fleetShapes(ThemeId.PAPER).card)
        assertEquals(6.dp, fleetShapes(ThemeId.HOLO).card)
        assertEquals(1.dp, fleetShapes(ThemeId.CHART).cell)
        assertEquals(16.dp, fleetShapes(ThemeId.PAPER).button)
        for (themeId in themes) assertEquals(28.dp, fleetShapes(themeId).sheet)
    }
}
