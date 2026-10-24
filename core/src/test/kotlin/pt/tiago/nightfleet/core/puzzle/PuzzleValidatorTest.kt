package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.base
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.gridOf
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.layoutGrid

class PuzzleValidatorTest {

    private fun validate(def: PuzzleDefinition, grid: PlayerGrid) = PuzzleValidator.validate(def, grid)

    private fun PuzzleDefinition.with(vararg clues: Pair<Int, Given>): PuzzleDefinition =
        copy(givens = clues.toMap())

    @Test
    fun correctLayoutIsSolved() {
        assertTrue(PuzzleValidator.isSolved(base, layoutGrid()))
    }

    @Test
    fun emptyGridIsIncompleteAndRowsAreUnder() {
        val report = validate(base, base.startGrid())
        assertFalse(report.isSolved)
        assertFalse(report.complete)
        assertEquals(LineStatus.UNDER, report.rowStatus[0])
        assertEquals(LineStatus.OK, report.rowStatus[1])
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun partialGridDoesNotReportFleetMismatch() {
        val grid = base.startGrid().withCell(0, PlayerCell.SHIP)
        val report = validate(base, grid)
        assertTrue(report.issues.isEmpty())
        assertFalse(report.isSolved)
    }

    @Test
    fun rowOverIsReported() {
        val grid = base.startGrid().withCell(6, PlayerCell.SHIP)
        val report = validate(base, grid)
        assertEquals(LineStatus.OVER, report.rowStatus[1])
    }

    @Test
    fun columnOverAndUnderAreReported() {
        // Column 1 needs 2 ships: three of them is OVER.
        val grid = base.startGrid().withCell(1, PlayerCell.SHIP).withCell(7, PlayerCell.SHIP).withCell(13, PlayerCell.SHIP)
        val report = validate(base, grid)
        assertEquals(LineStatus.OVER, report.colStatus[1])
        assertEquals(LineStatus.UNDER, report.colStatus[0])
    }

    @Test
    fun diagonalTouchIsDetected() {
        val grid = base.startGrid().withCell(0, PlayerCell.SHIP).withCell(7, PlayerCell.SHIP)
        val issue = validate(base, grid).issues.single { it.kind == IssueKind.DIAGONAL_TOUCH }
        assertEquals(listOf(0, 7), issue.cells)
    }

    @Test
    fun lShapeIsBadShape() {
        val grid = base.startGrid()
            .withCell(0, PlayerCell.SHIP)
            .withCell(1, PlayerCell.SHIP)
            .withCell(7, PlayerCell.SHIP)
        val issue = validate(base, grid).issues.single { it.kind == IssueKind.BAD_SHAPE }
        assertEquals(listOf(0, 1, 7), issue.cells)
    }

    @Test
    fun shipLongerThanLargestFleetShipIsBadShape() {
        val grid = base.startGrid()
            .withCell(0, PlayerCell.SHIP)
            .withCell(1, PlayerCell.SHIP)
            .withCell(2, PlayerCell.SHIP)
            .withCell(3, PlayerCell.SHIP)
        assertTrue(validate(base, grid).issues.any { it.kind == IssueKind.BAD_SHAPE })
    }

    @Test
    fun completeGridWithWrongFleetReportsMismatch() {
        // Three single ships in row 0 instead of 3, 2, 2, 1, 1, 1.
        val grid = gridOf(listOf("SWSWSW", "WWWWWW", "WWWWWW", "WWWWWW", "WWWWWW", "WWWWWW"))
        val report = validate(base, grid)
        assertTrue(report.issues.any { it.kind == IssueKind.FLEET_MISMATCH })
    }

    @Test
    fun shipOnIslandIsReported() {
        val def = base.copy(islands = listOf(35))
        val grid = def.startGrid().withCell(35, PlayerCell.SHIP)
        val issue = validate(def, grid).issues.single { it.kind == IssueKind.ON_ISLAND }
        assertEquals(listOf(35), issue.cells)
    }

    @Test
    fun islandsStartAsWater() {
        val def = base.copy(islands = listOf(35))
        assertEquals(PlayerCell.WATER, def.startGrid()[35])
    }

    @Test
    fun waterClueMarkedAsShipIsBroken() {
        val def = base.with(0 to Given.WATER)
        val report = validate(def, def.startGrid().withCell(0, PlayerCell.SHIP))
        assertEquals(listOf(0), report.issues.single { it.kind == IssueKind.GIVEN_MISMATCH }.cells)
    }

    @Test
    fun shipClueMarkedAsWaterIsBroken() {
        val def = base.with(0 to Given.SHIP)
        val report = validate(def, def.startGrid().withCell(0, PlayerCell.WATER))
        assertEquals(listOf(0), report.issues.single { it.kind == IssueKind.GIVEN_MISMATCH }.cells)
    }

    @Test
    fun endClueWithShipOnWrongSideIsBroken() {
        // Cell 12 is the west end of the horizontal ship 12-13: it is END_W, not END_N.
        val wrong = base.with(12 to Given.END_N)
        assertTrue(validate(wrong, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
        val right = base.with(12 to Given.END_W)
        assertFalse(validate(right, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
    }

    @Test
    fun subClueNeedsIsolatedShip() {
        val def = base.with(0 to Given.SUB)
        assertTrue(validate(def, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
        val isolated = base.with(5 to Given.SUB)
        assertFalse(validate(isolated, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
    }

    @Test
    fun middleClueMustBeInteriorOnceComplete() {
        val interior = base.with(1 to Given.MIDDLE)
        assertFalse(validate(interior, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
        val end = base.with(0 to Given.MIDDLE)
        assertTrue(validate(end, layoutGrid()).issues.any { it.kind == IssueKind.GIVEN_MISMATCH })
    }

    @Test
    fun errorsListWrongMarksAndIgnoreEmptyCells() {
        val solution = layoutGrid()
        val grid = base.startGrid()
            .withCell(0, PlayerCell.WATER)  // wrong: solution has a ship
            .withCell(6, PlayerCell.SHIP)   // wrong: solution has water
            .withCell(1, PlayerCell.SHIP)   // right
        assertEquals(listOf(0, 6), PuzzleValidator.errors(grid, solution))
    }

    @Test
    fun completeCorrectGridHasNoErrors() {
        assertEquals(emptyList<Int>(), PuzzleValidator.errors(layoutGrid(), layoutGrid()))
    }

    @Test
    fun emptyGridHasNoErrors() {
        assertEquals(emptyList<Int>(), PuzzleValidator.errors(base.startGrid(), layoutGrid()))
    }

    @Test
    fun shipComponentsAreSortedAndGroupedOrthogonally() {
        val grid = base.startGrid()
            .withCell(3, PlayerCell.SHIP)
            .withCell(2, PlayerCell.SHIP)
            .withCell(20, PlayerCell.SHIP)
            .withCell(33, PlayerCell.SHIP)
        val components = PuzzleValidator.shipComponents(base, grid).map { it.toList() }
        assertEquals(listOf(listOf(2, 3), listOf(20), listOf(33)), components)
    }

    @Test
    fun startGridFillsIslandsAndCluesOnly() {
        val def = base.copy(islands = listOf(4), givens = mapOf(0 to Given.SHIP, 1 to Given.WATER))
        val grid = def.startGrid()
        assertEquals(PlayerCell.WATER, grid[4])
        assertEquals(PlayerCell.SHIP, grid[0])
        assertEquals(PlayerCell.WATER, grid[1])
        assertEquals(PlayerCell.EMPTY, grid[2])
    }

    @Test
    fun withCellReturnsNewGridAndKeepsOriginal() {
        val original = base.startGrid()
        val changed = original.withCell(3, PlayerCell.SHIP)
        assertEquals(PlayerCell.EMPTY, original[3])
        assertEquals(PlayerCell.SHIP, changed[3])
    }

    @Test
    fun gridSizeMismatchIsRejected() {
        val small = gridOf(listOf("WWW", "WWW", "WWW"))
        try {
            validate(base, small)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
