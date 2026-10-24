package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demoGiven
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.layoutGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.onlyWater
import pt.tiago.nightfleet.core.random.SplitMix64

class PuzzleHintsTest {

    private val knownReasons = setOf(
        PuzzleReason.LINE_FULL,
        PuzzleReason.LINE_NEEDS_ALL,
        PuzzleReason.DIAGONAL_OF_SHIP,
        PuzzleReason.SIDE_OF_SHIP,
        PuzzleReason.GIVEN_SHAPE,
        PuzzleReason.FLEET_FIT,
        PuzzleReason.SHIP_CLOSED,
        PuzzleReason.LINE_FIT,
        PuzzleReason.CONTRADICTION,
    )

    /** Follows hints from the start until none is left, checking each one against the solution. */
    private fun replay(def: PuzzleDefinition, solution: PlayerGrid): Pair<List<Technique?>, PlayerGrid> {
        var grid = def.startGrid()
        val used = ArrayList<Technique?>()
        var guard = 0
        while (true) {
            val hint = nextHint(def, grid) ?: break
            assertFalse("a correct grid must not get wrong-mark hints", hint.reasonKey == PuzzleReason.WRONG_MARK)
            assertEquals(solution[hint.cell], hint.value)
            assertEquals(PlayerCell.EMPTY, grid[hint.cell])
            assertTrue(hint.reasonKey in knownReasons)
            used.add(hint.technique)
            grid = grid.withCell(hint.cell, hint.value)
            guard++
            assertTrue("hints must terminate", guard <= def.cellCount)
        }
        return used to grid
    }

    @Test
    fun unsolvableByLogicGivesNoHint() {
        assertNull(nextHint(demoGiven, demoGiven.startGrid()))
    }

    @Test
    fun firstHintOfOnlyWaterPuzzleIsCorrectAndTechniqueOne() {
        val start = onlyWater.startGrid()
        val hint = nextHint(onlyWater, start)
        assertNotNull(hint)
        assertEquals(layoutGrid()[hint!!.cell], hint.value)
        assertEquals(Technique.T1, hint.technique)
        assertEquals(PlayerCell.EMPTY, start[hint.cell])
    }

    @Test
    fun wrongMarkIsPointedOutBeforeAnyDeduction() {
        val grid = onlyWater.startGrid().withCell(0, PlayerCell.WATER)
        assertEquals(HintStep(0, PlayerCell.SHIP, null, PuzzleReason.WRONG_MARK), nextHint(onlyWater, grid))
    }

    @Test
    fun laterWrongMarkIsFoundWhenEarlierCellsAreCorrect() {
        val grid = onlyWater.startGrid()
            .withCell(0, PlayerCell.SHIP)   // correct
            .withCell(6, PlayerCell.SHIP)   // wrong: row 1 is water
        assertEquals(HintStep(6, PlayerCell.WATER, null, PuzzleReason.WRONG_MARK), nextHint(onlyWater, grid))
    }

    @Test
    fun completedCorrectGridGivesNoHint() {
        assertNull(nextHint(onlyWater, layoutGrid()))
    }

    @Test
    fun replayingHintsSolvesTheUniquePuzzle() {
        val (used, finalGrid) = replay(onlyWater, layoutGrid())
        assertTrue(used.isNotEmpty())
        assertTrue(PuzzleValidator.isSolved(onlyWater, finalGrid))
    }

    @Test
    fun replayingHintsOfAHardPuzzleReachesTheSolutionAndUsesT4() {
        val def = PuzzleGenerator.generate(6, PuzzleGrade.HARD, 1, SplitMix64(7L))
        assertNotNull(def)
        val solution = logicSolution(def!!)
        assertNotNull(solution)
        val (used, finalGrid) = replay(def, solution!!)
        assertTrue(used.contains(Technique.T4))
        assertTrue(PuzzleValidator.isSolved(def, finalGrid))
    }

    @Test
    fun hintsSolveAGeneratedMediumPuzzle() {
        val def = PuzzleGenerator.generate(8, PuzzleGrade.MEDIUM, 2, SplitMix64(99L))
        assertNotNull(def)
        val solution = ExactSolver.solve(def!!)
        assertNotNull(solution)
        val (_, finalGrid) = replay(def, solution!!)
        assertTrue(PuzzleValidator.isSolved(def, finalGrid))
    }
}
