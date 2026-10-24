package pt.tiago.nightfleet.core.puzzle

import pt.tiago.nightfleet.core.random.SplitMix64
import java.util.TreeMap

/**
 * Builds lighthouse puzzles from a seed. The same seed and parameters always give the same puzzle.
 * Uniqueness is proved by the logical grader: a puzzle is accepted only when T1 to T4 solve it,
 * which implies a single solution.
 */
object PuzzleGenerator {
    private const val MAX_ATTEMPTS = 60

    fun fleetFor(size: Int): List<Int> = when (size) {
        6 -> listOf(3, 2, 2, 1, 1, 1)
        8 -> listOf(4, 3, 2, 2, 1, 1, 1)
        10 -> listOf(4, 3, 3, 2, 2, 2, 1, 1, 1, 1)
        else -> throw IllegalArgumentException("Puzzle side must be 6, 8 or 10, was $size")
    }

    /**
     * Generates a puzzle of [size] x [size] with [islandsCount] islands whose grade is [targetGrade],
     * or null when no attempt succeeds (the caller may then pick another seed).
     */
    fun generate(size: Int, targetGrade: PuzzleGrade, islandsCount: Int, rng: SplitMix64): PuzzleDefinition? {
        val fleet = fleetFor(size)
        require(islandsCount in 0..(size * size) / 4) { "Too many islands for a $size x $size grid" }
        val tag = rng.stateHex()
        for (attempt in 0 until MAX_ATTEMPTS) {
            val id = "enigma-$size-${targetGrade.name.lowercase()}-$tag-$attempt"
            val definition = attemptOnce(size, fleet, targetGrade, islandsCount, rng, id)
            if (definition != null) return definition
        }
        return null
    }

    private fun attemptOnce(
        size: Int,
        fleet: List<Int>,
        target: PuzzleGrade,
        islandsCount: Int,
        rng: SplitMix64,
        id: String,
    ): PuzzleDefinition? {
        val n = size * size

        // Islands: a random subset of cells that never hold ships.
        val islandFlags = BooleanArray(n)
        val order = IntArray(n) { it }
        for (i in 0 until islandsCount) {
            val j = i + rng.nextInt(n - i)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
            islandFlags[order[i]] = true
        }

        // Fleet: each ship is a uniformly chosen legal placement, largest first.
        val shipFlags = BooleanArray(n)
        val blocked = islandFlags.copyOf()
        for (len in fleet) {
            val options = ArrayList<IntArray>()
            for (cell in 0 until n) {
                for (horizontal in booleanArrayOf(true, false)) {
                    if (len == 1 && !horizontal) continue
                    val cells = placementCells(size, cell, len, horizontal) ?: continue
                    if (cells.any { blocked[it] }) continue
                    options.add(cells)
                }
            }
            if (options.isEmpty()) return null
            val chosen = options[rng.nextInt(options.size)]
            for (c in chosen) shipFlags[c] = true
            for (c in chosen) {
                for (dr in -1..1) for (dc in -1..1) {
                    val r = c / size + dr
                    val col = c % size + dc
                    if (r in 0 until size && col in 0 until size) blocked[r * size + col] = true
                }
            }
        }

        val rows = IntArray(size)
        val cols = IntArray(size)
        for (cell in 0 until n) {
            if (shipFlags[cell]) {
                rows[cell / size]++
                cols[cell % size]++
            }
        }
        val islands = (0 until n).filter { islandFlags[it] }
        val givens = TreeMap<Int, Given>()
        fun build(map: Map<Int, Given>) = PuzzleDefinition(
            id = id,
            width = size,
            height = size,
            islands = islands,
            fleet = fleet,
            rowCounts = rows.toList(),
            colCounts = cols.toList(),
            givens = TreeMap(map),
        )

        // Add clues at random unknown cells until the logic allowed for this grade solves the puzzle.
        val allowed = if (target == PuzzleGrade.EASY) Technique.T2 else Technique.T3
        while (true) {
            val board = LogicBoard.initial(build(givens))
            saturate(board, allowed)
            if (board.conflict) return null
            if (board.isComplete()) break
            val unknown = (0 until n).filter { board.v[it] == CELL_UNKNOWN }
            val cell = unknown[rng.nextInt(unknown.size)]
            givens[cell] = randomClue(size, shipFlags, cell, rng)
        }

        // HARD: remove clues that are not needed, keeping the puzzle solvable with T4.
        if (target == PuzzleGrade.HARD) {
            val keys = givens.keys.toMutableList()
            shuffle(keys, rng)
            for (key in keys) {
                val clue = givens.remove(key) ?: continue
                val board = LogicBoard.initial(build(givens))
                saturate(board, Technique.T4)
                if (board.conflict || !board.isComplete()) givens[key] = clue
            }
        }

        val definition = build(givens)
        val result = gradePuzzle(definition)
        if (result !is GradeResult.Graded || result.grade.difficulty() != target) return null
        // Independent guard: the exact solver must prove a single solution within its budget.
        return if (ExactSolver.countSolutions(definition).isUnique) definition else null
    }

    /** Cells of a legal placement anchored at [cell] (left end or top end), or null when it leaves the grid. */
    private fun placementCells(size: Int, cell: Int, len: Int, horizontal: Boolean): IntArray? {
        val r = cell / size
        val c = cell % size
        if (horizontal && c + len > size) return null
        if (!horizontal && r + len > size) return null
        return IntArray(len) { if (horizontal) cell + it else cell + it * size }
    }

    /** A clue that is true for the solution: water for water cells, otherwise the ship shape or the plain ship clue. */
    private fun randomClue(size: Int, shipFlags: BooleanArray, cell: Int, rng: SplitMix64): Given {
        if (!shipFlags[cell]) return Given.WATER
        val north = cell >= size && shipFlags[cell - size]
        val south = cell + size < size * size && shipFlags[cell + size]
        val west = cell % size > 0 && shipFlags[cell - 1]
        val east = cell % size < size - 1 && shipFlags[cell + 1]
        val shape = shapeFromNeighbours(north, east, south, west).toGiven() ?: return Given.SHIP
        return if (rng.nextInt(2) == 0) Given.SHIP else shape
    }

    private fun <T> shuffle(items: MutableList<T>, rng: SplitMix64) {
        for (i in items.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = items[i]
            items[i] = items[j]
            items[j] = tmp
        }
    }
}
