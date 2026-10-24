package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * Test-only baseline brain (the app plays the admirals of core/ai, see [AdmiralOpponent]). A simple
 * parity hunter: it hunts on one checkerboard parity, then finishes every open hit by extending the line
 * the hits form. It only fires at squares that are still UNKNOWN in the observation.
 */
object ParityBrain : OpponentBrain {

    override fun decide(obs: Observation, rng: SplitMix64): Action {
        val index = adjacentToOpenHit(obs, rng) ?: hunt(obs, rng) ?: return Action.Timeout
        return Action.Fire(Cell(index / obs.width, index % obs.width))
    }

    /**
     * Unknown squares next to an open hit. A square scores the number of consecutive hits on each
     * axis through it, so the end of a line of two hits beats a side square. Ties are drawn from [rng].
     */
    private fun adjacentToOpenHit(obs: Observation, rng: SplitMix64): Int? {
        val known = obs.enemySea
        val width = obs.width
        val height = obs.height
        var best = 0
        val ties = ArrayList<Int>()
        for (index in known.indices) {
            if (known[index] != KnownCell.UNKNOWN) continue
            val row = index / width
            val col = index % width
            var score = 0
            for ((dr, dc) in AXES) {
                for (sign in SIGNS) {
                    var r = row + sign * dr
                    var c = col + sign * dc
                    while (r in 0 until height && c in 0 until width && known[r * width + c] == KnownCell.HIT) {
                        score++
                        r += sign * dr
                        c += sign * dc
                    }
                }
            }
            if (score == 0) continue
            if (score > best) {
                best = score
                ties.clear()
            }
            if (score == best) ties += index
        }
        if (ties.isEmpty()) return null
        return ties[rng.nextInt(ties.size)]
    }

    /** A random unknown square of the parity with more unknown squares (ties by [rng]). */
    private fun hunt(obs: Observation, rng: SplitMix64): Int? {
        val known = obs.enemySea
        val width = obs.width
        val even = ArrayList<Int>()
        val odd = ArrayList<Int>()
        for (index in known.indices) {
            if (known[index] != KnownCell.UNKNOWN) continue
            val parity = (index / width + index % width) % 2
            if (parity == 0) even += index else odd += index
        }
        val candidates = when {
            even.size > odd.size -> even
            odd.size > even.size -> odd
            even.isEmpty() -> return null
            else -> if (rng.nextInt(2) == 0) even else odd
        }
        if (candidates.isNotEmpty()) return candidates[rng.nextInt(candidates.size)]
        // The parity is exhausted: any unknown square is still a legal shot.
        val any = known.indices.filter { known[it] == KnownCell.UNKNOWN }
        return if (any.isEmpty()) null else any[rng.nextInt(any.size)]
    }

    private val AXES = listOf(0 to 1, 1 to 0)
    private val SIGNS = listOf(1, -1)
}
