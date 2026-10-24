package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.base
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demoGiven
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.layoutGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.onlyWater
import pt.tiago.nightfleet.core.random.SplitMix64

class LogicSolverTest {

    private val pairFleet = PuzzleDefinition(
        id = "pair",
        width = 6,
        height = 6,
        islands = emptyList(),
        fleet = listOf(2),
        rowCounts = listOf(2, 0, 0, 0, 0, 0),
        colCounts = listOf(1, 1, 0, 0, 0, 0),
    )

    private fun LogicBoard.has(cell: Int, value: PlayerCell, technique: Technique, reason: String): Boolean =
        log.any { it.cell == cell && it.value == value && it.technique == technique && it.reasonKey == reason }

    // ---- T1 ------------------------------------------------------------------------

    @Test
    fun fullLineMakesFreeCellsWater() {
        val def = base.copy(givens = mapOf(0 to Given.SHIP, 1 to Given.SHIP, 2 to Given.SHIP, 5 to Given.SHIP))
        val board = LogicBoard.initial(def)
        ruleLines(board)
        assertTrue(board.has(3, PlayerCell.WATER, Technique.T1, PuzzleReason.LINE_FULL))
        assertTrue(board.has(4, PlayerCell.WATER, Technique.T1, PuzzleReason.LINE_FULL))
    }

    @Test
    fun lineWithFreeCellsEqualToMissingShipsIsAllShip() {
        val def = base.copy(givens = mapOf(3 to Given.WATER, 4 to Given.WATER))
        val board = LogicBoard.initial(def)
        ruleLines(board)
        assertTrue(board.has(0, PlayerCell.SHIP, Technique.T1, PuzzleReason.LINE_NEEDS_ALL))
        assertTrue(board.has(5, PlayerCell.SHIP, Technique.T1, PuzzleReason.LINE_NEEDS_ALL))
    }

    @Test
    fun lineThatCannotHostItsShipsIsAContradiction() {
        val def = PuzzleDefinition(
            id = "too-few-cells",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(3, 2),
            rowCounts = listOf(5, 0, 0, 0, 0, 0),
            colCounts = listOf(1, 1, 1, 1, 1, 0),
            givens = mapOf(0 to Given.WATER, 1 to Given.WATER),
        )
        val board = LogicBoard.initial(def)
        ruleLines(board)
        assertTrue(board.conflict)
        assertEquals(UNSOLVABLE, gradePuzzle(def))
    }

    @Test
    fun t1AloneSolvesAPuzzleGivenAllWater() {
        val board = LogicBoard.initial(onlyWater)
        saturate(board, Technique.T1)
        assertTrue(board.isComplete())
        assertEquals(layoutGrid(), board.toGrid())
    }

    // ---- T2 ------------------------------------------------------------------------

    @Test
    fun diagonalsOfAShipAreWater() {
        val board = LogicBoard.initial(demoGiven)
        ruleShape(board)
        assertTrue(board.has(7, PlayerCell.WATER, Technique.T2, PuzzleReason.DIAGONAL_OF_SHIP))
    }

    @Test
    fun sidesOfAHorizontalPairAreWater() {
        val def = base.copy(givens = mapOf(14 to Given.SHIP, 15 to Given.SHIP))
        val board = LogicBoard.initial(def)
        ruleShape(board)
        assertTrue(board.has(8, PlayerCell.WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP))
        assertTrue(board.has(20, PlayerCell.WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP))
    }

    @Test
    fun northEndClueImpliesShipSouthAndWaterAround() {
        val def = base.copy(givens = mapOf(14 to Given.END_N))
        val board = LogicBoard.initial(def)
        ruleShape(board)
        assertTrue(board.has(20, PlayerCell.SHIP, Technique.T2, PuzzleReason.GIVEN_SHAPE))
        assertTrue(board.has(8, PlayerCell.WATER, Technique.T2, PuzzleReason.GIVEN_SHAPE))
        assertTrue(board.has(13, PlayerCell.WATER, Technique.T2, PuzzleReason.GIVEN_SHAPE))
        assertTrue(board.has(15, PlayerCell.WATER, Technique.T2, PuzzleReason.GIVEN_SHAPE))
    }

    @Test
    fun subClueSurroundedByWater() {
        val def = base.copy(givens = mapOf(14 to Given.SUB))
        val board = LogicBoard.initial(def)
        ruleShape(board)
        for (cell in listOf(8, 13, 15, 20)) assertEquals(CELL_WATER, board.v[cell])
    }

