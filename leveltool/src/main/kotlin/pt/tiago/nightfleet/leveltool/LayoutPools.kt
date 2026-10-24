package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64

/** One enemy sea layout pool (`layout-pool`). */
internal object LayoutPools {
    /** Upper bound of random tries per requested layout before the generator gives up. */
    private const val TRIES_PER_LAYOUT = 200L

    /** Random layouts drawn per slot of a styled pool; the best one under the style is kept. */
    private const val STYLE_DRAWS = 6

    /**
     * [count] distinct enemy layouts on [board] (its terrain, no mines yet), placed with [style].
     * Two layouts are the same when one is the other under a symmetry that maps the terrain onto
     * itself: those are rejected. Layouts are sorted by ship order, row and column.
     */
    fun layouts(board: Board, fleet: Fleet, style: PlacementStyle, count: Int, seed: Long): List<List<Placement>> {
        val side = board.width
        // A symmetry relates two layouts only if it also maps the terrain and the mines onto themselves.
        val mineSet = board.mines.toSet()
        val usable = Symmetry.invariant(board.terrain, side).filter { s ->
            mineSet.all { m ->
                val (r, c) = Symmetry.apply(s, m / side, m % side, side)
                r * side + c in mineSet
            }
        }
        // RandomPlacer sorts by style score and uses the random key only as a tie-break, so a styled
        // search is nearly deterministic. Each slot therefore takes the best of several random
        // balanced layouts under the admiral's style: random enough to differ, biased enough to show the style.
        val draws = if (style == PlacementStyle.BALANCED) 1 else STYLE_DRAWS
        val seen = HashSet<String>()
        val out = mutableListOf<List<Placement>>()
        var draw = 0L
        while (out.size < count) {
            if (draw > count * TRIES_PER_LAYOUT * draws) error("cannot place $count distinct layouts of ${fleet.name} (tried $draw times)")
            var best: List<Placement>? = null
            var bestScore = Long.MIN_VALUE
            repeat(draws) {
                val rng = SplitMix64(SplitMix64.split(seed, draw++))
                val result = RandomPlacer.place(board, fleet, rng, PlacementStyle.BALANCED, shipsMayTouch = false)
                if (result is PlacementResult.Success) {
                    val score = styleScore(result.placements, board, style)
                    if (best == null || score > bestScore) {
                        best = result.placements
                        bestScore = score
                    }
                }
            }
            val chosen = best ?: continue
            if (seen.add(canonicalKey(chosen, side, usable))) out += sorted(chosen)
        }
        return out
    }

    /** How well a layout follows [style] (same terms as RandomPlacer, measured on the finished layout). */
    private fun styleScore(placements: List<Placement>, board: Board, style: PlacementStyle): Long {
        val side = board.width
        val owner = HashMap<Int, Int>()
        placements.forEachIndexed { k, placement -> placement.cells().forEach { owner[it.row * side + it.col] = k } }
        var edges = 0L
        var centre = 0L
        var coast = 0L
        var nearby = 0L
        for ((index, k) in owner) {
            val r = index / side
            val c = index % side
            if (r == 0 || c == 0 || r == side - 1 || c == side - 1) edges++
            centre -= (Math.abs(2 * r - (side - 1)) + Math.abs(2 * c - (side - 1))).toLong()
            var touchesLand = false
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val rr = r + dr
                val cc = c + dc
                if (rr !in 0 until side || cc !in 0 until side) continue
                val neighbour = rr * side + cc
                if (board.terrain[neighbour] != Terrain.WATER) touchesLand = true
                val other = owner[neighbour]
                if (other != null && other != k) nearby++
            }
            if (touchesLand) coast++
        }
        return style.edges * edges + style.centre * centre + style.nearIslands * coast -
            style.spread * nearby + style.cluster * nearby
    }

    /** Layout as content: placements sorted by ship order, row and column. */
    fun toDto(layout: List<Placement>): List<PlacementDto> = sorted(layout).map {
        PlacementDto(it.shipClass, it.origin.row, it.origin.col, it.rotationIndex)
    }

    /**
     * Mines of the enemy sea, drawn before the layouts: the layouts are then placed around
     * them, and RandomPlacer never puts a ship on a mine or next to one. (Drawing mines after the
     * layouts is impossible: 16 to 32 layouts together cover the whole sea, so no water square would
     * be left away from every ship of every layout.)
     */
    fun mines(board: Board, count: Int, rng: SplitMix64): List<Cell> {
        val side = board.width
        val candidates = (0 until side * side).filter { i -> board.terrain[i] == Terrain.WATER }.toMutableList()
        val chosen = mutableListOf<Int>()
        for (i in 0 until minOf(count, candidates.size)) {
            val j = i + rng.nextInt(candidates.size - i)
            val tmp = candidates[i]
            candidates[i] = candidates[j]
            candidates[j] = tmp
            chosen += candidates[i]
        }
        return chosen.sorted().map { Cell(it / side, it % side) }
    }

    private fun sorted(placements: List<Placement>): List<Placement> = placements.sortedWith(
        compareBy<Placement> { it.shipClass.ordinal }.thenBy { it.origin.row }.thenBy { it.origin.col }.thenBy { it.rotationIndex },
    )

    /** Smallest text over the usable symmetries of the occupied squares with their ship classes. */
    private fun canonicalKey(placements: List<Placement>, side: Int, usable: List<Int>): String {
        var best: String? = null
        for (s in usable) {
            val text = placements.flatMap { placement ->
                placement.cells().map { cell ->
                    val (r, c) = Symmetry.apply(s, cell.row, cell.col, side)
                    "${placement.shipClass.name}@$r,$c"
                }
            }.sorted().joinToString(";")
            if (best == null || text < best) best = text
        }
        return best!!
    }
}

/** Layout pool document for a variant: its fleet, terrain and layouts. */
internal fun layoutPoolDto(
    poolId: String,
    fleet: String,
    terrainId: String?,
    terrainHash: String?,
    layouts: List<List<Placement>>,
): LayoutPoolDto = LayoutPoolDto(
    id = poolId,
    fleet = fleet,
    terrain = terrainId,
    terrainHash = terrainHash,
    layouts = layouts.map { LayoutPools.toDto(it) },
)
