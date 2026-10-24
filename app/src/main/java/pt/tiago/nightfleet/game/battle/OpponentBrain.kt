package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * The admiral's decision maker. Its only input is the admiral's [Observation]: it never receives the
 * engine state, so it cannot read the hidden fleet. The admirals of core/ai plug in behind this interface
 * ([AdmiralOpponent]); the battle screen does not know which brain is playing.
 */
interface OpponentBrain {
    fun decide(obs: Observation, rng: SplitMix64): Action

    /**
     * A legal action for the turn when the engine refuses what [decide] proposed. The battle never stalls:
     * the default passes the turn with [Action.Timeout].
     */
    fun fallback(obs: Observation, rng: SplitMix64): Action = Action.Timeout
}
