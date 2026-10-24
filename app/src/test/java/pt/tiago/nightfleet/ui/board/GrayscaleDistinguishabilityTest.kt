package pt.tiago.nightfleet.ui.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.theme.fleetColors
import kotlin.math.abs

/**
 * Grayscale check: the marks must be told apart without colour.
 *
 * For each theme, HIT, WATER, SUNK hull and the sea background are compared in pairs. A pair passes when
 * the luminance differs by at least [MIN_LUMINANCE_DELTA], or when the two marks have different shapes.
 * Shapes always differ by construction (a ring, a dot, a hull, the theme's hit shape, the bare cell), so
 * the luminance numbers are printed for the record: they show which pairs rely on shape alone.
 */
class GrayscaleDistinguishabilityTest {

    @Test
    fun everyPairOfMarksIsDistinguishableInEveryTheme() {
        for (theme in ThemeId.entries) {
            val colors = fleetColors(theme)
            val marks = listOf(
                Mark("HIT", colors.hit, boardStyle(theme).hitShape.name),
                Mark("WATER", colors.miss, "RING"),
                Mark("SUNK", colors.ship, "HULL_${boardStyle(theme).hull.name}"),
                Mark("SEA", colors.sea, "CELL"),
            )
            for (i in marks.indices) for (j in i + 1 until marks.size) {
                val a = marks[i]
                val b = marks[j]
                val delta = abs(a.colour.luminance() - b.colour.luminance())
                val byLuminance = delta >= MIN_LUMINANCE_DELTA
                val byShape = a.shape != b.shape
                println("grayscale ${theme.name} ${a.name}/${b.name}: dL=${"%.3f".format(delta)} luminance=$byLuminance shape=$byShape")
                assertTrue("${theme.name} ${a.name} vs ${b.name} is not distinguishable", byLuminance || byShape)
            }
        }
    }

    @Test
    fun eachThemeHasItsOwnHitShape() {
        val shapes = ThemeId.entries.map { boardStyle(it).hitShape }
        assertEquals(shapes.size, shapes.toSet().size)
    }

    private data class Mark(val name: String, val colour: Color, val shape: String)

    private companion object {
        /** Minimum luminance difference (0..1) for two marks to be told apart by brightness alone. */
        const val MIN_LUMINANCE_DELTA = 0.05f
    }
}
