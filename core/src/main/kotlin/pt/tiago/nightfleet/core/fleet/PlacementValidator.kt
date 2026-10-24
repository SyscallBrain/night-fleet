package pt.tiago.nightfleet.core.fleet

import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain

enum class ProblemCode {
    OVERLAP,
    TOUCHING,
    TERRAIN,
    OUT_OF_BOUNDS,
    MINE,
    MINE_NEAR_SHIP,
    INCOMPLETE_FLEET,
    DENSITY_OVER_25_PERCENT,
    INVALID_ROTATION,
}

/**
 * One problem found by [PlacementValidator]. [shipIndex] and [otherIndex] index the placement list
 * (-1 when not applicable); [cell] is the offending square when there is one.
 */
data class PlacementProblem(
    val code: ProblemCode,
    val shipIndex: Int = -1,
    val otherIndex: Int = -1,
    val cell: Cell? = null,
)

/** Checks fleet layouts and decoy positions Never throws on bad input. */
object PlacementValidator {

    /** Maximum share of the playable squares that a fleet may occupy. */
    const val MAX_DENSITY_PERCENT = 25

    /** Fleet-level problems: the fleet must not exceed 25 % of the water squares of [board]. */
    fun fleetProblems(board: Board, fleet: Fleet): List<PlacementProblem> {
        val playable = board.terrain.count { it == Terrain.WATER }
        return if (fleet.cellCount * 100 > playable * MAX_DENSITY_PERCENT) {
            listOf(PlacementProblem(ProblemCode.DENSITY_OVER_25_PERCENT))
        } else {
            emptyList()
        }
    }

    /**
     * Validates [placements] against [board] and [fleet]: composition, rotations, bounds, terrain,
     * mines, overlaps and (unless [shipsMayTouch]) touching ships, including diagonals.
     * An empty list means the layout is valid.
     */
    fun validate(
        board: Board,
        fleet: Fleet,
        placements: List<Placement>,
        shipsMayTouch: Boolean = false,
    ): List<PlacementProblem> {
        val problems = mutableListOf<PlacementProblem>()
        problems += fleetProblems(board, fleet)
        if (fleet.ships.groupingBy { it }.eachCount() != placements.groupingBy { it.shipClass }.eachCount()) {
            problems += PlacementProblem(ProblemCode.INCOMPLETE_FLEET)
        }

        val owner = IntArray(board.size) { -1 }
        val squares = arrayOfNulls<IntArray>(placements.size)
        placements.forEachIndexed { i, placement ->
            if (placement.rotationIndex !in placement.shipClass.rotations.indices) {
                problems += PlacementProblem(ProblemCode.INVALID_ROTATION, shipIndex = i)
                squares[i] = IntArray(0)
                return@forEachIndexed
            }
            val squareIndices = IntArray(placement.shipClass.size)
            var k = 0
            for (cell in placement.cells()) {
                if (!board.inBounds(cell)) {
                    problems += PlacementProblem(ProblemCode.OUT_OF_BOUNDS, shipIndex = i, cell = cell)
                    continue
                }
                val index = board.index(cell)
                if (board.terrainAt(index) != Terrain.WATER) {
                    problems += PlacementProblem(ProblemCode.TERRAIN, shipIndex = i, cell = cell)
                }
                if (board.isMine(index)) {
                    problems += PlacementProblem(ProblemCode.MINE, shipIndex = i, cell = cell)
                }
                if (owner[index] != -1) {
                    problems += PlacementProblem(ProblemCode.OVERLAP, shipIndex = owner[index], otherIndex = i, cell = cell)
                } else {
                    owner[index] = i
                }
                squareIndices[k++] = index
            }
            val owned = squareIndices.copyOf(k)
            squares[i] = owned
            // A mine never sits next to a ship, so the automatic water around a sunk ship is never a lie.
            for (index in owned) {
                if (board.neighbours8(index).any { board.isMine(it) }) {
                    problems += PlacementProblem(ProblemCode.MINE_NEAR_SHIP, shipIndex = i, cell = board.cell(index))
                }
            }
        }

        if (!shipsMayTouch) {
            val touching = LinkedHashSet<Pair<Int, Int>>()
            for (i in placements.indices) {
                for (index in squares[i] ?: continue) {
                    for (neighbour in board.neighbours8(index)) {
                        val other = owner[neighbour]
                        if (other != -1 && other != i) {
                            touching += minOf(i, other) to maxOf(i, other)
                        }
                    }
                }
            }
            for ((a, b) in touching) {
                problems += PlacementProblem(ProblemCode.TOUCHING, shipIndex = a, otherIndex = b)
            }
        }
        return problems
    }

    /**
     * Validates a decoy (Isco) at [cell]: one square of water inside the board, not a mine, not
     * occupied and not touching any ship of [placements] (diagonals included).
     */
    fun validateDecoy(
        board: Board,
        placements: List<Placement>,
        cell: Cell,
        shipsMayTouch: Boolean = false,
    ): List<PlacementProblem> {
        if (!board.inBounds(cell)) return listOf(PlacementProblem(ProblemCode.OUT_OF_BOUNDS, cell = cell))
        val index = board.index(cell)
        val problems = mutableListOf<PlacementProblem>()
        if (board.terrainAt(index) != Terrain.WATER) problems += PlacementProblem(ProblemCode.TERRAIN, cell = cell)
        if (board.isMine(index)) problems += PlacementProblem(ProblemCode.MINE, cell = cell)
        placements.forEachIndexed { i, placement ->
            if (placement.rotationIndex !in placement.shipClass.rotations.indices) return@forEachIndexed
            val shipSquares = placement.cells().filter { board.inBounds(it) }.map { board.index(it) }
            if (index in shipSquares) {
                problems += PlacementProblem(ProblemCode.OVERLAP, otherIndex = i, cell = cell)
            } else if (!shipsMayTouch && board.neighbours8(index).any { it in shipSquares }) {
                problems += PlacementProblem(ProblemCode.TOUCHING, otherIndex = i, cell = cell)
            }
        }
        return problems
    }
}
