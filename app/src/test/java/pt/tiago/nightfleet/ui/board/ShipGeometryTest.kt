package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the hull contours: one closed contour for straight ships of 1 to 5 cells and for the T-shaped
 * seaplane, every point inside the cells the ship covers. Compose drawing itself is not tested here.
 */
class ShipGeometryTest {

    private val cols = 10
    private val tolerance = 0.001f

    private fun index(row: Int, col: Int) = row * cols + col

    @Test
    fun straightShipsOfOneToFiveCellsAreOneClosedContour() {
        for (length in 1..5) {
            for (horizontal in listOf(true, false)) {
                val cells = (0 until length).map { k ->
                    if (horizontal) index(3, 2 + k) else index(2 + k, 3)
                }
                val commands = ShipGeometry.outline(cells, cols)
                assertOneClosedContour(commands, "length=$length horizontal=$horizontal")
                assertInsideCells(commands, cells, "length=$length horizontal=$horizontal")
            }
        }
    }

    @Test
    fun theSeaplaneIsOneClosedContourWithItsWingOnEitherSide() {
        // Bar of three cells on row 5, wing above (row 4) or below (row 6) the middle cell.
        val wingUp = listOf(index(4, 6), index(5, 5), index(5, 6), index(5, 7))
        val wingDown = listOf(index(6, 6), index(5, 5), index(5, 6), index(5, 7))
        // Vertical seaplane: bar on column 2, wing on the right (column 3) or left (column 1).
        val wingRight = listOf(index(4, 2), index(5, 2), index(6, 2), index(5, 3))
        val wingLeft = listOf(index(4, 2), index(5, 2), index(6, 2), index(5, 1))
        for (cells in listOf(wingUp, wingDown, wingRight, wingLeft)) {
            val commands = ShipGeometry.outline(cells, cols)
            assertOneClosedContour(commands, "seaplane $cells")
            assertInsideCells(commands, cells, "seaplane $cells")
        }
    }

    @Test
    fun anyOtherShapeFallsBackToOneContourPerCell() {
        // An L-shape is not a straight ship nor a seaplane: one rectangle per cell.
        val lShape = listOf(index(1, 1), index(2, 1), index(3, 1), index(3, 2))
        val commands = ShipGeometry.outline(lShape, cols)
        assertEquals(lShape.size, commands.count { it is PathCommand.MoveTo })
        assertEquals(lShape.size, commands.count { it == PathCommand.Close })
    }

    @Test
    fun aGapInAStraightShipIsNotOneShip() {
        val split = listOf(index(0, 0), index(0, 2))
        assertEquals(2, ShipGeometry.outline(split, cols).count { it is PathCommand.MoveTo })
    }

    @Test
    fun emptyShipGivesNoGeometry() {
        assertTrue(ShipGeometry.outline(emptyList(), cols).isEmpty())
    }

    private fun assertOneClosedContour(commands: List<PathCommand>, label: String) {
        assertEquals("$label: one MoveTo", 1, commands.count { it is PathCommand.MoveTo })
        assertEquals("$label: one Close", 1, commands.count { it == PathCommand.Close })
        assertEquals("$label: Close is last", PathCommand.Close, commands.last())
    }

    private fun assertInsideCells(commands: List<PathCommand>, cells: List<Int>, label: String) {
        val rows = cells.map { it / cols }
        val columns = cells.map { it % cols }
        val minX = columns.min().toFloat() - tolerance
        val maxX = columns.max() + 1f + tolerance
        val minY = rows.min().toFloat() - tolerance
        val maxY = rows.max() + 1f + tolerance
        for (point in points(commands)) {
            assertTrue("$label: x=${point.first} outside [$minX, $maxX]", point.first in minX..maxX)
            assertTrue("$label: y=${point.second} outside [$minY, $maxY]", point.second in minY..maxY)
        }
    }

    /** Every point the contour touches, control points included. */
    private fun points(commands: List<PathCommand>): List<Pair<Float, Float>> = commands.flatMap { command ->
        when (command) {
            is PathCommand.MoveTo -> listOf(command.x to command.y)
            is PathCommand.LineTo -> listOf(command.x to command.y)
            is PathCommand.CubicTo -> listOf(
                command.x1 to command.y1,
                command.x2 to command.y2,
                command.x to command.y,
            )
            PathCommand.Close -> emptyList()
        }
    }
}
