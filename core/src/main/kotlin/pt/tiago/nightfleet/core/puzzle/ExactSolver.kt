package pt.tiago.nightfleet.core.puzzle

/**
 * Result of [ExactSolver.countSolutions]. [count] is capped at the requested limit. When
 * [budgetExceeded] is true the count is not reliable and the puzzle must be rejected.
 */
data class CountResult(val count: Int, val budgetExceeded: Boolean) {
    val isUnique: Boolean get() = !budgetExceeded && count == 1
}

/** Exact backtracking solver: ships are placed largest first, with row and column pruning. */
object ExactSolver {
    const val DEFAULT_NODE_BUDGET: Long = 5_000_000L

    fun countSolutions(
        definition: PuzzleDefinition,
        limit: Int = 2,
        nodeBudget: Long = DEFAULT_NODE_BUDGET,
    ): CountResult {
        require(limit >= 1) { "Limit must be at least 1" }
        val search = ExactSearch(definition, limit, nodeBudget, collect = false)
        search.run()
        return CountResult(search.count, search.aborted)
    }

    /** The unique solution (SHIP and WATER only), or null when there is none, several, or the budget ran out. */
    fun solve(definition: PuzzleDefinition): PlayerGrid? {
        val search = ExactSearch(definition, 2, DEFAULT_NODE_BUDGET, collect = true)
        search.run()
        return if (!search.aborted && search.count == 1) search.firstSolution else null
    }
}

