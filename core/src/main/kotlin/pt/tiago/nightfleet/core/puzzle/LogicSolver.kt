package pt.tiago.nightfleet.core.puzzle

/** Stable reason keys attached to every deduction. The app maps them to translated text. */
object PuzzleReason {
    const val LINE_FULL = "line_full"
    const val LINE_NEEDS_ALL = "line_needs_all"
    const val DIAGONAL_OF_SHIP = "diagonal_of_ship"
    const val SIDE_OF_SHIP = "side_of_ship"
    const val GIVEN_SHAPE = "given_shape"
    const val FLEET_FIT = "fleet_fit"
    const val SHIP_CLOSED = "ship_closed"
    const val LINE_FIT = "line_fit"
    const val CONTRADICTION = "contradiction"
    const val WRONG_MARK = "wrong_mark"
}

/** Outcome of [gradePuzzle]. */
sealed interface GradeResult {
    data class Graded(val grade: Grade) : GradeResult

    data object UnsolvableByLogic : GradeResult
}

/** One deduced cell, in the order it was deduced. */
internal data class Deduction(val cell: Int, val value: PlayerCell, val technique: Technique, val reasonKey: String)

internal const val CELL_UNKNOWN = 0
internal const val CELL_SHIP = 1
internal const val CELL_WATER = 2

/** Grades a puzzle with the human-style techniques T1 to T4. Sound: every deduction holds for the unique solution. */
fun gradePuzzle(definition: PuzzleDefinition): GradeResult {
    val board = LogicBoard.initial(definition)
    saturate(board, Technique.T4)
    val grid = board.toGrid()
    if (board.conflict || grid == null || !PuzzleValidator.isSolved(definition, grid)) {
        return GradeResult.UnsolvableByLogic
    }
    val maxTechnique = board.log.maxOfOrNull { it.technique } ?: Technique.T1
    return GradeResult.Graded(Grade(maxTechnique, board.moves))
}

/** The solution reached by the logical techniques, or null when logic alone does not solve the puzzle. */
internal fun logicSolution(definition: PuzzleDefinition): PlayerGrid? {
    val board = LogicBoard.initial(definition)
    saturate(board, Technique.T4)
    if (board.conflict || !board.isComplete()) return null
    val grid = board.toGrid() ?: return null
    return if (PuzzleValidator.isSolved(definition, grid)) grid else null
}

/** Precomputed geometry shared by all copies of a board. */
internal class Geometry(val def: PuzzleDefinition) {
    val w = def.width
    val h = def.height
    val n = w * h
    /** Rows first (index 0 until h), then columns. Each entry lists the cells of the line. */
    val lines: Array<IntArray> = Array(h + w) { i ->
        if (i < h) IntArray(w) { i * w + it } else IntArray(h) { it * w + (i - h) }
    }
    val lineTargets: IntArray = IntArray(h + w) { if (it < h) def.rowCounts[it] else def.colCounts[it - h] }
    /** Number of fleet ships of each length (index 1..4). */
    val fleetCount: IntArray = IntArray(5).also { counts -> for (len in def.fleet) counts[len]++ }
    val givens: List<Pair<Int, Given>> = def.givens.entries.sortedBy { it.key }.map { it.key to it.value }
}

