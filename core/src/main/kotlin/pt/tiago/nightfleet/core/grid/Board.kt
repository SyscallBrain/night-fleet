package pt.tiago.nightfleet.core.grid

import kotlinx.serialization.Serializable

/**
 * One sea. Squares are indexed `row * width + col`; every neighbour helper returns indices in
 * ascending order, so iteration over a board is always deterministic.
 *
 * [mines] is the sorted set of mine indices of this sea (M2 rules; empty in M1).
 */
@Serializable
data class Board(
    val width: Int,
    val height: Int,
    val terrain: List<Terrain>,
    val mines: List<Int> = emptyList(),
) {
    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Board size out of range: ${width}x$height" }
        require(terrain.size == width * height) { "Terrain must have ${width * height} entries" }
        require(mines.sorted() == mines && mines.toSet().size == mines.size) { "Mines must be sorted and unique" }
        require(mines.all { it in terrain.indices && terrain[it] == Terrain.WATER }) { "Mines must lie on water" }
    }

    val size: Int get() = width * height

    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    fun inBounds(cell: Cell): Boolean = inBounds(cell.row, cell.col)

    /** Index of [cell]. The cell must be inside the board. */
    fun index(cell: Cell): Int {
        require(inBounds(cell)) { "Cell $cell is outside the ${width}x$height board" }
        return cell.row * width + cell.col
    }

    fun cell(index: Int): Cell = Cell(index / width, index % width)

    fun terrainAt(index: Int): Terrain = terrain[index]

    fun isMine(index: Int): Boolean = mines.binarySearch(index) >= 0

    /** Orthogonal neighbours of [index], ascending. */
    fun neighbours4(index: Int): IntArray {
        val r = index / width
        val c = index % width
        val out = IntArray(4)
        var k = 0
        for ((dr, dc) in NEIGHBOURS4) {
            val nr = r + dr
            val nc = c + dc
            if (inBounds(nr, nc)) out[k++] = nr * width + nc
        }
        return out.copyOf(k)
    }

    /** The eight surrounding squares of [index] (diagonals included), ascending, without [index] itself. */
    fun neighbours8(index: Int): IntArray {
        val r = index / width
        val c = index % width
        val out = IntArray(8)
        var k = 0
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val nr = r + dr
            val nc = c + dc
            if (inBounds(nr, nc)) out[k++] = nr * width + nc
        }
        return out.copyOf(k)
    }

    companion object {
        const val MAX_SIDE = 64
        private val NEIGHBOURS4 = listOf(-1 to 0, 0 to -1, 0 to 1, 1 to 0)

        fun water(width: Int, height: Int): Board = Board(width, height, List(width * height) { Terrain.WATER })
    }
}
