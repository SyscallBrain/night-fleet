package pt.tiago.nightfleet.game.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.Given
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade

/** Reducer rules of the lighthouse puzzle, on a hand-made 6x6 board and on one generated puzzle. */
class PuzzleReducerTest {

    /**
     * 6x6 with an island at cell 0 and a SUB given at cell 5 (row 0, col 5), water given at cell 6 would
     * be row 1 col 0. Fleet 3,2,2,1,1,1 (10 squares), counts match the fleet.
     */
    private val manual = PuzzleDefinition(
        id = "manual-6",
        width = 6,
        height = 6,
        islands = listOf(0),
        fleet = listOf(3, 2, 2, 1, 1, 1),
        rowCounts = listOf(3, 2, 2, 1, 1, 1),
        colCounts = listOf(3, 2, 2, 1, 1, 1),
        givens = mapOf(5 to Given.SUB, 6 to Given.WATER),
    )

    private fun manualState(): PuzzleUiState = PuzzleReducer.initial(
        definition = manual,
        solution = PlayerGrid(6, 6, List(36) { PlayerCell.WATER }),
        grade = PuzzleGrade.EASY,
    )

    private fun reduce(state: PuzzleUiState, vararg actions: PuzzleAction): PuzzleUiState =
        actions.fold(state) { acc, action -> PuzzleReducer.reduce(acc, action) }

    // Generated puzzle shared by the tests that need a real solution and real hints.
    private val generated: PuzzleUiState by lazy {
        (1L..400L).firstNotNullOf { seed -> PuzzleFactory.create(6, PuzzleGrade.EASY, seed) }
    }

    private fun freeCell(state: PuzzleUiState): Int =
        (0 until state.definition.cellCount).first {
            !PuzzleReducer.isLocked(state.definition, it) && state.grid[it] == PlayerCell.EMPTY
        }

    /** Marks every free cell with the solution value: the board ends up solved. */
    private fun solveWithSolution(state: PuzzleUiState): PuzzleUiState {
        var s = state
        for (cell in 0 until state.definition.cellCount) {
            if (PuzzleReducer.isLocked(state.definition, cell)) continue
            s = PuzzleReducer.reduce(s, PuzzleAction.Mark(cell, state.solution[cell]))
        }
        return s
    }

    // --- cycling and locks -------------------------------------------------------------

    @Test
    fun tapCyclesEmptyWaterShipEmpty() {
        val s0 = manualState()
        val s1 = reduce(s0, PuzzleAction.Tap(7))
        assertEquals(PlayerCell.WATER, s1.grid[7])
        val s2 = reduce(s1, PuzzleAction.Tap(7))
        assertEquals(PlayerCell.SHIP, s2.grid[7])
        val s3 = reduce(s2, PuzzleAction.Tap(7))
        assertEquals(PlayerCell.EMPTY, s3.grid[7])
    }

    @Test
    fun tapOnIslandIsIgnored() {
        val s0 = manualState()
        assertEquals(s0, reduce(s0, PuzzleAction.Tap(0)))
    }

    @Test
    fun tapOnWaterGivenIsIgnored() {
        val s0 = manualState()
        assertEquals(PlayerCell.WATER, s0.grid[6])
        assertEquals(s0, reduce(s0, PuzzleAction.Tap(6)))
    }

    @Test
    fun givenShipCannotBeCleared() {
        val s0 = manualState()
        assertEquals(PlayerCell.SHIP, s0.grid[5])
        val s1 = reduce(s0, PuzzleAction.Tap(5), PuzzleAction.Mark(5, PlayerCell.EMPTY), PuzzleAction.LongPress(5))
        assertEquals(PlayerCell.SHIP, s1.grid[5])
        assertTrue(s1.history.isEmpty())
    }

    @Test
    fun lockedCellsAreTheIslandsAndTheGivens() {
        assertTrue(PuzzleReducer.isLocked(manual, 0))
        assertTrue(PuzzleReducer.isLocked(manual, 5))
        assertTrue(PuzzleReducer.isLocked(manual, 6))
        assertFalse(PuzzleReducer.isLocked(manual, 7))
    }

    @Test
    fun tapOutOfRangeIsIgnored() {
        val s0 = manualState()
        assertEquals(s0, reduce(s0, PuzzleAction.Tap(-1)))
        assertEquals(s0, reduce(s0, PuzzleAction.Tap(36)))
    }