/** Partially known grid used by the logical techniques. */
internal class LogicBoard private constructor(
    val geo: Geometry,
    val v: IntArray,
    val log: MutableList<Deduction>,
    var conflict: Boolean,
) {
    /** Number of technique applications that made progress: the human-style steps of the grade. */
    var moves = 0
    fun isComplete(): Boolean {
        for (x in v) if (x == CELL_UNKNOWN) return false
        return true
    }

    /** Writes a deduced value. Returns true when the cell changed; a clash sets [conflict]. */
    fun put(cell: Int, value: Int, technique: Technique, reason: String): Boolean {
        val current = v[cell]
        if (current == value) return false
        if (current != CELL_UNKNOWN) {
            conflict = true
            return false
        }
        v[cell] = value
        log.add(Deduction(cell, if (value == CELL_SHIP) PlayerCell.SHIP else PlayerCell.WATER, technique, reason))
        return true
    }

    fun copyForProbe(): LogicBoard = LogicBoard(geo, v.copyOf(), mutableListOf(), conflict)

    fun toGrid(): PlayerGrid? {
        if (!isComplete()) return null
        return PlayerGrid(geo.w, geo.h, v.map { if (it == CELL_SHIP) PlayerCell.SHIP else PlayerCell.WATER })
    }

    companion object {
        /** The puzzle before any player action: islands and clues only. */
        fun initial(definition: PuzzleDefinition): LogicBoard {
            val geo = Geometry(definition)
            val v = IntArray(geo.n)
            for (island in definition.islands) v[island] = CELL_WATER
            for ((cell, given) in geo.givens) v[cell] = if (given == Given.WATER) CELL_WATER else CELL_SHIP
            return LogicBoard(geo, v, mutableListOf(), false)
        }

        /** The puzzle with the player's current marks added as known facts. */
        fun fromGrid(definition: PuzzleDefinition, grid: PlayerGrid): LogicBoard {
            val board = initial(definition)
            for (cell in 0 until board.geo.n) {
                val wanted = when (grid[cell]) {
                    PlayerCell.SHIP -> CELL_SHIP
                    PlayerCell.WATER -> CELL_WATER
                    PlayerCell.EMPTY -> CELL_UNKNOWN
                }
                if (wanted == CELL_UNKNOWN) continue
                if (board.v[cell] == CELL_UNKNOWN) {
                    board.v[cell] = wanted
                } else if (board.v[cell] != wanted) {
                    board.conflict = true
                }
            }
            return board
        }
    }
}

/**
 * Applies the techniques up to [maxTech], cheapest first, until nothing changes. With
 * [stopAfterFirst], returns as soon as one deduction has been logged.
 */
internal fun saturate(board: LogicBoard, maxTech: Technique, stopAfterFirst: Boolean = false) {
    val start = board.log.size
    while (!board.conflict && !board.isComplete()) {
        if (stopAfterFirst && board.log.size > start) return
        val progressed = ruleLines(board) ||
            (maxTech >= Technique.T2 && ruleShape(board)) ||
            (maxTech >= Technique.T3 && ruleFleet(board)) ||
            (maxTech >= Technique.T4 && ruleProbe(board))
        if (!progressed) return
        if (!board.conflict) board.moves++
    }
}

/** T1: a full line is water; a line whose free cells equal the missing ships is all ship. */
internal fun ruleLines(b: LogicBoard): Boolean {
    var progressed = false
    val geo = b.geo
    for (li in geo.lines.indices) {
        val cells = geo.lines[li]
        val target = geo.lineTargets[li]
        var ships = 0
        var unknown = 0
        for (c in cells) {
            when (b.v[c]) {
                CELL_SHIP -> ships++
                CELL_UNKNOWN -> unknown++
            }
        }
        if (ships > target || ships + unknown < target) {
            b.conflict = true
            return true
        }
        if (unknown == 0) continue
        if (ships == target) {
            for (c in cells) {
                if (b.v[c] == CELL_UNKNOWN && b.put(c, CELL_WATER, Technique.T1, PuzzleReason.LINE_FULL)) progressed = true
            }
        } else if (ships + unknown == target) {
            for (c in cells) {
                if (b.v[c] == CELL_UNKNOWN && b.put(c, CELL_SHIP, Technique.T1, PuzzleReason.LINE_NEEDS_ALL)) progressed = true
            }
        }
        if (b.conflict) return true
    }
    return progressed
}

