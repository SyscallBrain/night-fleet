package pt.tiago.nightfleet.game.replay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.PlacementCodec
import pt.tiago.nightfleet.game.battle.SetupSave

/**
 * A finished battle rebuilt from its logbook payload: the start state, every action in play
 * order and the state after each of them. Step 0 is the start; step t is the state after the t-th action, so
 * "previous" is the state at t - 1. Pure: the same payload always gives the same states and hashes.
 */
class MatchReplay private constructor(
    /** The attempt seed of the battle. */
    val seed: Long,
    /** The actions in play order. */
    val actions: List<Action>,
    private val states: List<BattleState>,
) {
    /** Number of actions, so the replay has `steps + 1` states. */
    val steps: Int get() = actions.size

    /** The state after [step] actions. */
    fun stateAt(step: Int): BattleState = states[step.coerceIn(0, steps)]

    /** The engine hash of the state after [step] actions. */
    fun hashAt(step: Int): Long = StateHasher.hash(stateAt(step))

    /** The state at the end of the battle. */
    val last: BattleState get() = states.last()

    companion object {
        /**
         * Rebuilds the battle of [payload]. A quick battle has the placements and the seed; a free play battle also
         * has its resolved setup under "setup". Null when the payload is damaged or an action is refused.
         */
        fun fromPayload(payload: JsonObject): MatchReplay? {
            val seed = payload.listAt("seeds")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.toLongOrNull()
                ?: return null
            val start = startState(payload, seed) ?: return null
            val actions = payload.listAt("actions")?.map { element ->
                val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
                runCatching { BattleCodec.decodeAction(text) }.getOrNull() ?: return null
            } ?: return null
            val states = ArrayList<BattleState>(actions.size + 1)
            states += start
            var match = BattleMatch(start)
            for (action in actions) {
                val step = match.apply(action)
                if (!step.accepted) return null
                match = step.match
                states += match.state
            }
            return MatchReplay(seed, actions, states)
        }

        private fun startState(payload: JsonObject, seed: Long): BattleState? {
            val setupText = payload.stringAt("setup")
            if (setupText != null) {
                val setup: MatchSetup = SetupSave.decodeSetup(setupText) ?: return null
                return (setup.create() as? SetupResult.Created)?.state
            }
            val placements = PlacementCodec.decodeAll(payload.listAt("placements")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null)
                ?: return null
            return (BattleMatch.create(placements, seed) as? SetupResult.Created)?.state
        }

        private fun JsonObject.stringAt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.listAt(key: String) = (this[key] as? JsonArray)?.toList()
    }
}

/** The squares an action shoots at: a fire, the squares of a salvo, or none. */
fun Action.shotCells(): List<Cell> = when (this) {
    is Action.Fire -> listOf(cell)
    is Action.Salvo -> cells
    else -> emptyList()
}
