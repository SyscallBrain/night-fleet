package pt.tiago.nightfleet.game.battle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.DifficultyStats
import pt.tiago.nightfleet.data.HEATMAP_CELLS
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.ui.board.BoardRenderModel

/**
 * Pure rules that turn a finished quick battle into the profile statistics and the logbook entry
 *. The stored result is idempotent: [ProfileStore.commitResult] ignores a
 * match id it has already committed.
 */
object BattleRecords {

    /** Adds the battle to the statistics of [QuickBattle.DIFFICULTY]. [shotCells] are the player's shots, row-major. */
    fun withQuickBattle(profile: Profile, summary: QuickBattle.Summary, shotCells: List<Int>, width: Int): Profile =
        withBattle(profile, QuickBattle.DIFFICULTY, summary, shotCells, width)

    /**
     * Adds one finished battle of any mode to the statistics of [difficulty]: the battle and the win, shots and hits,
     * the longest run of hits, the ships sunk by class, the player's weapons, the time on screen and the shot map.
     * [shotCells] are the player's shots, row-major on a [width] by [width] sea.
     */
    fun withBattle(
        profile: Profile,
        difficulty: Difficulty,
        summary: QuickBattle.Summary,
        shotCells: List<Int>,
        width: Int,
        weaponsUsed: Map<String, Int> = emptyMap(),
        playSeconds: Long = 0,
    ): Profile {
        val current = profile.stats[difficulty] ?: DifficultyStats()
        val heatmap = current.shotHeatmap.toMutableList()
        while (heatmap.size < HEATMAP_CELLS) heatmap += 0
        for (cell in shotCells) {
            val slot = QuickBattle.heatmapIndex(cell, width)
            heatmap[slot] = heatmap[slot] + 1
        }
        val sunk = current.shipsSunkByClass.toMutableMap()
        for ((name, count) in summary.sunkByClass) sunk[name] = (sunk[name] ?: 0) + count
        val weapons = current.weaponsUsed.toMutableMap()
        for ((name, count) in weaponsUsed) weapons[name] = (weapons[name] ?: 0) + count
        val updated = current.copy(
            battles = current.battles + 1,
            wins = current.wins + if (summary.won) 1 else 0,
            shots = current.shots + summary.shots,
            hits = current.hits + summary.hits,
            longestHitStreak = maxOf(current.longestHitStreak, summary.longestHitStreak),
            shipsSunkByClass = sunk,
            weaponsUsed = weapons,
            playTimeSeconds = current.playTimeSeconds + playSeconds.coerceAtLeast(0),
            shotHeatmap = heatmap.take(HEATMAP_CELLS),
        )
        return profile.copy(stats = profile.stats + (difficulty to updated))
    }

    /** Counts one solved puzzle, and its time on screen, in the statistics of [difficulty]. */
    fun withPuzzleSolved(profile: Profile, difficulty: Difficulty, playSeconds: Long = 0): Profile {
        val current = profile.stats[difficulty] ?: DifficultyStats()
        val updated = current.copy(
            puzzlesSolved = current.puzzlesSolved + 1,
            playTimeSeconds = current.playTimeSeconds + playSeconds.coerceAtLeast(0),
        )
        return profile.copy(stats = profile.stats + (difficulty to updated))
    }

    /** The logbook entry of a finished battle: final maps for the list, and the replay payload (seed + actions). */
    fun logEntry(
        matchId: String,
        attemptSeed: Long,
        placements: List<Placement>,
        match: BattleMatch,
        summary: QuickBattle.Summary,
        ownMap: BoardRenderModel,
        enemyMap: BoardRenderModel,
        nowMillis: Long,
        setup: MatchSetup? = null,
        kind: MatchKind = MatchKind.FREE_PLAY,
        difficulty: Difficulty = QuickBattle.DIFFICULTY,
    ): LogEntry = LogEntry(
        id = matchId,
        epochMillis = nowMillis,
        kind = kind,
        difficulty = difficulty,
        result = if (summary.won) LogResult.WIN else LogResult.LOSS,
        stars = summary.stars,
        usedHint = summary.usedHint,
        shots = summary.shots,
        hits = summary.hits,
        boardWidth = match.width,
        boardHeight = match.width,
        finalMapPlayer = ObservationMapper.finalMapCode(ownMap),
        finalMapEnemy = ObservationMapper.finalMapCode(enemyMap),
        replay = replayPayload(attemptSeed, placements, match.actions, setup),
    )

    /**
     * The replay payload of a battle: the seed, the player's placements, every action in play
     * order and, for a battle built from a setup (free play), the resolved setup. [MatchReplay] rebuilds it.
     */
    fun replayPayload(
        attemptSeed: Long,
        placements: List<Placement>,
        actions: List<Action>,
        setup: MatchSetup?,
    ): JsonObject = JsonObject(
        buildMap {
            put("seeds", JsonArray(listOf(JsonPrimitive(attemptSeed.toString()))))
            put("placements", JsonArray(PlacementCodec.encodeAll(placements).map { JsonPrimitive(it) }))
            put("actions", JsonArray(actions.map { JsonPrimitive(BattleCodec.encodeAction(it)) }))
            if (setup != null) put("setup", JsonPrimitive(SetupSave.encodeSetup(setup)))
        },
    )
}
