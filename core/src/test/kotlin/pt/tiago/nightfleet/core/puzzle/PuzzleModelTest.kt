package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.base
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demoGiven
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.gridOf
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.layoutGrid

class PuzzleModelTest {

    // ---- segment shapes -------------------------------------------------------------

    @Test fun noNeighboursIsSingle() = assertEquals(SegmentShape.SINGLE, shapeFromNeighbours(false, false, false, false))

    @Test fun onlySouthNeighbourIsNorthEnd() = assertEquals(SegmentShape.END_N, shapeFromNeighbours(false, false, true, false))

    @Test fun onlyWestNeighbourIsEastEnd() = assertEquals(SegmentShape.END_E, shapeFromNeighbours(false, false, false, true))

    @Test fun onlyNorthNeighbourIsSouthEnd() = assertEquals(SegmentShape.END_S, shapeFromNeighbours(true, false, false, false))

    @Test fun onlyEastNeighbourIsWestEnd() = assertEquals(SegmentShape.END_W, shapeFromNeighbours(false, true, false, false))

    @Test fun verticalPairIsMiddleV() = assertEquals(SegmentShape.MIDDLE_V, shapeFromNeighbours(true, false, true, false))

    @Test fun horizontalPairIsMiddleH() = assertEquals(SegmentShape.MIDDLE_H, shapeFromNeighbours(false, true, false, true))

    @Test fun cornerAndJunctionsAreUnresolved() {
        assertEquals(SegmentShape.UNRESOLVED, shapeFromNeighbours(true, true, false, false))
        assertEquals(SegmentShape.UNRESOLVED, shapeFromNeighbours(true, true, true, false))
        assertEquals(SegmentShape.UNRESOLVED, shapeFromNeighbours(true, true, true, true))
    }

    @Test fun layoutCellsGetTheirShapeFromNeighbours() {
        val grid = layoutGrid()
        assertEquals(SegmentShape.END_W, grid.segmentShape(0))
        assertEquals(SegmentShape.MIDDLE_H, grid.segmentShape(1))
        assertEquals(SegmentShape.END_E, grid.segmentShape(2))
        assertEquals(SegmentShape.SINGLE, grid.segmentShape(5))
        assertEquals(SegmentShape.SINGLE, grid.segmentShape(15))
        assertNull(grid.segmentShape(6))
    }

    @Test fun verticalShipGetsNorthMiddleSouthShapes() {
        val grid = gridOf(listOf("WWWWWW", "WWSWWW", "WWSWWW", "WWSWWW", "WWWWWW", "WWWWWW"))
        assertEquals(SegmentShape.END_N, grid.segmentShape(8))
        assertEquals(SegmentShape.MIDDLE_V, grid.segmentShape(14))
        assertEquals(SegmentShape.END_S, grid.segmentShape(20))
    }

    @Test fun cornerCellIsUnresolvedInGrid() {
        val grid = gridOf(listOf("WWWWWW", "WSWWWW", "WSSWWW", "WWWWWW", "WWWWWW", "WWWWWW"))
        // Cell 13 is (2, 1): ship to the north (7) and to the east (14), an L corner.
        assertEquals(SegmentShape.UNRESOLVED, grid.segmentShape(13))
    }

    @Test fun toGivenMapsEveryResolvedShape() {
        assertEquals(Given.SUB, SegmentShape.SINGLE.toGiven())
        assertEquals(Given.END_N, SegmentShape.END_N.toGiven())
        assertEquals(Given.MIDDLE, SegmentShape.MIDDLE_H.toGiven())
        assertEquals(Given.MIDDLE, SegmentShape.MIDDLE_V.toGiven())
        assertNull(SegmentShape.UNRESOLVED.toGiven())
    }

    // ---- construction ---------------------------------------------------------------

    @Test fun rejectsUnsupportedSize() = assertRejected { base.copy(width = 7, rowCounts = List(7) { 0 }, colCounts = List(6) { 0 }) }

    @Test fun rejectsFleetOutsideOneToFour() = assertRejected { base.copy(fleet = listOf(5, 1)) }

    @Test fun rejectsUnsortedFleet() = assertRejected { base.copy(fleet = listOf(1, 2, 2, 1, 1, 3)) }

    @Test fun rejectsCountsThatDoNotMatchFleet() = assertRejected { base.copy(rowCounts = listOf(4, 0, 3, 0, 2, 0)) }

    @Test fun rejectsGivenOnIsland() = assertRejected { base.copy(islands = listOf(3), givens = mapOf(3 to Given.SHIP)) }

    @Test fun rejectsUnsortedIslands() = assertRejected { base.copy(islands = listOf(5, 3)) }

    @Test fun rejectsGivenOutsideGrid() = assertRejected { base.copy(givens = mapOf(36 to Given.WATER)) }

    // ---- JSON -----------------------------------------------------------------------

    @Test fun jsonRoundTripKeepsEverything() {
        val text = PuzzleCodec.encode(demoGiven)
        assertEquals(demoGiven, PuzzleCodec.decode(text))
    }

    @Test fun jsonIsCanonicalWhateverTheGivenOrder() {
        val ascending = demoGiven.copy(givens = linkedMapOf(0 to Given.SHIP, 8 to Given.WATER, 23 to Given.SHIP, 28 to Given.WATER, 31 to Given.SHIP))
        val descending = demoGiven.copy(givens = linkedMapOf(31 to Given.SHIP, 28 to Given.WATER, 23 to Given.SHIP, 8 to Given.WATER, 0 to Given.SHIP))
        assertEquals(PuzzleCodec.encode(ascending), PuzzleCodec.encode(descending))
    }

    @Test fun jsonWritesGivensInCellOrder() {
        val text = PuzzleCodec.encode(demoGiven)
        assertTrue(text.indexOf("\"0\"") < text.indexOf("\"8\""))
        assertTrue(text.indexOf("\"8\"") < text.indexOf("\"23\""))
    }

    @Test fun jsonWritesDefaultsSoTheFormIsStable() {
        val text = PuzzleCodec.encode(base)
        assertTrue(text.contains("\"givens\":{}"))
    }

    @Test fun gradeDifficultyFollowsContentTable() {
        assertEquals(PuzzleGrade.EASY, Grade(Technique.T1, 3).difficulty())
        assertEquals(PuzzleGrade.EASY, Grade(Technique.T2, 40).difficulty())
        assertEquals(PuzzleGrade.MEDIUM, Grade(Technique.T3, HARD_T3_MIN_STEPS - 1).difficulty())
        assertEquals(PuzzleGrade.HARD, Grade(Technique.T3, HARD_T3_MIN_STEPS).difficulty())
        assertEquals(PuzzleGrade.HARD, Grade(Technique.T4, 2).difficulty())
    }

    @Test fun startGridOfDemoShowsClues() {
        val grid = demoGiven.startGrid()
        assertEquals(PlayerCell.SHIP, grid[0])
        assertEquals(PlayerCell.WATER, grid[8])
        assertEquals(PlayerCell.EMPTY, grid[1])
        assertFalse(grid.isComplete)
    }

    private fun assertRejected(build: () -> PuzzleDefinition) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotEmpty())
        }
    }
}
