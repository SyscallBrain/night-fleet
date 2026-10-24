package pt.tiago.nightfleet.game.puzzle

import pt.tiago.nightfleet.core.puzzle.HintStep
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.puzzle.PuzzleValidator
import pt.tiago.nightfleet.core.puzzle.nextHint

/** Hints a player may ask for in one puzzle. Verify is separate and counts once towards the stars. */
const val PUZZLE_MAX_HINTS = 3

/** Undo steps kept in memory. */
const val PUZZLE_HISTORY_LIMIT = 200

/** Everything the player can do on the puzzle screen. */
sealed interface PuzzleAction {
    /** Cycles EMPTY -> WATER -> SHIP -> EMPTY. */
    data class Tap(val cell: Int) : PuzzleAction

    /** Sets WATER directly. */
    data class LongPress(val cell: Int) : PuzzleAction

    /** A brush stroke: paints WATER over every listed cell as one undo step. */
    data class PaintWater(val cells: List<Int>) : PuzzleAction

    /** Sets one cell to [value] (EMPTY clears it). Used by the accessibility actions. */
    data class Mark(val cell: Int, val value: PlayerCell) : PuzzleAction

    data object Undo : PuzzleAction

    /** Fills EMPTY cells of every complete row and column with water. Free. */
    data object FillWater : PuzzleAction

    data object Hint : PuzzleAction

    /** Marks the wrong cells against the solution. Counts once towards the stars. */
    data object Verify : PuzzleAction

    /** Clears the marks but keeps the hint and verify counters. */
    data object Restart : PuzzleAction

    /** Clock update from the view model. Ignored once solved. */
    data class Tick(val elapsedMillis: Long) : PuzzleAction
}

/** Short feedback for the message line. `null` in the state means the standing instructions. */
sealed interface PuzzleNotice {
    data class Filled(val cells: Int) : PuzzleNotice
    data object NothingToFill : PuzzleNotice

    /** The reason of the last hint is in [PuzzleUiState.lastHint]. */
    data object Hint : PuzzleNotice
    data object NoHintsLeft : PuzzleNotice
    data object NoHintAvailable : PuzzleNotice
    data object VerifyClean : PuzzleNotice
    data class VerifyErrors(val count: Int) : PuzzleNotice
    data object Restarted : PuzzleNotice
    data object Solved : PuzzleNotice
}

/**
 * The state of one lighthouse puzzle. [solution] is the certified unique solution (SHIP and WATER only),
 * used by Verify. [stars] is 0 until the puzzle is solved.
 */
data class PuzzleUiState(
    val definition: PuzzleDefinition,
    val solution: PlayerGrid,
    val grade: PuzzleGrade,
    val grid: PlayerGrid,
    val history: List<PlayerGrid> = emptyList(),
    val hintsUsed: Int = 0,
    val verifyUsed: Boolean = false,
    /** Cumulative number of wrong cells flagged by Verify. */
    val errorsShown: Int = 0,
    /** Cells flagged by the last Verify and not changed since. */
    val errorCells: Set<Int> = emptySet(),
    val lastHint: HintStep? = null,
    val notice: PuzzleNotice? = null,
    val elapsedMillis: Long = 0,
    val solved: Boolean = false,
    val stars: Int = 0,
) {
    /** Hints and verify together: the stars are lost one by one. */
    val aidsUsed: Int get() = hintsUsed + if (verifyUsed) 1 else 0

    val hintsLeft: Int get() = (PUZZLE_MAX_HINTS - hintsUsed).coerceAtLeast(0)

    val canUndo: Boolean get() = history.isNotEmpty()
}

/**
 * Pure rules of the puzzle screen (no Android types, so it is tested on the JVM).
 * Givens and islands are locked. Once solved, the board ignores every action.
 */
object PuzzleReducer {

    fun initial(
        definition: PuzzleDefinition,
        solution: PlayerGrid,
        grade: PuzzleGrade,
        elapsedMillis: Long = 0,
    ): PuzzleUiState = PuzzleUiState(
        definition = definition,
        solution = solution,
        grade = grade,
        grid = definition.startGrid(),
        elapsedMillis = elapsedMillis,
    )

    /** Stars for a solved puzzle with [aids] hints and verify uses: none 3, one 2, more 1. */
    fun starsFor(aids: Int): Int = when {
        aids <= 0 -> 3
        aids == 1 -> 2
        else -> 1
    }

    /** Givens and islands cannot change. */
    fun isLocked(definition: PuzzleDefinition, cell: Int): Boolean =
        cell in definition.givens || definition.islands.binarySearch(cell) >= 0

    fun reduce(state: PuzzleUiState, action: PuzzleAction): PuzzleUiState {
        if (state.solved) return state
        return when (action) {
            is PuzzleAction.Tap -> tap(state, action.cell)
            is PuzzleAction.LongPress -> mark(state, action.cell, PlayerCell.WATER)
            is PuzzleAction.Mark -> mark(state, action.cell, action.value)
            is PuzzleAction.PaintWater -> paint(state, action.cells)
            PuzzleAction.Undo -> undo(state)
            PuzzleAction.FillWater -> fill(state)
            PuzzleAction.Hint -> hint(state)
            PuzzleAction.Verify -> verify(state)
            PuzzleAction.Restart -> restart(state)
            is PuzzleAction.Tick -> state.copy(elapsedMillis = action.elapsedMillis)
        }
    }

