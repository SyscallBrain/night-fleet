package pt.tiago.nightfleet.core.hash

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.RuleSet

/**
 * The scripted classic duel of the determinism golden: each side shoots the first unknown square in
 * row-major order and the first side times out on turn 5. Shared by the JVM test and the Android diagnostics,
 * so both check the same hash.
 */
object ScriptedDuel {

    /** Seed of the golden duel. */
    const val SEED = 2024L

    /** [StateHasher] of the golden duel at [SEED]. Changes only when [ENGINE_VERSION] changes. */
    const val GOLDEN_HASH = 5737748764626553192L

    /** Plays the scripted duel from [seed] to its end. Fails with [IllegalStateException] on an illegal action. */
    fun play(seed: Long): BattleState {
        val created = BattleSetup.create(RuleSet(), MissionType.DUEL, listOf(null, null), 0, seed)
        var state = (created as SetupResult.Created).state
        var turn = 0
        while (state.result == null) {
            val action: Action = if (turn == 5) {
                Action.Timeout
            } else {
                val observation = Observation.of(state, state.current)
                val index = observation.enemySea.indexOfFirst { it == KnownCell.UNKNOWN }
                Action.Fire(state.sides[1 - state.current].board.cell(index))
            }
            val out = Engine.apply(state, action)
            val illegal = out.events.filterIsInstance<Event.IllegalAction>().firstOrNull()
            check(illegal == null) { "scripted duel made an illegal action: ${illegal?.reason}" }
            state = out.state
            turn++
        }
        return state
    }
}
