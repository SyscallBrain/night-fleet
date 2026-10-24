package pt.tiago.nightfleet.core.puzzle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Direction indices used by [PuzzleDefinition.neighbour]. */
object Dir {
    const val NORTH = 0
    const val EAST = 1
    const val SOUTH = 2
    const val WEST = 3
}

private val DR = intArrayOf(-1, 0, 1, 0)
private val DC = intArrayOf(0, 1, 0, -1)

/** Allowed side lengths of a lighthouse puzzle grid. */
val PUZZLE_SIZES: List<Int> = listOf(6, 8, 10)

/**
 * A fixed clue of the puzzle. Ship clues describe the shape of the ship that covers the cell:
 * `END_N` is the north end of a vertical ship (the ship continues south), and so on.
 * `MIDDLE` is an inner cell of a ship of length 3 or more, `SUB` a ship of length 1, `SHIP` a ship
 * cell with no known shape.
 */
@Serializable
enum class Given { WATER, SUB, END_N, END_E, END_S, END_W, MIDDLE, SHIP }

/** State of one cell in a player's grid. Islands are always [WATER] in a player's grid. */
@Serializable
enum class PlayerCell { EMPTY, WATER, SHIP }

/** Visual shape of a ship cell, deduced from its orthogonal ship neighbours. */
enum class SegmentShape { SINGLE, END_N, END_E, END_S, END_W, MIDDLE_H, MIDDLE_V, UNRESOLVED }

/**
 * An immutable puzzle. Cells are indexed `row * width + col`, origin at the top left.
 * [islands] and [givens] keys are cell indices; [islands] must be sorted and distinct.
 * [fleet] lists straight ship lengths from largest to smallest.
 */
@Serializable
data class PuzzleDefinition(
    val id: String,
    val width: Int,
    val height: Int,
    val islands: List<Int>,
    val fleet: List<Int>,
    val rowCounts: List<Int>,
    val colCounts: List<Int>,
    val givens: Map<Int, Given> = emptyMap(),
) {
    init {
        require(width in PUZZLE_SIZES && height in PUZZLE_SIZES) { "Puzzle sides must be 6, 8 or 10" }
        require(fleet.isNotEmpty() && fleet.all { it in 1..4 }) { "Fleet lengths must be 1..4" }
        require(fleet == fleet.sortedDescending()) { "Fleet must be sorted from largest to smallest" }
        require(rowCounts.size == height && colCounts.size == width) { "Count lists must match the grid" }
        require(rowCounts.all { it in 0..width } && colCounts.all { it in 0..height }) { "Count out of range" }
        val cells = width * height
        require(islands == islands.sorted().distinct() && islands.all { it in 0 until cells }) {
            "Islands must be distinct, sorted cell indices"
        }
        require(givens.keys.all { it in 0 until cells && it !in islands }) { "Givens must be on the grid, not on islands" }
        val total = fleet.sum()
        require(rowCounts.sum() == total && colCounts.sum() == total) { "Counts must add up to the fleet size" }
    }

    val cellCount: Int get() = width * height

    fun row(cell: Int): Int = cell / width

    fun col(cell: Int): Int = cell % width

    fun cellAt(row: Int, col: Int): Int = row * width + col

    /** Cell at the given offset, or -1 when it falls outside the grid. */
    fun offset(cell: Int, dr: Int, dc: Int): Int {
        val r = cell / width + dr
        val c = cell % width + dc
        return if (r in 0 until height && c in 0 until width) r * width + c else -1
    }

    /** Orthogonal neighbour in [dir] (see [Dir]), or -1 at the edge. */
    fun neighbour(cell: Int, dir: Int): Int = offset(cell, DR[dir], DC[dir])

    /** The grid as a player sees it before playing: islands and water clues filled, ship clues filled. */
    fun startGrid(): PlayerGrid {
        val cells = MutableList(cellCount) { PlayerCell.EMPTY }
        for (island in islands) cells[island] = PlayerCell.WATER
        for ((cell, given) in givens) cells[cell] = if (given == Given.WATER) PlayerCell.WATER else PlayerCell.SHIP
        return PlayerGrid(width, height, cells)
    }
}