/** T2: diagonals of a ship are water, sides of a ship are water, and clues imply their neighbours. */
internal fun ruleShape(b: LogicBoard): Boolean {
    val geo = b.geo
    val def = geo.def
    val v = b.v
    var progressed = false
    for (cell in 0 until geo.n) {
        if (v[cell] != CELL_SHIP) continue
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 || dc == 0) continue
            val nb = def.offset(cell, dr, dc)
            if (nb >= 0 && b.put(nb, CELL_WATER, Technique.T2, PuzzleReason.DIAGONAL_OF_SHIP)) progressed = true
        }
        val north = def.neighbour(cell, Dir.NORTH)
        val south = def.neighbour(cell, Dir.SOUTH)
        val east = def.neighbour(cell, Dir.EAST)
        val west = def.neighbour(cell, Dir.WEST)
        val verticalShip = (north >= 0 && v[north] == CELL_SHIP) || (south >= 0 && v[south] == CELL_SHIP)
        val horizontalShip = (east >= 0 && v[east] == CELL_SHIP) || (west >= 0 && v[west] == CELL_SHIP)
        if (verticalShip) {
            if (east >= 0 && b.put(east, CELL_WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP)) progressed = true
            if (west >= 0 && b.put(west, CELL_WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP)) progressed = true
        }
        if (horizontalShip) {
            if (north >= 0 && b.put(north, CELL_WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP)) progressed = true
            if (south >= 0 && b.put(south, CELL_WATER, Technique.T2, PuzzleReason.SIDE_OF_SHIP)) progressed = true
        }
        if (b.conflict) return true
    }
    for ((cell, given) in geo.givens) {
        if (given == Given.SUB) {
            for (dir in 0..3) {
                val nb = def.neighbour(cell, dir)
                if (nb >= 0 && b.put(nb, CELL_WATER, Technique.T2, PuzzleReason.GIVEN_SHAPE)) progressed = true
            }
            continue
        }
        // Direction in which the ship continues from a clued end.
        val continuation = when (given) {
            Given.END_N -> Dir.SOUTH
            Given.END_E -> Dir.WEST
            Given.END_S -> Dir.NORTH
            Given.END_W -> Dir.EAST
            else -> -1
        }
        if (continuation < 0) continue
        val next = def.neighbour(cell, continuation)
        if (next < 0) {
            b.conflict = true
            return true
        }
        if (b.put(next, CELL_SHIP, Technique.T2, PuzzleReason.GIVEN_SHAPE)) progressed = true
        val outside = (continuation + 2) % 4
        val sides = if (continuation % 2 == 0) intArrayOf(Dir.EAST, Dir.WEST) else intArrayOf(Dir.NORTH, Dir.SOUTH)
        for (dir in sides + outside) {
            val nb = def.neighbour(cell, dir)
            if (nb >= 0 && b.put(nb, CELL_WATER, Technique.T2, PuzzleReason.GIVEN_SHAPE)) progressed = true
        }
    }
    return progressed
}

/**
 * T3, fleet accounting. Complete ships (closed at both ends) are removed from the fleet. Each
 * remaining ship must fit in a window of its length that avoids water and other ships: cells in no
 * window are water, and a line whose remaining ship cells can only come from one set of windows is
 * filled. An open end of a ship is closed when no longer ship remains.
 */
internal fun ruleFleet(b: LogicBoard): Boolean {
    val geo = b.geo
    val def = geo.def
    val v = b.v
    val n = geo.n
    val w = geo.w
    val h = geo.h
    var progressed = false

    val component = IntArray(n) { -1 }
    val complete = BooleanArray(n)
    val remaining = geo.fleetCount.copyOf()
    val incomplete = ArrayList<Pair<IntArray, IntArray>>()

    for (start in 0 until n) {
        if (v[start] != CELL_SHIP || component[start] >= 0) continue
        val cells = collectShipComponent(b, start, component)
        val step = straightStep(geo, cells)
        if (step < 0 || cells.size > 4) {
            b.conflict = true
            return true
        }
        val beyond = beyondCells(def, cells, step)
        if (beyond.none { v[it] == CELL_UNKNOWN }) {
            for (c in cells) complete[c] = true
            remaining[cells.size]--
            if (remaining[cells.size] < 0) {
                b.conflict = true
                return true
            }
        } else {
            incomplete.add(cells to beyond.filter { v[it] == CELL_UNKNOWN }.toIntArray())
        }
    }

    var maxRemaining = 0
    for (len in 4 downTo 1) {
        if (remaining[len] > 0) {
            maxRemaining = len
            break
        }
    }
    for ((cells, openEnds) in incomplete) {
        if (cells.size > maxRemaining) {
            b.conflict = true
            return true
        }
        if (cells.size == maxRemaining) {
            for (c in openEnds) if (b.put(c, CELL_WATER, Technique.T3, PuzzleReason.SHIP_CLOSED)) progressed = true
        }
    }
    if (b.conflict) return true

    val covered = BooleanArray(n)
    for (len in 1..4) {
        if (remaining[len] == 0) continue
        for (cell in 0 until n) {
            val r = cell / w
            val c = cell % w
            if (c + len <= w) markWindow(b, IntArray(len) { cell + it }, complete, covered)
            if (len > 1 && r + len <= h) markWindow(b, IntArray(len) { cell + it * w }, complete, covered)
        }
    }
    for (cell in 0 until n) {
        if (v[cell] == CELL_UNKNOWN && !covered[cell]) {
            if (b.put(cell, CELL_WATER, Technique.T3, PuzzleReason.FLEET_FIT)) progressed = true
        } else if (v[cell] == CELL_SHIP && !complete[cell] && !covered[cell]) {
            b.conflict = true
            return true
        }
    }

    for (li in geo.lines.indices) {
        val cells = geo.lines[li]
        var ships = 0
        var coveredUnknown = 0
        for (c in cells) {
            if (v[c] == CELL_SHIP) ships++
            if (v[c] == CELL_UNKNOWN && covered[c]) coveredUnknown++
        }
        val need = geo.lineTargets[li] - ships
        if (need < 0 || coveredUnknown < need) {
            b.conflict = true
            return true
        }
        if (need > 0 && coveredUnknown == need) {
            for (c in cells) {
                if (v[c] == CELL_UNKNOWN && covered[c] && b.put(c, CELL_SHIP, Technique.T3, PuzzleReason.LINE_FIT)) {
                    progressed = true
                }
            }
        }
    }
    return progressed
}