    // --- long press, brush and accessibility marks -------------------------------------

    @Test
    fun longPressSetsWater() {
        val s1 = reduce(manualState(), PuzzleAction.LongPress(7))
        assertEquals(PlayerCell.WATER, s1.grid[7])
        assertEquals(1, s1.history.size)
    }

    @Test
    fun longPressOnWaterChangesNothing() {
        val s1 = reduce(manualState(), PuzzleAction.LongPress(7))
        assertEquals(s1, reduce(s1, PuzzleAction.LongPress(7)))
    }

    @Test
    fun longPressOverShipSetsWater() {
        val s1 = reduce(manualState(), PuzzleAction.Tap(7), PuzzleAction.Tap(7), PuzzleAction.LongPress(7))
        assertEquals(PlayerCell.WATER, s1.grid[7])
    }

    @Test
    fun paintWaterPaintsEveryCellAsOneUndoStep() {
        val s0 = manualState()
        val s1 = reduce(s0, PuzzleAction.PaintWater(listOf(7, 8, 9, 10)))
        assertEquals(listOf(7, 8, 9, 10).map { PlayerCell.WATER }, listOf(7, 8, 9, 10).map { s1.grid[it] })
        assertEquals(1, s1.history.size)
        val undone = reduce(s1, PuzzleAction.Undo)
        assertEquals(PlayerCell.EMPTY, undone.grid[8])
    }

    @Test
    fun paintWaterSkipsLockedAndWaterCells() {
        val s1 = reduce(manualState(), PuzzleAction.PaintWater(listOf(0, 5, 6, 7, 7)))
        assertEquals(PlayerCell.WATER, s1.grid[7])
        assertEquals(PlayerCell.SHIP, s1.grid[5])
        assertEquals(1, s1.history.size)
    }

    @Test
    fun paintWaterWithNothingToPaintChangesNothing() {
        val s1 = reduce(manualState(), PuzzleAction.PaintWater(listOf(0, 6)))
        assertTrue(s1.history.isEmpty())
        assertEquals(manualState().grid, s1.grid)
    }

    @Test
    fun markSetsTheValueAndClears() {
        val s1 = reduce(manualState(), PuzzleAction.Mark(7, PlayerCell.SHIP))
        assertEquals(PlayerCell.SHIP, s1.grid[7])
        val s2 = reduce(s1, PuzzleAction.Mark(7, PlayerCell.EMPTY))
        assertEquals(PlayerCell.EMPTY, s2.grid[7])
    }

    // --- undo, restart --------------------------------------------------------------

    @Test
    fun undoRestoresThePreviousGrid() {
        val s0 = manualState()
        val s2 = reduce(s0, PuzzleAction.Tap(7), PuzzleAction.Tap(8))
        val s3 = reduce(s2, PuzzleAction.Undo)
        assertEquals(PlayerCell.WATER, s3.grid[7])
        assertEquals(PlayerCell.EMPTY, s3.grid[8])
        assertEquals(1, s3.history.size)
    }

    @Test
    fun undoWithEmptyHistoryIsNoop() {
        val s0 = manualState()
        assertEquals(s0, reduce(s0, PuzzleAction.Undo))
    }

    @Test
    fun historyIsCappedAt200Steps() {
        var s = manualState()
        repeat(PUZZLE_HISTORY_LIMIT + 50) { i ->
            s = PuzzleReducer.reduce(s, PuzzleAction.Mark(7, if (i % 2 == 0) PlayerCell.WATER else PlayerCell.EMPTY))
        }
        assertEquals(PUZZLE_HISTORY_LIMIT, s.history.size)
    }

    @Test
    fun restartClearsMarksButKeepsCounters() {
        val s0 = manualState()
        val s1 = reduce(s0, PuzzleAction.Tap(7), PuzzleAction.Verify, PuzzleAction.Restart)
        assertEquals(PlayerCell.EMPTY, s1.grid[7])
        assertTrue(s1.history.isEmpty())
        assertTrue(s1.verifyUsed)
        assertEquals(PuzzleNotice.Restarted, s1.notice)
    }

    // --- fill water -------------------------------------------------------------------

