package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.roundToInt

/** Layout arithmetic: integer cells, aligned edges, magnifier threshold. */
class BoardMetricsTest {

    private val widthsDp = listOf(360, 390, 412)
    private val densities = listOf(2.0f, 2.625f, 3.0f)
    private val sizes = listOf(8, 10, 12)

    @Test
    fun cellSizeFollowsTheFormulaForEveryScreenAndBoard() {
        for (dp in widthsDp) for (density in densities) for (n in sizes) {
            val availableW = (dp * density).roundToInt()
            val layout = BoardMetrics.compute(availableW, availableHeightPx = 10_000, cols = n, rows = n, density = density)
            val label = (16f * density).roundToInt()
            val gap = (2f * density).roundToInt()
            val expected = floor((availableW - label - (n - 1) * gap).toFloat() / n).toInt()
            assertEquals("dp=$dp density=$density n=$n", expected, layout.cellPx)
        }
    }

    @Test
    fun boardNeverExceedsTheAvailableWidthAndLosesLessThanOnePixelPerColumn() {
        for (dp in widthsDp) for (density in densities) for (n in sizes) {
            val availableW = (dp * density).roundToInt()
            val layout = BoardMetrics.compute(availableW, 10_000, n, n, density)
            assertTrue("width ${layout.widthPx} > $availableW", layout.widthPx <= availableW)
            assertTrue("lost ${availableW - layout.widthPx} px", availableW - layout.widthPx < n)
        }
    }

    @Test
    fun cellEdgesLandOnWholePixelsAndStepByThePitch() {
        for (density in densities) for (n in sizes) {
            val layout = BoardMetrics.compute(700, 10_000, n, n, density)
            for (col in 0 until n - 1) {
                assertEquals(layout.pitch, layout.cellX(col + 1) - layout.cellX(col))
            }
            assertEquals(layout.labelPx, layout.cellX(0))
            assertEquals(layout.cellX(n - 1) + layout.cellPx, layout.widthPx)
        }
    }

    @Test
    fun heightBoundsTheCellWhenTheBoardIsShort() {
        val layout = BoardMetrics.compute(availableWidthPx = 1000, availableHeightPx = 300, cols = 10, rows = 10, density = 2f)
        // 300 px minus 32 px of labels and 9 gaps of 4 px, shared by 10 rows.
        assertEquals(floor((300 - 32 - 9 * 4).toFloat() / 10).toInt(), layout.cellPx)
    }

    @Test
    fun coordinatesCanBeLeftOutAndReconMarginIsReserved() {
        val plain = BoardMetrics.compute(500, 10_000, 10, 10, 2f, showCoordinates = false)
        assertEquals(0, plain.labelPx)
        val withRecon = BoardMetrics.compute(500, 10_000, 10, 10, 2f, reconMargin = true)
        assertTrue(withRecon.marginPx > 0)
        assertTrue(withRecon.widthPx <= 500)
        assertTrue(withRecon.cellPx <= BoardMetrics.compute(500, 10_000, 10, 10, 2f).cellPx)
    }

    @Test
    fun cellAtFindsTheCellUnderEachCentreAndNothingBetweenThem() {
        val layout = BoardMetrics.compute(600, 10_000, 10, 10, 2.625f)
        for (row in 0 until 10) for (col in 0 until 10) {
            val cx = layout.cellX(col) + layout.cellPx / 2f
            val cy = layout.cellY(row) + layout.cellPx / 2f
            assertEquals(row * 10 + col, layout.cellAt(cx, cy))
        }
        // The gap between column 0 and column 1 belongs to no cell.
        val gapX = (layout.cellX(0) + layout.cellPx + layout.gapPx / 2).toFloat()
        assertNull(layout.cellAt(gapX, layout.cellY(0).toFloat() + 1f))
        // The coordinate strips and the outside are not cells.
        assertNull(layout.cellAt(2f, layout.cellY(3).toFloat()))
        assertNull(layout.cellAt(layout.cellY(3).toFloat(), 2f))
        assertNull(layout.cellAt(layout.widthPx.toFloat() + 5f, 40f))
        assertNull(layout.cellAt(-1f, 40f))
    }

    @Test
    fun cellsBelowTwentySixDpNeedTheMagnifier() {
        // 12 x 12 on a 360 dp screen with the 16 dp gutters of the battle screen: about 24 dp per cell.
        val narrow = BoardMetrics.compute(((360 - 32) * 2.0f).roundToInt(), 10_000, 12, 12, 2.0f)
        assertTrue(narrow.needsMagnifier)
        // 8 x 8 on a 412 dp screen: far above the threshold.
        val wide = BoardMetrics.compute((412 * 2.625f).roundToInt(), 10_000, 8, 8, 2.625f)
        assertFalse(wide.needsMagnifier)
        for (dp in widthsDp) for (density in densities) for (n in sizes) {
            val layout = BoardMetrics.compute((dp * density).roundToInt(), 10_000, n, n, density)
            assertEquals(layout.cellPx < MAGNIFIER_THRESHOLD_DP * density, layout.needsMagnifier)
        }
    }
}