/** A player's marks. [cells] has `width * height` entries in cell-index order. */
@Serializable
data class PlayerGrid(val width: Int, val height: Int, val cells: List<PlayerCell>) {
    init {
        require(cells.size == width * height) { "Grid size mismatch" }
    }

    operator fun get(cell: Int): PlayerCell = cells[cell]

    fun withCell(cell: Int, value: PlayerCell): PlayerGrid {
        val copy = cells.toMutableList()
        copy[cell] = value
        return PlayerGrid(width, height, copy)
    }

    val isComplete: Boolean get() = cells.none { it == PlayerCell.EMPTY }

    /** Visual shape of a SHIP cell, or null when the cell is not a ship. */
    fun segmentShape(cell: Int): SegmentShape? {
        if (cells[cell] != PlayerCell.SHIP) return null
        fun ship(dir: Int): Boolean {
            val nb = neighbourOf(cell, dir)
            return nb >= 0 && cells[nb] == PlayerCell.SHIP
        }
        return shapeFromNeighbours(ship(Dir.NORTH), ship(Dir.EAST), ship(Dir.SOUTH), ship(Dir.WEST))
    }

    private fun neighbourOf(cell: Int, dir: Int): Int {
        val r = cell / width + (if (dir == Dir.NORTH) -1 else if (dir == Dir.SOUTH) 1 else 0)
        val c = cell % width + (if (dir == Dir.WEST) -1 else if (dir == Dir.EAST) 1 else 0)
        return if (r in 0 until height && c in 0 until width) r * width + c else -1
    }
}

/** Shape of a ship cell from which of its four orthogonal neighbours are ship cells. */
fun shapeFromNeighbours(north: Boolean, east: Boolean, south: Boolean, west: Boolean): SegmentShape = when {
    !north && !east && !south && !west -> SegmentShape.SINGLE
    north && south && !east && !west -> SegmentShape.MIDDLE_V
    east && west && !north && !south -> SegmentShape.MIDDLE_H
    south && !north && !east && !west -> SegmentShape.END_N
    west && !north && !east && !south -> SegmentShape.END_E
    north && !east && !south && !west -> SegmentShape.END_S
    east && !north && !south && !west -> SegmentShape.END_W
    else -> SegmentShape.UNRESOLVED
}

/** The clue that a given ship cell with this shape would carry, or null when no clue matches. */
internal fun SegmentShape.toGiven(): Given? = when (this) {
    SegmentShape.SINGLE -> Given.SUB
    SegmentShape.END_N -> Given.END_N
    SegmentShape.END_E -> Given.END_E
    SegmentShape.END_S -> Given.END_S
    SegmentShape.END_W -> Given.END_W
    SegmentShape.MIDDLE_H, SegmentShape.MIDDLE_V -> Given.MIDDLE
    SegmentShape.UNRESOLVED -> null
}

/** Techniques of the logical grader, from the simplest to the most demanding. */
@Serializable
enum class Technique { T1, T2, T3, T4 }

/** Hardest technique needed to solve a puzzle and the number of cells deduced. */
@Serializable
data class Grade(val maxTechnique: Technique, val steps: Int)

/** Player-facing difficulty of a puzzle. */
@Serializable
enum class PuzzleGrade { EASY, MEDIUM, HARD }

/** A T3 puzzle with at least this many deductions is HARD rather than MEDIUM. */
const val HARD_T3_MIN_STEPS: Int = 12

/** EASY: at most T2. MEDIUM: T3 with few steps. HARD: T4, or T3 with many steps. */
fun Grade.difficulty(): PuzzleGrade = when {
    maxTechnique <= Technique.T2 -> PuzzleGrade.EASY
    maxTechnique == Technique.T3 && steps < HARD_T3_MIN_STEPS -> PuzzleGrade.MEDIUM
    else -> PuzzleGrade.HARD
}

/** Canonical JSON form of a puzzle (givens sorted by cell, default values always written). */
object PuzzleCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(definition: PuzzleDefinition): String {
        val canonical = definition.copy(givens = definition.givens.toSortedMap())
        return json.encodeToString(PuzzleDefinition.serializer(), canonical)
    }

    fun decode(text: String): PuzzleDefinition = json.decodeFromString(PuzzleDefinition.serializer(), text)
}