    @Test
    fun fillWaterFillsTheFreeSquaresOfACompleteLine() {
        // Row 1 needs 2 ships: cells 7 and 8 make it complete, so 9, 10 and 11 become water.
        // Column 5 already has its one ship (the given at cell 5), so its free squares 17, 23, 29, 35 too.
        val s1 = reduce(
            manualState(),
            PuzzleAction.Mark(7, PlayerCell.SHIP),
            PuzzleAction.Mark(8, PlayerCell.SHIP),
            PuzzleAction.FillWater,
        )
        assertEquals(PlayerCell.WATER, s1.grid[9])
        assertEquals(PlayerCell.WATER, s1.grid[35])
        assertEquals(PlayerCell.EMPTY, s1.grid[13])
        assertEquals(PuzzleNotice.Filled(7), s1.notice)
    }

    @Test
    fun fillWaterIsCorrectForTheSolutionWhenLinesAreFull() {
        val s = generated
        // Fill every row with its solution ships: each complete row then gets its water filled.
        var state = s
        for (row in 0 until s.definition.height) {
            for (col in 0 until s.definition.width) {
                val cell = s.definition.cellAt(row, col)
                if (s.solution[cell] == PlayerCell.SHIP && !PuzzleReducer.isLocked(s.definition, cell) && state.grid[cell] != PlayerCell.SHIP) {
                    state = PuzzleReducer.reduce(state, PuzzleAction.Mark(cell, PlayerCell.SHIP))
                }
            }
        }
        val filled = PuzzleReducer.reduce(state, PuzzleAction.FillWater)
        // Every EMPTY cell that FillWater touched must be water in the solution.
        for (cell in 0 until s.definition.cellCount) {
            if (state.grid[cell] == PlayerCell.EMPTY && filled.grid[cell] == PlayerCell.WATER) {
                assertEquals(PlayerCell.WATER, s.solution[cell])
            }
        }
    }

    @Test
    fun fillWaterWithNoCompleteLineReportsNothingToFill() {
        // Column 5 starts complete (its given ship). One more ship there makes it over, row 2 is short: nothing fills.
        val s1 = reduce(manualState(), PuzzleAction.Mark(17, PlayerCell.SHIP), PuzzleAction.FillWater)
        assertEquals(PuzzleNotice.NothingToFill, s1.notice)
        assertEquals(1, s1.history.size)
    }

    @Test
    fun fillWaterIsNotAHint() {
        val s1 = reduce(
            manualState(),
            PuzzleAction.Mark(1, PlayerCell.SHIP),
            PuzzleAction.Mark(2, PlayerCell.SHIP),
            PuzzleAction.Mark(3, PlayerCell.SHIP),
            PuzzleAction.FillWater,
        )
        assertEquals(0, s1.hintsUsed)
        assertFalse(s1.verifyUsed)
        assertEquals(0, s1.aidsUsed)
    }

    // --- hints ------------------------------------------------------------------------

    @Test
    fun hintRevealsTheCellWithItsReasonAndCounts() {
        val s1 = reduce(generated, PuzzleAction.Hint)
        val hint = requireNotNull(s1.lastHint)
        assertEquals(hint.value, s1.grid[hint.cell])
        assertEquals(1, s1.hintsUsed)
        assertEquals(PuzzleNotice.Hint, s1.notice)
    }

    @Test
    fun hintLimitIsThreePerPuzzle() {
        val s3 = reduce(generated, PuzzleAction.Hint, PuzzleAction.Hint, PuzzleAction.Hint)
        assertFalse("the easy puzzle must not be solved by three hints", s3.solved)
        assertEquals(3, s3.hintsUsed)
        assertEquals(0, s3.hintsLeft)
        val s4 = reduce(s3, PuzzleAction.Hint)
        assertEquals(3, s4.hintsUsed)
        assertEquals(PuzzleNotice.NoHintsLeft, s4.notice)
    }

    @Test
    fun hintOnTheManualBoardStartsWithoutAHint() {
        assertNull(manualState().lastHint)
    }

    @Test
    fun hintsLeftCountsDown() {
        val s1 = reduce(generated, PuzzleAction.Hint)
        assertEquals(PUZZLE_MAX_HINTS - 1, s1.hintsLeft)
    }

    // --- verify -----------------------------------------------------------------------

