package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.random.SplitMix64

/** Sampled fleets: [freq] counts, per unknown square, the weight of the samples that cover it. */
internal class SampledFleets(
    val freq: LongArray,
    val samples: Int,
    val nodes: Int,
    val fleets: List<List<Placement>>,
)

/**
 * Samples complete enemy fleets consistent with the view (RELENTLESS) by randomized
 * backtracking. Each sample is a full fleet that satisfies every constraint of [Knowledge] exactly,
 * including the sonar and recon readings. The search has a node budget; if it runs out before the
 * requested number of samples, [sample] returns null and the caller falls back to the density.
 *
 * Weak water is allowed with weight 1 instead of 4 (weak evidence counts 1/4).
 */
internal object FleetSampler {
    const val SAMPLES = 4_000

    /** Ships placed in the search tree over all samples of one decision. */
    const val NODE_BUDGET = 400_000

    fun sample(
        k: Knowledge,
        rng: SplitMix64,
        samples: Int = SAMPLES,
        budget: Int = NODE_BUDGET,
        keepFleets: Boolean = false,
    ): SampledFleets? {
        val order = k.view.enemyRemaining
            .flatMap { entry -> List(entry.count) { entry.shipClass } }
            .sortedWith(compareBy({ -it.size }, { it.ordinal }))
        val shapes = order.map { Geometry.of(k.w, k.h, it) }
        val candidates = order.mapIndexed { i, cls ->
            shapes[i].squares.indices.filter { k.candidate(cls.submersible, shapes[i].squares[it], weakExcluded = false) }
                .toIntArray()
        }
        if (candidates.any { it.isEmpty() }) return null
        return Run(k, order, shapes.map { it.placements }, shapes.map { it.squares }, candidates, rng, samples, budget, keepFleets)
            .run()
    }

    private class Run(
        private val k: Knowledge,
        private val order: List<ShipClass>,
        private val placements: List<List<Placement>>,
        private val squares: List<Array<IntArray>>,
        private val candidates: List<IntArray>,
        private val rng: SplitMix64,
        private val wanted: Int,
        private val budget: Int,
        private val keepFleets: Boolean,
    ) {
        private val n = k.n
        private val occupied = BooleanArray(n)
        private val surface = BooleanArray(n)
        private val block = IntArray(n)
        private val chosen = IntArray(order.size)

        /** Squares still to be placed from each depth on: the upper bound for the open must-cover squares. */
        private val remaining = IntArray(order.size + 1).also { r ->
            for (d in order.indices.reversed()) r[d] = r[d + 1] + order[d].size
        }
        private val freq = LongArray(n)
        private val fleets = ArrayList<List<Placement>>()
        private var nodes = 0
        private var got = 0
        private var aborted = false

        /**
         * One depth-first enumeration of consistent fleets, each visited once. The result is used only when
         * the enumeration is COMPLETE: the whole tree was searched within the budget and there are fewer than
         * [wanted] fleets. Then every fleet has the same weight and the frequencies are exact. A partial
         * enumeration (budget hit or cap reached) is biased by the search order, so it gives null and the
         * caller falls back to the density. A view with no consistent fleet also gives null.
         */
        fun run(): SampledFleets? {
            place(0)
            if (aborted || got == 0 || got >= wanted) return null
            return SampledFleets(freq, got, nodes, fleets)
        }

        /** Returns true when the enumeration must stop (enough fleets, or the budget ran out). */
        private fun place(depth: Int): Boolean {
            if (depth == order.size) {
                if (leaf()) got++
                return got >= wanted
            }
            val cand = candidates[depth]
            val shape = squares[depth]
            val submersible = order[depth].submersible
            val start = rng.nextInt(cand.size)
            for (j in cand.indices) {
                val p = cand[(start + j) % cand.size]
                val sq = shape[p]
                if (sq.any { clashes(it) }) continue
                // A node is a ship placed in the search tree. Scanning rejected candidates is not counted.
                nodes++
                if (nodes > budget) {
                    aborted = true
                    return false
                }
                mark(sq, submersible, +1)
                chosen[depth] = p
                val stop = viable(depth + 1) && place(depth + 1)
                mark(sq, submersible, -1)
                if (stop) return true
                if (aborted) return false
            }
            return false
        }

        /**
         * Forward check, without counting nodes: the open must-cover squares must fit in the squares left,
         * and the next ship must still have a placement that does not clash.
         */
        private fun viable(depth: Int): Boolean {
            if (depth >= order.size) return true
            var open = 0
            for (x in k.mustList) if (!occupied[x]) open++
            if (open > remaining[depth]) return false
            return candidates[depth].any { p -> squares[depth][p].none { clashes(it) } }
        }

        private fun clashes(x: Int): Boolean = if (k.shipsMayTouch) occupied[x] else block[x] > 0

        private fun mark(sq: IntArray, submersible: Boolean, delta: Int) {
            for (x in sq) {
                occupied[x] = delta > 0
                if (!submersible) surface[x] = delta > 0
                for (y in k.nb8[x]) block[y] += delta
                block[x] += delta
            }
        }

        /** Exact check of the complete fleet; on success it records the sample. */
        private fun leaf(): Boolean {
            for (x in k.mustList) if (!occupied[x]) return false
            for (s in k.sonar) {
                var count = 0
                for (x in s.box) if (occupied[x] && !k.hit[x]) count++
                if (count != s.count) return false
            }
            for (r in k.recon) {
                var anyAll = false
                var anySurface = false
                for (x in r.line) {
                    if (k.hit[x]) continue
                    if (occupied[x]) anyAll = true
                    if (surface[x]) anySurface = true
                }
                val ok = if (k.diving) {
                    if (r.present) anyAll else !anySurface
                } else {
                    r.present == anyAll
                }
                if (!ok) return false
            }
            // Uniform weight: a complete enumeration is exact, so every consistent fleet counts once.
            for (x in 0 until n) if (occupied[x] && k.unknown[x]) freq[x] += 1L
            if (keepFleets) fleets += order.indices.map { placements[it][chosen[it]] }
            return true
        }
    }
}