    private fun inRange(state: PuzzleUiState, cell: Int): Boolean = cell in 0 until state.definition.cellCount

    private fun tap(state: PuzzleUiState, cell: Int): PuzzleUiState {
        if (!inRange(state, cell) || isLocked(state.definition, cell)) return state
        val next = when (state.grid[cell]) {
            PlayerCell.EMPTY -> PlayerCell.WATER
            PlayerCell.WATER -> PlayerCell.SHIP
            PlayerCell.SHIP -> PlayerCell.EMPTY
        }
        return commit(state, state.grid.withCell(cell, next))
    }

    private fun mark(state: PuzzleUiState, cell: Int, value: PlayerCell): PuzzleUiState {
        if (!inRange(state, cell) || isLocked(state.definition, cell) || state.grid[cell] == value) return state
        return commit(state, state.grid.withCell(cell, value))
    }

    private fun paint(state: PuzzleUiState, cells: List<Int>): PuzzleUiState {
        val targets = cells.distinct().filter {
            inRange(state, it) && !isLocked(state.definition, it) && state.grid[it] != PlayerCell.WATER
        }
        if (targets.isEmpty()) return state
        var grid = state.grid
        for (cell in targets) grid = grid.withCell(cell, PlayerCell.WATER)
        return commit(state, grid)
    }

    private fun undo(state: PuzzleUiState): PuzzleUiState {
        if (state.history.isEmpty()) return state
        val previous = state.history.last()
        return state.copy(
            grid = previous,
            history = state.history.dropLast(1),
            errorCells = keepErrors(state, previous),
            lastHint = null,
            notice = null,
        )
    }

    private fun fill(state: PuzzleUiState): PuzzleUiState {
        val def = state.definition
        val grid = state.grid
        val cells = LinkedHashSet<Int>()
        for (row in 0 until def.height) {
            val line = (0 until def.width).map { def.cellAt(row, it) }
            if (shipsIn(grid, line) == def.rowCounts[row]) cells.addAll(line.filter { grid[it] == PlayerCell.EMPTY })
        }
        for (col in 0 until def.width) {
            val line = (0 until def.height).map { def.cellAt(it, col) }
            if (shipsIn(grid, line) == def.colCounts[col]) cells.addAll(line.filter { grid[it] == PlayerCell.EMPTY })
        }
        if (cells.isEmpty()) return state.copy(notice = PuzzleNotice.NothingToFill)
        var filled = grid
        for (cell in cells) filled = filled.withCell(cell, PlayerCell.WATER)
        return commit(state, filled, notice = PuzzleNotice.Filled(cells.size))
    }

    private fun hint(state: PuzzleUiState): PuzzleUiState {
        if (state.hintsUsed >= PUZZLE_MAX_HINTS) return state.copy(notice = PuzzleNotice.NoHintsLeft)
        val step = nextHint(state.definition, state.grid)
            ?: return state.copy(notice = PuzzleNotice.NoHintAvailable)
        return commit(
            state,
            state.grid.withCell(step.cell, step.value),
            notice = PuzzleNotice.Hint,
        ) { it.copy(hintsUsed = it.hintsUsed + 1, lastHint = step) }
    }

    private fun verify(state: PuzzleUiState): PuzzleUiState {
        val wrong = PuzzleValidator.errors(state.grid, state.solution)
        return state.copy(
            verifyUsed = true,
            errorCells = wrong.toSet(),
            errorsShown = state.errorsShown + wrong.size,
            lastHint = null,
            notice = if (wrong.isEmpty()) PuzzleNotice.VerifyClean else PuzzleNotice.VerifyErrors(wrong.size),
        )
    }

    private fun restart(state: PuzzleUiState): PuzzleUiState = state.copy(
        grid = state.definition.startGrid(),
        history = emptyList(),
        errorCells = emptySet(),
        lastHint = null,
        notice = PuzzleNotice.Restarted,
    )

    /** Records a new grid as one undo step, then checks for the solution. */
    private fun commit(
        state: PuzzleUiState,
        grid: PlayerGrid,
        notice: PuzzleNotice? = null,
        extra: (PuzzleUiState) -> PuzzleUiState = { it },
    ): PuzzleUiState {
        if (grid == state.grid) return state
        val base = state.copy(
            grid = grid,
            history = (state.history + state.grid).takeLast(PUZZLE_HISTORY_LIMIT),
            errorCells = keepErrors(state, grid),
            lastHint = null,
            notice = notice,
        )
        val next = extra(base)
        return if (PuzzleValidator.isSolved(next.definition, grid)) {
            next.copy(
                solved = true,
                stars = starsFor(next.aidsUsed),
                errorCells = emptySet(),
                lastHint = null,
                notice = PuzzleNotice.Solved,
            )
        } else {
            next
        }
    }

    /** Flags stay only on cells whose mark the player has not changed. */
    private fun keepErrors(state: PuzzleUiState, grid: PlayerGrid): Set<Int> =
        state.errorCells.filter { grid[it] == state.grid[it] }.toSet()

    private fun shipsIn(grid: PlayerGrid, cells: List<Int>): Int = cells.count { grid[it] == PlayerCell.SHIP }
}