    @Test
    fun verifyFlagsWrongCellsAgainstTheSolution() {
        val s = generated
        val cell = freeCell(s)
        val wrong = if (s.solution[cell] == PlayerCell.SHIP) PlayerCell.WATER else PlayerCell.SHIP
        val s1 = reduce(s, PuzzleAction.Mark(cell, wrong), PuzzleAction.Verify)
        assertEquals(setOf(cell), s1.errorCells)
        assertEquals(1, s1.errorsShown)
        assertTrue(s1.verifyUsed)
        assertEquals(PuzzleNotice.VerifyErrors(1), s1.notice)
    }

    @Test
    fun verifyOfCorrectMarksReportsClean() {
        val s = generated
        val cell = freeCell(s)
        val s1 = reduce(s, PuzzleAction.Mark(cell, s.solution[cell]), PuzzleAction.Verify)
        assertTrue(s1.errorCells.isEmpty())
        assertEquals(PuzzleNotice.VerifyClean, s1.notice)
    }

    @Test
    fun verifyCountsOnceTowardsTheStars() {
        val s = generated
        val s1 = reduce(s, PuzzleAction.Verify, PuzzleAction.Verify)
        assertTrue(s1.verifyUsed)
        assertEquals(1, s1.aidsUsed)
    }

    @Test
    fun editingAFlaggedCellClearsItsFlag() {
        val s = generated
        val cell = freeCell(s)
        val wrong = if (s.solution[cell] == PlayerCell.SHIP) PlayerCell.WATER else PlayerCell.SHIP
        val flagged = reduce(s, PuzzleAction.Mark(cell, wrong), PuzzleAction.Verify)
        val edited = reduce(flagged, PuzzleAction.Tap(cell))
        assertFalse(edited.errorCells.contains(cell))
    }

    // --- stars and solved detection -----------------------------------------------------

    @Test
    fun solvingWithoutAidsGivesThreeStars() {
        val solved = solveWithSolution(generated)
        assertTrue(solved.solved)
        assertEquals(3, solved.stars)
        assertEquals(PuzzleNotice.Solved, solved.notice)
    }

    @Test
    fun solvingWithOneAidGivesTwoStars() {
        val s = reduce(generated, PuzzleAction.Hint)
        val solved = solveWithSolution(s)
        assertTrue(solved.solved)
        assertEquals(2, solved.stars)
    }

    @Test
    fun solvingWithVerifyAndHintGivesOneStar() {
        val s = reduce(generated, PuzzleAction.Verify, PuzzleAction.Hint)
        val solved = solveWithSolution(s)
        assertTrue(solved.solved)
        assertEquals(1, solved.stars)
    }

    @Test
    fun starsForAidCounts() {
        assertEquals(3, PuzzleReducer.starsFor(0))
        assertEquals(2, PuzzleReducer.starsFor(1))
        assertEquals(1, PuzzleReducer.starsFor(2))
        assertEquals(1, PuzzleReducer.starsFor(4))
    }

    @Test
    fun solvedBoardIgnoresFurtherActions() {
        val solved = solveWithSolution(generated)
        val cell = (0 until solved.definition.cellCount).first { !PuzzleReducer.isLocked(solved.definition, it) }
        assertEquals(solved, reduce(solved, PuzzleAction.Tap(cell), PuzzleAction.Undo, PuzzleAction.Hint))
    }

    @Test
    fun solvedDetectionNeedsEveryLineToMatch() {
        // The generated puzzle with one solution square still empty is not solved.
        val s = generated
        val cells = (0 until s.definition.cellCount).filter { !PuzzleReducer.isLocked(s.definition, it) }
        var state = s
        for (cell in cells.drop(1)) state = PuzzleReducer.reduce(state, PuzzleAction.Mark(cell, s.solution[cell]))
        assertFalse(state.solved)
    }

    @Test
    fun tickUpdatesElapsedUntilSolved() {
        val s1 = reduce(manualState(), PuzzleAction.Tick(5_000))
        assertEquals(5_000, s1.elapsedMillis)
        val solved = solveWithSolution(generated.copy(elapsedMillis = 7_000))
        val frozen = reduce(solved, PuzzleAction.Tick(99_000))
        assertEquals(7_000, frozen.elapsedMillis)
    }

    // --- persistence and helpers ----------------------------------------------------------

