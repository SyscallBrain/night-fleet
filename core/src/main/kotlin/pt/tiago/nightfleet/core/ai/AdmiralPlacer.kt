package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64
import kotlin.math.abs

/**
 * Places an admiral's fleet: [CANDIDATES] random layouts with the profile's style,
 * and the one with the best style score is kept. Ties keep the earlier candidate.
 */
object AdmiralPlacer {
    const val CANDIDATES = 8

    fun place(
        board: Board,
        fleet: Fleet,
        profile: AdmiralProfile,
        rng: SplitMix64,
        shipsMayTouch: Boolean = false,
    ): PlacementResult {
        var best: List<Placement>? = null
        var bestScore = Long.MIN_VALUE
        var nodes = 0
        repeat(CANDIDATES) {
            val candidateRng = SplitMix64(rng.nextLong())
            when (val result = RandomPlacer.place(board, fleet, candidateRng, profile.placementStyle, shipsMayTouch)) {
                is PlacementResult.Failure -> return result
                is PlacementResult.Success -> {
                    nodes += result.nodes
                    val score = styleScore(board, result.placements, profile.placementStyle)
                    if (score > bestScore) {
                        bestScore = score
                        best = result.placements
                    }
                }
            }
        }
        return PlacementResult.Success(best!!, nodes)
    }

    /**
     * Decoy squares (Isco) for an admiral: water squares that touch no ship, that are not mines
     * and that do not touch each other. The admiral's style ranks them, so a compact admiral hides its decoys
     * near its fleet and a spread admiral far from it. Fewer than [count] are returned when the sea is full.
     */
    fun placeDecoys(
        board: Board,
        placements: List<Placement>,
        count: Int,
        profile: AdmiralProfile,
        rng: SplitMix64,
        shipsMayTouch: Boolean = false,
    ): List<Cell> {
        require(count >= 0) { "Decoy count must not be negative" }
        val w = board.width
        val h = board.height
        val owner = IntArray(board.size) { -1 }
        placements.forEachIndexed { i, p -> p.cells().forEach { c -> owner[c.row * w + c.col] = i } }
        val style = profile.placementStyle
        val blocked = BooleanArray(board.size)
        val chosen = ArrayList<Cell>()
        repeat(count) {
            var best = Long.MIN_VALUE
            val ties = ArrayList<Int>()
            for (x in 0 until board.size) {
                if (blocked[x]) continue
                if (PlacementValidator.validateDecoy(board, placements, board.cell(x), shipsMayTouch).isNotEmpty()) continue
                val score = squareScore(board, owner, x, style)
                if (score > best) {
                    best = score
                    ties.clear()
                }
                if (score == best) ties += x
            }
            if (ties.isEmpty()) return chosen
            val pick = ties[rng.nextInt(ties.size)]
            chosen += board.cell(pick)
            blocked[pick] = true
            for (y in board.neighbours8(pick)) blocked[y] = true
        }
        return chosen
    }

    /** Style score of one square as if a ship stood there, with the weights of [styleScore]. */
    private fun squareScore(board: Board, owner: IntArray, index: Int, style: PlacementStyle): Long {
        val w = board.width
        val h = board.height
        val r = index / w
        val c = index % w
        val edge = if (r == 0 || c == 0 || r == h - 1 || c == w - 1) 1L else 0L
        val centre = -(abs(2 * r - (h - 1)) + abs(2 * c - (w - 1))).toLong()
        val coast = if (board.neighbours8(index).any { board.terrainAt(it) != Terrain.WATER }) 1L else 0L
        var nearby = 0L
        for (rr in r - 2..r + 2) for (cc in c - 2..c + 2) {
            if (rr !in 0 until h || cc !in 0 until w) continue
            if (owner[rr * w + cc] != -1) nearby++
        }
        return style.edges * edge + style.centre * centre + style.nearIslands * coast +
            (style.cluster - style.spread).toLong() * nearby
    }

    /** Style score of a layout, with the same weights as [RandomPlacer]. */
    fun styleScore(board: Board, placements: List<Placement>, style: PlacementStyle): Long {
        val w = board.width
        val h = board.height
        val owner = IntArray(board.size) { -1 }
        val squares = placements.mapIndexed { i, placement ->
            placement.cells().map { cell -> (cell.row * w + cell.col).also { owner[it] = i } }
        }
        var edges = 0L
        var centre = 0L
        var coast = 0L
        var nearby = 0L
        for ((i, list) in squares.withIndex()) for (index in list) {
            val r = index / w
            val c = index % w
            if (r == 0 || c == 0 || r == h - 1 || c == w - 1) edges++
            centre -= (abs(2 * r - (h - 1)) + abs(2 * c - (w - 1))).toLong()
            if (board.neighbours8(index).any { board.terrainAt(it) != Terrain.WATER }) coast++
            for (rr in r - 2..r + 2) for (cc in c - 2..c + 2) {
                if (rr !in 0 until h || cc !in 0 until w) continue
                val other = owner[rr * w + cc]
                if (other != -1 && other != i) nearby++
            }
        }
        return style.edges * edges + style.centre * centre + style.nearIslands * coast +
            (style.cluster - style.spread).toLong() * nearby
    }
}
