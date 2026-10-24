package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.base
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demo
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demoGiven
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.layoutGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.onlyWater

class ExactSolverTest {

    /**
     * Independent oracle: enumerate every set of straight placements that matches the fleet, and keep
     * the sets that the validator accepts. Solutions are deduplicated as sets of ship cells.
     */
    private fun bruteForceSolutions(def: PuzzleDefinition): Set<Set<Int>> {
        val w = def.width
        val h = def.height
        val placements = ArrayList<List<Int>>()
        for (len in def.fleet.distinct()) {
            for (r in 0 until h) for (c in 0 until w) {
                if (c + len <= w) placements.add((0 until len).map { r * w + c + it })
                if (r + len <= h && len > 1) placements.add((0 until len).map { (r + it) * w + c })
            }
        }
        val solutions = HashSet<Set<Int>>()
        fun touches(p: List<Int>, cells: Set<Int>): Boolean = p.any { a ->
            cells.any { b -> kotlin.math.abs(a / w - b / w) <= 1 && kotlin.math.abs(a % w - b % w) <= 1 }
        }
        // `from` orders identical ships so that each set of ships is generated once.
        fun recurse(fleetIndex: Int, from: Int, cells: MutableSet<Int>) {
            if (fleetIndex == def.fleet.size) {
                val grid = PlayerGrid(w, h, (0 until w * h).map { if (it in cells) PlayerCell.SHIP else PlayerCell.WATER })
                if (PuzzleValidator.isSolved(def, grid)) solutions.add(cells.toSet())
                return
            }
            val len = def.fleet[fleetIndex]
            val first = if (fleetIndex > 0 && def.fleet[fleetIndex - 1] == len) from else 0
            for (i in first until placements.size) {
                val p = placements[i]
                if (p.size != len) continue
                if (p.any { it in cells || it in def.islands }) continue
                if (touches(p, cells)) continue
                cells.addAll(p)
                recurse(fleetIndex + 1, i + 1, cells)
                cells.removeAll(p.toSet())
            }
        }
        recurse(0, 0, HashSet())
        return solutions
    }

    @Test
    fun demoHasFourSolutionsWithoutClues() {
        assertEquals(CountResult(4, false), ExactSolver.countSolutions(demo, limit = 1000))
    }

    @Test
    fun demoWithItsFiveCluesHasTwoSolutions() {
        assertEquals(CountResult(2, false), ExactSolver.countSolutions(demoGiven, limit = 1000))
    }

    @Test
    fun demoCountsAgreeWithBruteForceOracle() {
        assertEquals(bruteForceSolutions(demo).size, ExactSolver.countSolutions(demo, limit = 1000).count)
        assertEquals(bruteForceSolutions(demoGiven).size, ExactSolver.countSolutions(demoGiven, limit = 1000).count)
    }

    @Test
    fun layoutPuzzleHasFifteenSolutionsAndOracleAgrees() {
        assertEquals(15, ExactSolver.countSolutions(base, limit = 1000).count)
        assertEquals(15, bruteForceSolutions(base).size)
    }

    @Test
    fun limitStopsTheCount() {
        assertEquals(1, ExactSolver.countSolutions(base, limit = 1).count)
        assertEquals(2, ExactSolver.countSolutions(demo, limit = 2).count)
    }

    @Test
    fun uniquePuzzleReturnsItsSolution() {
        val result = ExactSolver.countSolutions(onlyWater)
        assertTrue(result.isUnique)
        assertEquals(layoutGrid(), ExactSolver.solve(onlyWater))
    }

    @Test
    fun solveReturnsNullWhenSeveralSolutionsExist() {
        assertNull(ExactSolver.solve(demoGiven))
    }

    @Test
    fun twoIdenticalSingleShipsAreCountedOnce() {
        // Two single ships at (0,0) and (5,5): the two ships are interchangeable, so one solution.
        val twoSingles = PuzzleDefinition(
            id = "singles",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(1, 1),
            rowCounts = listOf(1, 0, 0, 0, 0, 1),
            colCounts = listOf(1, 0, 0, 0, 0, 1),
        )
        // Diagonal placements (0,0)-(5,5) and (0,5)-(5,0) are the two solutions, not four.
        assertEquals(2, ExactSolver.countSolutions(twoSingles, limit = 1000).count)
        assertEquals(bruteForceSolutions(twoSingles).size, 2)
    }

    @Test
    fun impossibleCountsHaveNoSolution() {
        val impossible = PuzzleDefinition(
            id = "impossible",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(2),
            rowCounts = listOf(1, 1, 0, 0, 0, 0),
            colCounts = listOf(1, 1, 0, 0, 0, 0),
        )
        assertEquals(0, ExactSolver.countSolutions(impossible).count)
        assertNull(ExactSolver.solve(impossible))
    }

    @Test
    fun contradictoryClueHasNoSolution() {
        val horizontal = PuzzleDefinition(
            id = "horizontal",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(2),
            rowCounts = listOf(2, 0, 0, 0, 0, 0),
            colCounts = listOf(1, 1, 0, 0, 0, 0),
        )
        assertEquals(1, ExactSolver.countSolutions(horizontal).count)
        // An END_N clue on the bottom row cannot continue south.
        val clued = horizontal.copy(givens = mapOf(30 to Given.END_N))
        assertEquals(0, ExactSolver.countSolutions(clued).count)
    }

    @Test
    fun islandRemovesTheOnlyPlacement() {
        val single = PuzzleDefinition(
            id = "single",
            width = 6,
            height = 6,
            islands = emptyList(),
            fleet = listOf(1),
            rowCounts = listOf(1, 0, 0, 0, 0, 0),
            colCounts = listOf(0, 0, 0, 0, 0, 1),
        )
        assertEquals(1, ExactSolver.countSolutions(single).count)
        assertEquals(0, ExactSolver.countSolutions(single.copy(islands = listOf(5))).count)
    }

    @Test
    fun nodeBudgetIsReportedAsExceeded() {
        val result = ExactSolver.countSolutions(demo, limit = 2, nodeBudget = 5)
        assertTrue(result.budgetExceeded)
        assertFalse(result.isUnique)
    }

    @Test
    fun defaultBudgetSolvesDemoWithoutExceeding() {
        assertFalse(ExactSolver.countSolutions(demoGiven).budgetExceeded)
    }
}