/** Marks the cells of a window as covered when the window could hold a ship of its length. */
private fun markWindow(b: LogicBoard, window: IntArray, complete: BooleanArray, covered: BooleanArray) {
    val v = b.v
    val def = b.geo.def
    for (c in window) {
        if (v[c] == CELL_WATER) return
        if (v[c] == CELL_SHIP && complete[c]) return
    }
    for (c in window) {
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nb = def.offset(c, dr, dc)
            if (nb >= 0 && v[nb] == CELL_SHIP && !window.contains(nb)) return
        }
    }
    for (c in window) covered[c] = true
}

/** Ship cells connected orthogonally to [start], sorted. Marks them in [component] with the start cell. */
private fun collectShipComponent(b: LogicBoard, start: Int, component: IntArray): IntArray {
    val def = b.geo.def
    val members = ArrayList<Int>()
    val stack = ArrayList<Int>()
    stack.add(start)
    component[start] = start
    while (stack.isNotEmpty()) {
        val cell = stack.removeAt(stack.size - 1)
        members.add(cell)
        for (dir in 0..3) {
            val nb = def.neighbour(cell, dir)
            if (nb >= 0 && b.v[nb] == CELL_SHIP && component[nb] < 0) {
                component[nb] = start
                stack.add(nb)
            }
        }
    }
    members.sort()
    return members.toIntArray()
}

/** 1 for a horizontal run, the width for a vertical one, 0 for a single cell, -1 when not straight. */
private fun straightStep(geo: Geometry, cells: IntArray): Int {
    if (cells.size == 1) return 0
    val d = cells[1] - cells[0]
    if (d != 1 && d != geo.w) return -1
    for (i in 1 until cells.size) if (cells[i] - cells[i - 1] != d) return -1
    if (d == 1 && cells.any { geo.def.row(it) != geo.def.row(cells[0]) }) return -1
    return d
}

/** On-board cells just beyond the ends of a run, or around a single cell. */
private fun beyondCells(def: PuzzleDefinition, cells: IntArray, step: Int): IntArray {
    val raw = when {
        cells.size == 1 -> listOf(
            def.neighbour(cells[0], Dir.NORTH),
            def.neighbour(cells[0], Dir.EAST),
            def.neighbour(cells[0], Dir.SOUTH),
            def.neighbour(cells[0], Dir.WEST),
        )
        step == 1 -> listOf(def.offset(cells.first(), 0, -1), def.offset(cells.last(), 0, 1))
        else -> listOf(def.offset(cells.first(), -1, 0), def.offset(cells.last(), 1, 0))
    }
    return raw.filter { it >= 0 }.toIntArray()
}

/**
 * T4, one-step contradiction: a cell whose ship assumption (or water assumption) leads to a
 * contradiction under T1 to T3 is set to the other value. Only the first such cell is applied.
 */
internal fun ruleProbe(b: LogicBoard): Boolean {
    for (cell in 0 until b.geo.n) {
        if (b.v[cell] != CELL_UNKNOWN) continue
        val shipOk = probeConsistent(b, cell, CELL_SHIP)
        val waterOk = probeConsistent(b, cell, CELL_WATER)
        if (!shipOk && !waterOk) {
            b.conflict = true
            return true
        }
        if (!shipOk) return b.put(cell, CELL_WATER, Technique.T4, PuzzleReason.CONTRADICTION)
        if (!waterOk) return b.put(cell, CELL_SHIP, Technique.T4, PuzzleReason.CONTRADICTION)
    }
    return false
}

private fun probeConsistent(b: LogicBoard, cell: Int, value: Int): Boolean {
    val probe = b.copyForProbe()
    probe.v[cell] = value
    saturate(probe, Technique.T3)
    return !probe.conflict
}
