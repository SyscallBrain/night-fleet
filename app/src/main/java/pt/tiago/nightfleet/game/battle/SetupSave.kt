package pt.tiago.nightfleet.game.battle

import kotlinx.serialization.json.Json
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.data.BossSeries
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.SavedMatch

/**
 * Save file of a battle created from a [MatchSetup] (free play and the two-player duel). The setup is
 * stored resolved, so a restore replays the actions from the same fleets and checks the engine hash.
 */
object SetupSave {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Id of a free play match, from its attempt seed. The prefix keeps it apart from the quick battle ids. */
    fun freeMatchId(attemptSeed: Long): String = FREE_PREFIX + java.lang.Long.toHexString(attemptSeed).padStart(16, '0')

    const val FREE_PREFIX = "fp-"

    fun encodeSetup(setup: MatchSetup): String = json.encodeToString(MatchSetup.serializer(), setup)

    /** Null when the text is not a setup of this version. */
    fun decodeSetup(text: String): MatchSetup? = runCatching {
        json.decodeFromString(MatchSetup.serializer(), text)
    }.getOrNull()

    /** The save of [match], played from [setup], in the slot of [kind]. */
    fun toSaved(
        kind: MatchKind,
        matchId: String,
        setup: MatchSetup,
        match: BattleMatch,
        nowMillis: Long,
        difficulty: Difficulty? = null,
        missionId: String? = null,
        bossSeries: BossSeries? = null,
    ): SavedMatch = SavedMatch(
        matchId = matchId,
        kind = kind,
        engineVersion = ENGINE_VERSION,
        missionId = missionId,
        difficulty = difficulty,
        admiralId = setup.opponentId,
        seeds = listOf(setup.attemptSeed),
        actions = match.actions.map(BattleCodec::encodeAction),
        expectedHash = StateHasher.hash(match.state),
        savedAtEpochMillis = nowMillis,
        setup = encodeSetup(setup),
        hintsUsed = match.hintsUsed,
        bossSeries = bossSeries,
    )

    /** Rebuilds the match of a save that carries a setup. Anything wrong discards it with a stable reason. */
    fun restore(saved: SavedMatch): RestoreResult {
        if (saved.engineVersion != ENGINE_VERSION) return RestoreResult.Discarded("ENGINE_VERSION")
        val text = saved.setup ?: return RestoreResult.Discarded("NO_SETUP")
        val setup = decodeSetup(text) ?: return RestoreResult.Discarded("SETUP_DECODE")
        val created = setup.create()
        if (created !is SetupResult.Created) return RestoreResult.Discarded("SETUP")
        var match = BattleMatch(created.state)
        for (line in saved.actions) {
            val action: Action = runCatching { BattleCodec.decodeAction(line) }.getOrNull()
                ?: return RestoreResult.Discarded("ACTION_DECODE")
            val step = match.apply(action)
            if (!step.accepted) return RestoreResult.Discarded("ACTION_REFUSED")
            match = step.match
        }
        if (StateHasher.hash(match.state) != saved.expectedHash) return RestoreResult.Discarded("HASH_MISMATCH")
        return RestoreResult.Restored(match.copy(hintsUsed = saved.hintsUsed), setup.attemptSeed)
    }
}
