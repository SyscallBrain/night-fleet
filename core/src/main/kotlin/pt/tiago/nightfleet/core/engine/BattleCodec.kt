package pt.tiago.nightfleet.core.engine

import kotlinx.serialization.json.Json

/** JSON encoding of states and actions. Round trips keep the state hash (see StateHasher). */
object BattleCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encodeState(state: BattleState): String = json.encodeToString(BattleState.serializer(), state)

    fun decodeState(text: String): BattleState = json.decodeFromString(BattleState.serializer(), text)

    fun encodeAction(action: Action): String = json.encodeToString(Action.serializer(), action)

    fun decodeAction(text: String): Action = json.decodeFromString(Action.serializer(), text)
}
