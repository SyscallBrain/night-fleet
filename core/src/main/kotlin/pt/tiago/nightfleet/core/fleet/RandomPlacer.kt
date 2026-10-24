package pt.tiago.nightfleet.core.fleet

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64
import kotlin.math.abs

/**
 * Integer weights that bias the order in which [RandomPlacer] tries candidate positions.
 * Positive weights prefer the feature; negative weights avoid it. [BALANCED] (all zero) gives an
 * unbiased random order.
 *
 * - [edges]: squares of the ship on the border of the grid.
 * - [centre]: closeness to the centre of the grid.
 * - [nearIslands]: squares next to land or reef.
 * - [spread]: avoidance of squares near other ships (applied as a penalty).
 * - [cluster]: attraction to squares near other ships.
 */
@Serializable
data class PlacementStyle(
    val edges: Int = 0,
    val centre: Int = 0,
    val nearIslands: Int = 0,
    val spread: Int = 0,
    val cluster: Int = 0,
) {
    companion object {
        val BALANCED = PlacementStyle()
    }
}

sealed class PlacementResult {
    /** [placements] follow the order of the fleet's ship list. [nodes] is the number of candidates tried. */
    data class Success(val placements: List<Placement>, val nodes: Int) : PlacementResult()

    data class Failure(val reason: String, val nodes: Int) : PlacementResult()
}

/**
 * Random legal layouts. Largest classes are placed first, with bounded backtracking. The search
 * never runs more than [NODE_BUDGET] candidate trials and then reports [REASON_NODE_BUDGET].
 * Given the same board, fleet, style and RNG state, the result is always the same.
 */
object RandomPlacer {
    const val NODE_BUDGET = 10_000
    const val REASON_INVALID_FLEET = "INVALID_FLEET"
    const val REASON_NODE_BUDGET = "NODE_BUDGET_EXCEEDED"
    const val REASON_NO_LAYOUT = "NO_LAYOUT"

    fun place(
        board: Board,
        fleet: Fleet,
        rng: SplitMix64,
        style: PlacementStyle = PlacementStyle.BALANCED,
        shipsMayTouch: Boolean = false,
    ): PlacementResult {
        if (PlacementValidator.fleetProblems(board, fleet).isNotEmpty()) {
            return PlacementResult.Failure(REASON_INVALID_FLEET, 0)
        }
        return Search(board, fleet, rng, style, shipsMayTouch).run()
    }

    private class Candidate(val placement: Placement, val score: Long, val key: Long)

    private class Search(
        private val board: Board,
        private val fleet: Fleet,
        private val rng: SplitMix64,
        private val style: PlacementStyle,
        private val shipsMayTouch: Boolean,
    ) {
        private val w = board.width
        private val h = board.height
        private val n = board.size

        /** Squares no ship may use: non-water, mines and squares next to a mine. */
        private val unusable = BooleanArray(n)

        /** Squares next to land or reef. */
        private val coastal = BooleanArray(n)
        private val occupied = BooleanArray(n)

        /** Placed ship squares in the 8-neighbourhood (self included) of each square. */
        private val block = IntArray(n)

        /** Placed ship squares within Chebyshev distance 2 of each square. */
        private val near = IntArray(n)
        private val closed8 = Array(n) { neighbourhood(it, 1) }
        private val box2 = Array(n) { neighbourhood(it, 2) }
        private val order = fleet.ships.indices.sortedWith(compareBy({ -fleet.ships[it].size }, { it }))
        private val chosen = arrayOfNulls<Placement>(fleet.ships.size)
        private var nodes = 0
        private var aborted = false

        init {
            for (index in 0 until n) {
                val neighbours = board.neighbours8(index)
                unusable[index] = board.terrainAt(index) != Terrain.WATER ||
                    board.isMine(index) || neighbours.any { board.isMine(it) }
                coastal[index] = neighbours.any { board.terrainAt(it) != Terrain.WATER }
            }
        }

        fun run(): PlacementResult {
            val ok = solve(0)
            return when {
                ok -> PlacementResult.Success(chosen.map { it!! }, nodes)
                aborted -> PlacementResult.Failure(REASON_NODE_BUDGET, nodes)
                else -> PlacementResult.Failure(REASON_NO_LAYOUT, nodes)
            }
        }

        private fun solve(depth: Int): Boolean {
            if (depth == order.size) return true
            val shipIndex = order[depth]
            for (candidate in candidates(fleet.ships[shipIndex])) {
                nodes++
                if (nodes > NODE_BUDGET) {
                    aborted = true
                    return false
                }
                update(candidate.placement, +1)
                chosen[shipIndex] = candidate.placement
                if (solve(depth + 1)) return true
                update(candidate.placement, -1)
                if (aborted) return false
            }
            chosen[shipIndex] = null
            return false
        }

        private fun candidates(shipClass: ShipClass): List<Candidate> {
            val out = ArrayList<Candidate>()
            for (rotation in shipClass.rotations.indices) {
                val offsets = shipClass.rotations[rotation]
                for (r in 0 until h) for (c in 0 until w) {
                    val squares = IntArray(offsets.size)
                    var legal = true
                    for ((k, offset) in offsets.withIndex()) {
                        val rr = r + offset.row
                        val cc = c + offset.col
                        if (!board.inBounds(rr, cc)) {
                            legal = false
                            break
                        }
                        val index = rr * w + cc
                        if (unusable[index] || occupied[index] || (!shipsMayTouch && block[index] > 0)) {
                            legal = false
                            break
                        }
                        squares[k] = index
                    }
                    if (!legal) continue
                    val placement = Placement(shipClass, Cell(r, c), rotation)
                    out += Candidate(placement, score(squares), rng.nextLong())
                }
            }
            return out.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.key })
        }

        private fun score(squares: IntArray): Long {
            var edges = 0L
            var centre = 0L
            var coast = 0L
            var nearby = 0L
            for (index in squares) {
                val r = index / w
                val c = index % w
                if (r == 0 || c == 0 || r == h - 1 || c == w - 1) edges++
                centre -= (abs(2 * r - (h - 1)) + abs(2 * c - (w - 1))).toLong()
                if (coastal[index]) coast++
                nearby += near[index].toLong()
            }
            return style.edges * edges + style.centre * centre + style.nearIslands * coast -
                style.spread * nearby + style.cluster * nearby
        }

        private fun update(placement: Placement, delta: Int) {
            for (cell in placement.cells()) {
                val index = cell.row * w + cell.col
                occupied[index] = delta > 0
                for (x in closed8[index]) block[x] += delta
                for (x in box2[index]) near[x] += delta
            }
        }

        private fun neighbourhood(index: Int, radius: Int): IntArray {
            val r = index / w
            val c = index % w
            val list = ArrayList<Int>()
            for (rr in r - radius..r + radius) for (cc in c - radius..c + radius) {
                if (board.inBounds(rr, cc)) list += rr * w + cc
            }
            return list.toIntArray()
        }
    }
}
