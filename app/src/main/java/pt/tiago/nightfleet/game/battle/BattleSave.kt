package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.SavedMatch

/** Outcome of [BattleSave.restore]. A discarded match carries a stable reason code for the log. */
sealed class RestoreResult {
    data class Restored(val match: BattleMatch, val attemptSeed: Long) : RestoreResult()
    data class Discarded(val reason: String) : RestoreResult()
}

/**
 * The save file of a quick battle. The payload holds the seed, the
 * player's placements, every accepted action and the state hash after the last one. Restoring replays
 * the actions through the engine from the same setup and compares the hash: a payload that was edited,
 * truncated or written by another engine version does not come back.
 */
object BattleSave {
    const val MATCH_ID_PREFIX = "qb-"

    fun matchIdOf(attemptSeed: Long): String = MATCH_ID_PREFIX + java.lang.Long.toHexString(attemptSeed).padStart(16, '0')

    fun toSaved(
        match: BattleMatch,
        attemptSeed: Long,
        playerPlacements: List<Placement>,
        nowMillis: Long,
    ): SavedMatch = SavedMatch(
        matchId = matchIdOf(attemptSeed),
        kind = MatchKind.FREE_PLAY,
        engineVersion = ENGINE_VERSION,
        difficulty = Difficulty.MEDIUM,
        seeds = listOf(attemptSeed),
        placements = PlacementCodec.encodeAll(playerPlacements),
        actions = match.actions.map(BattleCodec::encodeAction),
        expectedHash = StateHasher.hash(match.state),
        savedAtEpochMillis = nowMillis,
        hintsUsed = match.hintsUsed,
    )

    fun restore(saved: SavedMatch): RestoreResult {
        if (saved.kind != MatchKind.FREE_PLAY) return RestoreResult.Discarded("KIND")
        if (saved.engineVersion != ENGINE_VERSION) return RestoreResult.Discarded("ENGINE_VERSION")
        val attemptSeed = saved.seeds.singleOrNull() ?: return RestoreResult.Discarded("SEEDS")
        val placements = PlacementCodec.decodeAll(saved.placements)
            ?: return RestoreResult.Discarded("PLACEMENTS")
        val setup = BattleMatch.create(placements, attemptSeed)
        if (setup !is SetupResult.Created) return RestoreResult.Discarded("SETUP")
        var match = BattleMatch(setup.state)
        for (text in saved.actions) {
            val action: Action = runCatching { BattleCodec.decodeAction(text) }.getOrNull()
                ?: return RestoreResult.Discarded("ACTION_DECODE")
            val step = match.apply(action)
            if (!step.accepted) return RestoreResult.Discarded("ACTION_REFUSED")
            match = step.match
        }
        if (StateHasher.hash(match.state) != saved.expectedHash) return RestoreResult.Discarded("HASH_MISMATCH")
        return RestoreResult.Restored(match.copy(hintsUsed = saved.hintsUsed), attemptSeed)
    }
}

/**
 * Text form of a placement list: one string per ship, `CLASS row col rotationIndex`. The rotation is the
 * engine's rotation index, so a formation and a save share the same meaning.
 */
object PlacementCodec {
    private val LINE = Regex("([A-Z_]+) (\\d{1,2}) (\\d{1,2}) (\\d)")

    fun encodeAll(placements: List<Placement>): List<String> = placements.map { p ->
        "${p.shipClass.name} ${p.origin.row} ${p.origin.col} ${p.rotationIndex}"
    }

    /** Null when any line is malformed or names an unknown class. */
    fun decodeAll(lines: List<String>): List<Placement>? {
        val out = ArrayList<Placement>(lines.size)
        for (line in lines) {
            val match = LINE.matchEntire(line.trim()) ?: return null
            val shipClass = runCatching { ShipClass.valueOf(match.groupValues[1]) }.getOrNull() ?: return null
            out += Placement(
                shipClass = shipClass,
                origin = Cell(match.groupValues[2].toInt(), match.groupValues[3].toInt()),
                rotationIndex = match.groupValues[4].toInt(),
            )
        }
        return out
    }
}
