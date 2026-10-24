package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet

/**
 * Fixed decision cases: case `i` is a real battle of the engine after `1 + i % 40` random shots,
 * decided by the admiral `Admirals.ALL[i % size]`. The same index gives the same case for every tier, so the
 * golden moves and the on-device timings use one definition.
 */
object DecisionCases {

    /** One decision: the view of the side to act and the admiral who decides. */
    class Case internal constructor(val index: Int, val view: AiView, val profile: AdmiralProfile) {

        /** The move of [tier] for this case; its rng depends on the index and the tier only. */
        fun decide(tier: AiTier): AiIntent {
            val rng = SplitMix64(SplitMix64.split(index.toLong(), tier.ordinal.toLong()))
            return AdmiralBrain.decide(view, profile, tier, rng)
        }
    }

    /** Case [index] on a board described by [rules] (the golden moves use the default 10×10 rules). */
    fun build(index: Int, rules: RuleSet = RuleSet()): Case {
        val view = Scenarios.viewOfMover(Scenarios.midBattle(SplitMix64.split(0x60D3L, index.toLong()), shots = 1 + index % 40, rules = rules))
        return Case(index, view, Admirals.ALL[index % Admirals.ALL.size])
    }
}
