package pt.tiago.nightfleet.game.freeplay

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * Islands for the free play: a few random walks of land from a seed. The result is kept only when the
 * fleet can still be placed on it (checked with [RandomPlacer] on three streams), so an island map never
 * makes a game impossible to set up.
 */
object TerrainGenerator {

    /** How many different island maps are tried before the sea is left empty. */
    const val ATTEMPTS = 8

    /** Islands for a [width] by [height] sea of [fleet], or null when no tried map keeps the fleet placeable. */
    fun generate(width: Int, height: Int, seed: Long, fleet: Fleet): List<Terrain>? {
        for (attempt in 0 until ATTEMPTS) {
            val terrain = islands(width, height, SplitMix64(SplitMix64.mix(seed xor (attempt.toLong() * 0x9E37L))))
            if (placeable(width, height, terrain, fleet)) return terrain
        }
        return null
    }

    /** The land of one map: 2 to 5 random walks of 1 to 4 squares. Deterministic for [rng]. */
    internal fun islands(width: Int, height: Int, rng: SplitMix64): List<Terrain> {
        val terrain = MutableList(width * height) { Terrain.WATER }
        val walks = 2 + rng.nextInt(4)
        repeat(walks) {
            var row = rng.nextInt(height)
            var col = rng.nextInt(width)
            val length = 1 + rng.nextInt(4)
            repeat(length) {
                terrain[row * width + col] = Terrain.ISLAND
                val step = STEPS[rng.nextInt(STEPS.size)]
                row = (row + step.first).coerceIn(0, height - 1)
                col = (col + step.second).coerceIn(0, width - 1)
            }
        }
        return terrain
    }

    /** The fleet can be placed on [terrain] on three different streams. */
    internal fun placeable(width: Int, height: Int, terrain: List<Terrain>, fleet: Fleet): Boolean {
        val board = Board(width, height, terrain)
        return (1L..3L).all { stream ->
            RandomPlacer.place(board, fleet, SplitMix64(SplitMix64.mix(stream))) is PlacementResult.Success
        }
    }

    private val STEPS = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)
}

/**
 * Mines and decoys of a free play battle. Both are placed away from the fleet of their sea:
 * a mine never touches a ship, and a decoy never touches one either (validated by [PlacementValidator]).
 */
object SetupGenerators {

    /**
     * [count] mines on the water of [board] that touch no ship of [ships] (the ships' squares and their eight
     * neighbours are excluded). Sorted row-major indices, fewer than [count] when the sea is too full.
     */
    fun mines(board: Board, ships: List<Placement>, count: Int, rng: SplitMix64): List<Int> {
        if (count <= 0) return emptyList()
        val forbidden = BooleanArray(board.size)
        for (ship in ships) for (cell in ship.cells()) {
            if (!board.inBounds(cell)) continue
            val index = board.index(cell)
            forbidden[index] = true
            for (neighbour in board.neighbours8(index)) forbidden[neighbour] = true
        }
        val pool = (0 until board.size).filter { !forbidden[it] && board.terrainAt(it) == Terrain.WATER }.toMutableList()
        val chosen = ArrayList<Int>()
        repeat(minOf(count, pool.size)) {
            chosen += pool.removeAt(rng.nextInt(pool.size))
        }
        return chosen.sorted()
    }

    /**
     * One decoy (isco) for [board] (with its mines already set) and [ships], or null when no water square is
     * free. A random valid square; the choice is deterministic for [rng].
     */
    fun decoy(board: Board, ships: List<Placement>, rng: SplitMix64): Cell? {
        val candidates = (0 until board.size).map { board.cell(it) }.filter { cell ->
            PlacementValidator.validateDecoy(board, ships, cell).isEmpty()
        }
        if (candidates.isEmpty()) return null
        return candidates[rng.nextInt(candidates.size)]
    }
}