    @Test
    fun endClueOnTheBottomRowIsContradictory() {
        val board = LogicBoard.initial(base.copy(givens = mapOf(30 to Given.END_N)))
        ruleShape(board)
        assertTrue(board.conflict)
    }

    // ---- T3 ------------------------------------------------------------------------

    @Test
    fun completedShipClosesItsOpenEndWithWater() {
        val def = pairFleet.copy(givens = mapOf(0 to Given.SHIP, 1 to Given.SHIP))
        val board = LogicBoard.initial(def)
        ruleFleet(board)
        assertTrue(board.has(2, PlayerCell.WATER, Technique.T3, PuzzleReason.SHIP_CLOSED))
    }

    @Test
    fun cellThatCannotHostAnyRemainingShipIsWater() {
        val def = pairFleet.copy(givens = mapOf(34 to Given.WATER, 29 to Given.WATER))
        val board = LogicBoard.initial(def)
        ruleFleet(board)
        assertTrue(board.has(35, PlayerCell.WATER, Technique.T3, PuzzleReason.FLEET_FIT))
    }

    @Test
    fun lineWhoseRemainingShipsOnlyFitOneWayIsFilled() {
        val def = PuzzleDefinition(
            id = "line-fit",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(3),
            rowCounts = listOf(3, 0, 0, 0, 0, 0),
            colCounts = listOf(1, 1, 1, 0, 0, 0),
            givens = mapOf(3 to Given.WATER, 4 to Given.WATER, 5 to Given.WATER),
        )
        val board = LogicBoard.initial(def)
        ruleFleet(board)
        assertTrue(board.has(0, PlayerCell.SHIP, Technique.T3, PuzzleReason.LINE_FIT))
        assertTrue(board.has(2, PlayerCell.SHIP, Technique.T3, PuzzleReason.LINE_FIT))
    }

    @Test
    fun singleShipWithoutSingleShipsInFleetIsContradictory() {
        val def = pairFleet.copy(givens = mapOf(0 to Given.SHIP, 1 to Given.WATER, 6 to Given.WATER))
        val board = LogicBoard.initial(def)
        ruleFleet(board)
        assertTrue(board.conflict)
    }

    // ---- T4 ------------------------------------------------------------------------

    @Test
    fun hardPuzzleNeedsContradictionProbing() {
        val def = hardSample()
        val board = LogicBoard.initial(def)
        saturate(board, Technique.T3)
        assertFalse(board.isComplete())
        assertTrue(ruleProbe(board))
        assertTrue(board.log.last().reasonKey == PuzzleReason.CONTRADICTION)
        assertEquals(Technique.T4, board.log.last().technique)
    }

    @Test
    fun gradeOfHardSampleIsT4() {
        val result = gradePuzzle(hardSample()) as GradeResult.Graded
        assertEquals(Technique.T4, result.grade.maxTechnique)
    }

    // ---- grader --------------------------------------------------------------------

    @Test
    fun onlyWaterPuzzleGradesAsT1() {
        val result = gradePuzzle(onlyWater) as GradeResult.Graded
        assertEquals(Technique.T1, result.grade.maxTechnique)
        assertTrue(result.grade.steps > 0)
    }

    @Test
    fun fullyCluedPuzzleNeedsNoSteps() {
        val clues = (0 until 36).associateWith { if (it in PuzzleFixtures.layoutShips) Given.SHIP else Given.WATER }
        assertEquals(GradeResult.Graded(Grade(Technique.T1, 0)), gradePuzzle(base.copy(givens = clues)))
    }

    @Test
    fun puzzleWithSeveralSolutionsIsUnsolvableByLogic() {
        assertEquals(UNSOLVABLE, gradePuzzle(demoGiven))
        assertEquals(null, logicSolution(demoGiven))
    }

    @Test
    fun logicSolutionMatchesTheUniqueSolution() {
        assertEquals(layoutGrid(), logicSolution(onlyWater))
    }

    @Test
    fun gradeOfAnUnsolvableGridIsNotAGrade() {
        assertFalse(gradePuzzle(demoGiven) is GradeResult.Graded)
    }

    private fun hardSample(): PuzzleDefinition {
        val def = PuzzleGenerator.generate(6, PuzzleGrade.HARD, 1, SplitMix64(7L))
        assertNotNull(def)
        return def!!
    }

    private val UNSOLVABLE: GradeResult = GradeResult.UnsolvableByLogic
}
