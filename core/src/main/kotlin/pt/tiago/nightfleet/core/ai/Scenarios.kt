package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet

/** Real battles played with the engine to a chosen point, so the views are ones the game can produce. */
internal object Scenarios {

    /** A duel with hidden fleets from [seed], after [shots] random shots (each side fires at unknown squares). */
    fun midBattle(seed: Long, shots: Int, rules: RuleSet = RuleSet()): BattleState {
        val created = BattleSetup.create(
            rules = rules,
            mission = MissionType.DUEL,
            placements = listOf(null, null),
            firstPlayer = SeedStreams.firstPlayer(seed),
            attemptSeed = seed,
        )
        var state = (created as SetupResult.Created).state
        val rng = SplitMix64(seed xor 0x5DEECE66DL)
        repeat(shots) {
            if (state.result != null) return state
            val observation = Observation.of(state, state.current)
            val unknown = observation.enemySea.indices.filter { observation.enemySea[it] == KnownCell.UNKNOWN }
            val pick = unknown[rng.nextInt(unknown.size)]
            val cell = state.sides[1 - state.current].board.cell(pick)
            state = Engine.apply(state, Action.Fire(cell)).state
        }
        return state
    }

    /** The view of the side to act in [state]. */
    fun viewOfMover(state: BattleState): AiView = AiView.from(Observation.of(state, state.current))
}
