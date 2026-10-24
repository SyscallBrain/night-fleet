package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.engine.shotsForStars
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.RuleSet

/**
 * Plays whole battles between admirals with the engine. It is the one place where the
 * AI, the observation and the engine meet, so the leveltool and the tests share it. Every random choice
 * comes from [SeedStreams], so the same seed always gives the same battle and the same final hash.
 */
object BattleRunner {

    /** One side of a battle: who decides, with which profile and tier. */
    class Side(val ai: AdmiralAI, val profile: AdmiralProfile, val tier: AiTier)

    /**
     * Result of a battle. [shots] are the shots each side fired, [turns] the turns played and
     * [finalHash] the [StateHasher] value of the final state.
     */
    data class Outcome(
        val winner: Int?,
        val reason: EndReason,
        val shots: List<Int>,
        val turns: Int,
        val finalHash: Long,
    )

    private const val MAX_STEPS = 100_000

    /**
     * A duel between two sides. Fleets are drawn by [AdmiralPlacer] when [admiralLayouts] is true, and by
     * the engine's random placer otherwise. The first player comes from the seed.
     */
    fun duel(rules: RuleSet, seed: Long, sides: List<Side>, admiralLayouts: Boolean = false): Outcome {
        require(sides.size == 2) { "A duel has exactly two sides" }
        val placements: List<List<Placement>?> = if (admiralLayouts) {
            sides.mapIndexed { i, side -> layout(rules, seed, i, side.profile) }
        } else {
            listOf(null, null)
        }
        val state = create(rules, MissionType.DUEL, placements, SeedStreams.firstPlayer(seed), seed, null)
        return play(state, seed) { sides[it] }
    }

    /**
     * A hunt: side 0 fires alone at a random fleet within [budget] shots. A hunt always starts with side 0.
     */
    fun hunt(rules: RuleSet, seed: Long, shooter: Side, budget: Int = rules.width * rules.height): Outcome {
        val state = create(rules, MissionType.HUNT, listOf(null, null), 0, seed, budget)
        return play(state, seed) { shooter }
    }

    private fun layout(rules: RuleSet, seed: Long, side: Int, profile: AdmiralProfile): List<Placement> {
        val rng = SeedStreams.rng(seed, SeedStreams.Stream.LAYOUT, side)
        val result = AdmiralPlacer.place(rules.boardFor(side), rules.fleet, profile, rng, rules.shipsMayTouch)
        return (result as? PlacementResult.Success)?.placements
            ?: throw IllegalStateException("Admiral ${profile.id} could not place the fleet: $result")
    }

    private fun create(
        rules: RuleSet,
        mission: MissionType,
        placements: List<List<Placement>?>,
        firstPlayer: Int,
        seed: Long,
        budget: Int?,
    ): BattleState = when (val setup = BattleSetup.create(rules, mission, placements, firstPlayer, seed, budget)) {
        is SetupResult.Created -> setup.state
        is SetupResult.Rejected -> throw IllegalArgumentException("Battle rejected: ${setup.reasons}")
    }

    private fun play(start: BattleState, seed: Long, sideOf: (Int) -> Side): Outcome {
        val rngs = List(2) { SeedStreams.rng(seed, SeedStreams.Stream.AI, it) }
        var state = start
        var steps = 0
        while (state.result == null) {
            check(++steps <= MAX_STEPS) { "Battle did not end after $MAX_STEPS steps" }
            val actor = state.current
            val side = sideOf(actor)
            val view = AiView.from(Observation.of(state, actor))
            val intent = side.ai.decide(view, side.profile, side.tier, rngs[actor])
            val action = intent.toAction()
            val outcome = Engine.apply(state, action)
            outcome.events.filterIsInstance<Event.IllegalAction>().firstOrNull()?.let {
                throw IllegalStateException("Admiral proposed an illegal action: ${it.reason} for $intent")
            }
            state = outcome.state
        }
        val result = state.result
        val shots = (0..1).map { shotsForStars(state, it) }
        return Outcome(result.winner, result.reason, shots, state.turn, StateHasher.hash(state))
    }
}