private class ExactSearch(
    private val def: PuzzleDefinition,
    private val limit: Int,
    private val budget: Long,
    private val collect: Boolean,
) {
    private class Placement(val cells: IntArray, val closed: IntArray, val horizontal: Boolean)

    private val w = def.width
    private val h = def.height
    private val n = def.cellCount
    private val fleet = def.fleet
    private val blocked = BooleanArray(n)
    private val isShip = BooleanArray(n)
    private val required = BooleanArray(n)
    private val givenAt = arrayOfNulls<Given>(n)
    private val rowRem = IntArray(h) { def.rowCounts[it] }
    private val colRem = IntArray(w) { def.colCounts[it] }
    private val freeRow = IntArray(h) { w }
    private val freeCol = IntArray(w) { h }
    private var requiredLeft = 0
    private var nodes = 0L
    private val byLength: Array<List<Placement>> = Array(5) { emptyList() }

    var count = 0
        private set
    var aborted = false
        private set
    var firstSolution: PlayerGrid? = null
        private set

    init {
        for (island in def.islands) block(island)
        for ((cell, given) in def.givens) {
            givenAt[cell] = given
            if (given == Given.WATER) {
                block(cell)
            } else {
                required[cell] = true
                requiredLeft++
            }
        }
        for (len in fleet.distinct()) byLength[len] = buildPlacements(len)
    }

    fun run() {
        for (cell in 0 until n) if (required[cell] && blocked[cell]) return
        for (r in 0 until h) if (rowRem[r] > freeRow[r]) return
        for (c in 0 until w) if (colRem[c] > freeCol[c]) return
        search(0, -1)
    }

    private fun block(cell: Int) {
        if (blocked[cell]) return
        blocked[cell] = true
        freeRow[cell / w]--
        freeCol[cell % w]--
    }

    private fun unblock(cell: Int) {
        blocked[cell] = false
        freeRow[cell / w]++
        freeCol[cell % w]++
    }

    private fun buildPlacements(len: Int): List<Placement> {
        val list = ArrayList<Placement>()
        for (cell in 0 until n) {
            val r = cell / w
            val c = cell % w
            for (horizontal in booleanArrayOf(true, false)) {
                if (len == 1 && !horizontal) continue
                if (horizontal && c + len > w) continue
                if (!horizontal && r + len > h) continue
                val cells = IntArray(len) { if (horizontal) cell + it else cell + it * w }
                if (cells.any { blocked[it] }) continue
                if (!givensAllow(cells, len, horizontal)) continue
                list.add(Placement(cells, closedNeighbourhood(cells), horizontal))
            }
        }
        return list
    }

    private fun givensAllow(cells: IntArray, len: Int, horizontal: Boolean): Boolean {
        for (k in cells.indices) {
            val given = givenAt[cells[k]] ?: continue
            if (!compatible(given, len, horizontal, k)) return false
        }
        return true
    }

    private fun compatible(given: Given, len: Int, horizontal: Boolean, k: Int): Boolean = when (given) {
        Given.WATER -> false
        Given.SHIP -> true
        Given.SUB -> len == 1
        Given.MIDDLE -> len >= 3 && k in 1 until len - 1
        Given.END_N -> !horizontal && len >= 2 && k == 0
        Given.END_S -> !horizontal && len >= 2 && k == len - 1
        Given.END_W -> horizontal && len >= 2 && k == 0
        Given.END_E -> horizontal && len >= 2 && k == len - 1
    }

    private fun closedNeighbourhood(cells: IntArray): IntArray {
        val mark = BooleanArray(n)
        for (cell in cells) {
            for (dr in -1..1) for (dc in -1..1) {
                val nb = def.offset(cell, dr, dc)
                if (nb >= 0) mark[nb] = true
            }
            mark[cell] = true
        }
        return (0 until n).filter { mark[it] }.toIntArray()
    }

    private fun search(index: Int, previous: Int) {
        if (count >= limit || aborted) return
        if (index == fleet.size) {
            if (requiredLeft == 0) {
                count++
                if (collect && firstSolution == null) firstSolution = buildGrid()
            }
            return
        }
        val len = fleet[index]
        val options = byLength[len]
        // Identical ships are interchangeable: force them into increasing placement order.
        val start = if (index > 0 && fleet[index - 1] == len) previous + 1 else 0
        for (option in start until options.size) {
            if (++nodes > budget) {
                aborted = true
                return
            }
            val placement = options[option]
            val newly = tryPlace(placement) ?: continue
            search(index + 1, option)
            undoPlace(placement, newly)
            if (count >= limit || aborted) return
        }
    }

    /** Places a ship if legal; returns the cells newly blocked, or null with the state unchanged. */
    private fun tryPlace(p: Placement): IntArray? {
        for (c in p.cells) if (blocked[c]) return null
        if (p.horizontal) {
            if (rowRem[p.cells[0] / w] < p.cells.size) return null
            for (c in p.cells) if (colRem[c % w] < 1) return null
        } else {
            if (colRem[p.cells[0] % w] < p.cells.size) return null
            for (c in p.cells) if (rowRem[c / w] < 1) return null
        }
        for (c in p.closed) {
            if (required[c] && !p.cells.contains(c)) return null
        }
        for (c in p.cells) {
            isShip[c] = true
            block(c)
            rowRem[c / w]--
            colRem[c % w]--
            if (required[c]) requiredLeft--
        }
        val newly = ArrayList<Int>()
        for (c in p.closed) {
            if (!blocked[c]) {
                block(c)
                newly.add(c)
            }
        }
        for (c in p.closed) {
            if (rowRem[c / w] > freeRow[c / w] || colRem[c % w] > freeCol[c % w]) {
                val result = newly.toIntArray()
                undoPlace(p, result)
                return null
            }
        }
        return newly.toIntArray()
    }

    private fun undoPlace(p: Placement, newly: IntArray) {
        for (c in newly) unblock(c)
        for (c in p.cells) {
            isShip[c] = false
            unblock(c)
            rowRem[c / w]++
            colRem[c % w]++
            if (required[c]) requiredLeft++
        }
    }

    private fun buildGrid(): PlayerGrid =
        PlayerGrid(w, h, List(n) { if (isShip[it]) PlayerCell.SHIP else PlayerCell.WATER })
}
