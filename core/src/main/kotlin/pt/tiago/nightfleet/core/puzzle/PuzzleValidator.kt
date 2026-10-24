package pt.tiago.nightfleet.core.puzzle

/** Count of ship cells in a line compared with its clue. */
enum class LineStatus { OK, UNDER, OVER }

enum class IssueKind { ON_ISLAND, DIAGONAL_TOUCH, BAD_SHAPE, FLEET_MISMATCH, GIVEN_MISMATCH }

/** A rule broken by the player's marks, with the cells involved (sorted). */
data class ValidationIssue(val kind: IssueKind, val cells: List<Int>)

data class ValidationReport(
    val rowStatus: List<LineStatus>,
    val colStatus: List<LineStatus>,
    val issues: List<ValidationIssue>,
    val complete: Boolean,
) {
    val isSolved: Boolean
        get() = complete && issues.isEmpty() && rowStatus.all { it == LineStatus.OK } && colStatus.all { it == LineStatus.OK }
}

/** Checks player grids against the rules of the puzzle. Partial grids are checked as far as they go. */
object PuzzleValidator {

    fun validate(definition: PuzzleDefinition, grid: PlayerGrid): ValidationReport {
        require(grid.width == definition.width && grid.height == definition.height) { "Grid size mismatch" }
        val w = definition.width
        val h = definition.height
        val rowShips = IntArray(h)
        val colShips = IntArray(w)
        for (cell in 0 until definition.cellCount) {
            if (grid[cell] == PlayerCell.SHIP) {
                rowShips[cell / w]++
                colShips[cell % w]++
            }
        }
        val rowStatus = List(h) { statusOf(rowShips[it], definition.rowCounts[it]) }
        val colStatus = List(w) { statusOf(colShips[it], definition.colCounts[it]) }

        val issues = ArrayList<ValidationIssue>()

        val onIsland = definition.islands.filter { grid[it] == PlayerCell.SHIP }
        if (onIsland.isNotEmpty()) issues.add(ValidationIssue(IssueKind.ON_ISLAND, onIsland))

        val diagonal = sortedSetOf<Int>()
        for (cell in 0 until definition.cellCount) {
            if (grid[cell] != PlayerCell.SHIP) continue
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 || dc == 0) continue
                val nb = definition.offset(cell, dr, dc)
                if (nb >= 0 && grid[nb] == PlayerCell.SHIP) {
                    diagonal.add(cell)
                    diagonal.add(nb)
                }
            }
        }
        if (diagonal.isNotEmpty()) issues.add(ValidationIssue(IssueKind.DIAGONAL_TOUCH, diagonal.toList()))

        val components = shipComponents(definition, grid)
        val maxLength = definition.fleet.first()
        var shapesOk = true
        for (component in components) {
            if (!isStraight(definition, component) || component.size > maxLength) {
                shapesOk = false
                issues.add(ValidationIssue(IssueKind.BAD_SHAPE, component.toList()))
            }
        }
        if (grid.isComplete && shapesOk) {
            val lengths = components.map { it.size }.sortedDescending()
            if (lengths != definition.fleet) {
                val allShips = (0 until definition.cellCount).filter { grid[it] == PlayerCell.SHIP }
                issues.add(ValidationIssue(IssueKind.FLEET_MISMATCH, allShips))
            }
        }

        val givenBroken = definition.givens.entries
            .sortedBy { it.key }
            .filter { !givenRespected(definition, grid, it.key, it.value) }
            .map { it.key }
        if (givenBroken.isNotEmpty()) issues.add(ValidationIssue(IssueKind.GIVEN_MISMATCH, givenBroken))

        return ValidationReport(rowStatus, colStatus, issues, grid.isComplete)
    }

    fun isSolved(definition: PuzzleDefinition, grid: PlayerGrid): Boolean = validate(definition, grid).isSolved

    /**
     * Cells whose mark contradicts [solution] (a complete grid of SHIP and WATER). EMPTY cells are
     * never errors. Returned in ascending cell order.
     */
    fun errors(grid: PlayerGrid, solution: PlayerGrid): List<Int> {
        require(grid.width == solution.width && grid.height == solution.height) { "Grid size mismatch" }
        return (0 until grid.cells.size).filter { cell ->
            val mark = grid[cell]
            mark != PlayerCell.EMPTY && mark != solution[cell]
        }
    }

    private fun statusOf(count: Int, target: Int): LineStatus = when {
        count < target -> LineStatus.UNDER
        count > target -> LineStatus.OVER
        else -> LineStatus.OK
    }

    /** 4-connected groups of ship cells, each sorted by cell index, groups ordered by first cell. */
    internal fun shipComponents(definition: PuzzleDefinition, grid: PlayerGrid): List<IntArray> {
        val seen = BooleanArray(definition.cellCount)
        val components = ArrayList<IntArray>()
        for (start in 0 until definition.cellCount) {
            if (seen[start] || grid[start] != PlayerCell.SHIP) continue
            val members = ArrayList<Int>()
            val stack = ArrayList<Int>()
            stack.add(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeAt(stack.size - 1)
                members.add(cell)
                for (dir in 0..3) {
                    val nb = definition.neighbour(cell, dir)
                    if (nb >= 0 && !seen[nb] && grid[nb] == PlayerCell.SHIP) {
                        seen[nb] = true
                        stack.add(nb)
                    }
                }
            }
            members.sort()
            components.add(members.toIntArray())
        }
        return components
    }

    private fun isStraight(definition: PuzzleDefinition, cells: IntArray): Boolean {
        if (cells.size == 1) return true
        val sameRow = cells.all { definition.row(it) == definition.row(cells[0]) }
        val sameCol = cells.all { definition.col(it) == definition.col(cells[0]) }
        return sameRow || sameCol
    }

    /**
     * Whether a given holds for the player's marks. A clue is broken when a cell it fixes is marked
     * the other way, or when a neighbour it fixes is a known mark of the wrong kind. [MIDDLE] also
     * needs an opposite pair of ship neighbours once the grid is complete.
     */
    private fun givenRespected(definition: PuzzleDefinition, grid: PlayerGrid, cell: Int, given: Given): Boolean {
        val state = grid[cell]
        val selfOk = if (given == Given.WATER) state != PlayerCell.SHIP else state != PlayerCell.WATER
        if (!selfOk) return false
        if (given == Given.WATER) return true
        val requirements = requirementsOf(given)
        for (dir in 0..3) {
            val nb = definition.neighbour(cell, dir)
            when (requirements[dir]) {
                REQ_WATER -> if (nb >= 0 && grid[nb] == PlayerCell.SHIP) return false
                REQ_SHIP -> if (nb < 0 || grid[nb] == PlayerCell.WATER) return false
            }
        }
        if (given == Given.MIDDLE && grid.isComplete) {
            fun ship(dir: Int): Boolean {
                val nb = definition.neighbour(cell, dir)
                return nb >= 0 && grid[nb] == PlayerCell.SHIP
            }
            return (ship(Dir.NORTH) && ship(Dir.SOUTH)) || (ship(Dir.EAST) && ship(Dir.WEST))
        }
        return true
    }

    private const val REQ_ANY = 0
    private const val REQ_WATER = 1
    private const val REQ_SHIP = 2

    /** Per direction (N, E, S, W): what a clue requires of that neighbour. */
    internal fun requirementsOf(given: Given): IntArray {
        val req = IntArray(4) { REQ_ANY }
        val continuation = when (given) {
            Given.SUB -> {
                req.fill(REQ_WATER)
                -1
            }
            Given.END_N -> Dir.SOUTH
            Given.END_E -> Dir.WEST
            Given.END_S -> Dir.NORTH
            Given.END_W -> Dir.EAST
            else -> -1
        }
        if (continuation >= 0) {
            req.fill(REQ_WATER)
            req[continuation] = REQ_SHIP
        }
        return req
    }
}
