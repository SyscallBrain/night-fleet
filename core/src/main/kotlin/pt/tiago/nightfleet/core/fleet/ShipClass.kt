package pt.tiago.nightfleet.core.fleet

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.grid.Cell

/**
 * Ship classes. Each shape is a normalised polyomino (its bounding box starts at
 * row 0 and column 0). [rotations] lists the distinct orientations, base shape first: a line ship
 * has 2, the seaplane T has 4 and the midget submarine has 1.
 */
@Serializable
enum class ShipClass(
    private val base: List<Cell>,
    /** Can dive when the submarine rule is on. */
    val submersible: Boolean = false,
    /** Cargo ship for the convoy missions. */
    val cargo: Boolean = false,
) {
    CARRIER(horizontalLine(5)),
    BATTLESHIP(horizontalLine(4)),
    CRUISER(horizontalLine(3)),
    SUBMARINE(horizontalLine(3), submersible = true),
    DESTROYER(horizontalLine(2)),
    SEAPLANE(listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(1, 1))),
    MIDGET_SUB(listOf(Cell(0, 0))),
    FREIGHTER(horizontalLine(2), cargo = true);

    /** Number of squares of the shape. */
    val size: Int = base.size

    /** Distinct normalised orientations, index 0 is the base shape. */
    val rotations: List<List<Cell>> = uniqueRotations(base)

    val rotationCount: Int get() = rotations.size

    /** Squares of orientation [rotationIndex] with the bounding-box corner at [origin]. */
    fun cellsAt(rotationIndex: Int, origin: Cell): List<Cell> =
        rotations[rotationIndex].map { Cell(origin.row + it.row, origin.col + it.col) }
}

private fun horizontalLine(length: Int): List<Cell> = List(length) { Cell(0, it) }

/** Shifts the shape so its minimum row and column are 0, sorted by (row, col). */
internal fun normalizeShape(cells: List<Cell>): List<Cell> {
    val minRow = cells.minOf { it.row }
    val minCol = cells.minOf { it.col }
    return cells.map { Cell(it.row - minRow, it.col - minCol) }
        .sortedWith(compareBy<Cell> { it.row }.thenBy { it.col })
}

/** Rotates by 90 degrees four times and keeps each new normalised orientation once, in order. */
internal fun uniqueRotations(base: List<Cell>): List<List<Cell>> {
    val out = mutableListOf<List<Cell>>()
    var current = normalizeShape(base)
    repeat(4) {
        if (current !in out) out += current
        current = normalizeShape(current.map { Cell(it.col, -it.row) })
    }
    return out
}