    @Test
    fun snapshotRoundTripKeepsEveryField() {
        val s = reduce(generated, PuzzleAction.Hint, PuzzleAction.Verify, PuzzleAction.Tick(42_000))
        val restored = requireNotNull(PuzzleSnapshotCodec.decode(PuzzleSnapshotCodec.encode(s)))
        assertEquals(s.definition, restored.definition)
        assertEquals(s.solution, restored.solution)
        assertEquals(s.grid, restored.grid)
        assertEquals(s.grade, restored.grade)
        assertEquals(s.hintsUsed, restored.hintsUsed)
        assertEquals(s.verifyUsed, restored.verifyUsed)
        assertEquals(s.errorsShown, restored.errorsShown)
        assertEquals(42_000, restored.elapsedMillis)
    }

    @Test
    fun savedMatchRoundTripUsesTheEndlessKind() {
        val s = reduce(generated, PuzzleAction.Hint)
        val match = PuzzleSnapshotCodec.toSavedMatch(s, savedAtEpochMillis = 1_000)
        assertEquals(s.definition.id, match.matchId)
        assertEquals(pt.tiago.nightfleet.data.MatchKind.ENDLESS, match.kind)
        val restored = requireNotNull(PuzzleSnapshotCodec.fromSavedMatch(match))
        assertEquals(s.grid, restored.grid)
        assertEquals(s.hintsUsed, restored.hintsUsed)
    }

    @Test
    fun savedMatchOfAnotherKindIsRejected() {
        val match = PuzzleSnapshotCodec.toSavedMatch(generated, 0).copy(kind = pt.tiago.nightfleet.data.MatchKind.DAILY)
        assertNull(PuzzleSnapshotCodec.fromSavedMatch(match))
    }

    @Test
    fun corruptPayloadDecodesToNull() {
        assertNull(PuzzleSnapshotCodec.decode("{not json"))
    }

    @Test
    fun requestRoundTripsThroughItsText() {
        assertEquals(PuzzleRequest.Resume, PuzzleRequest.decode(PuzzleRequest.Resume.encode()))
        val new = PuzzleRequest.New(10, PuzzleGrade.HARD)
        assertEquals(new, PuzzleRequest.decode(new.encode()))
        assertNull(PuzzleRequest.decode("new:7:EASY"))
        assertNull(PuzzleRequest.decode("garbage"))
    }

    @Test
    fun recordSolvedCountsPerBoardSize() {
        val profile = pt.tiago.nightfleet.data.Profile()
        val once = PuzzleProgress.recordSolved(profile, 8)
        val twice = PuzzleProgress.recordSolved(once, 8)
        assertEquals(2, twice.endlessSolved[8])
        assertNull(twice.endlessSolved[6])
    }

    @Test
    fun fleetGroupsCountStraightShipsOnly() {
        // A straight 3-ship in row 2, a bent pair in row 4 and col 3 (not a ship), and a single.
        val cells = MutableList(36) { PlayerCell.EMPTY }
        for (c in 0..2) cells[2 * 6 + c] = PlayerCell.SHIP
        cells[4 * 6 + 0] = PlayerCell.SHIP
        cells[4 * 6 + 1] = PlayerCell.SHIP
        cells[5 * 6 + 1] = PlayerCell.SHIP
        cells[0 * 6 + 5] = PlayerCell.SHIP
        val grid = PlayerGrid(6, 6, cells)
        val groups = PuzzleFleet.groups(manual, grid).associateBy { it.length }
        assertEquals(1, groups.getValue(3).have)
        assertEquals(1, groups.getValue(3).need)
        assertTrue(groups.getValue(3).complete)
        assertEquals(0, groups.getValue(2).have)
        assertEquals(2, groups.getValue(2).need)
        assertEquals(1, groups.getValue(1).have)
    }

    @Test
    fun fleetGroupsAreOrderedLargestFirst() {
        val groups = PuzzleFleet.groups(manual, manual.startGrid())
        assertEquals(listOf(3, 2, 1), groups.map { it.length })
    }

    @Test
    fun starsAreNotCountedWhenUnsolved() {
        val s = reduce(generated, PuzzleAction.Hint, PuzzleAction.Hint)
        assertEquals(0, s.stars)
        assertFalse(s.solved)
    }

    @Test
    fun undoAfterSolveDoesNotReopenThePuzzle() {
        val solved = solveWithSolution(generated)
        assertTrue(reduce(solved, PuzzleAction.Undo).solved)
    }
}
