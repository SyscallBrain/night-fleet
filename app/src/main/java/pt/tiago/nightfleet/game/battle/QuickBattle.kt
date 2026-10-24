package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.engine.hitsForPrecision
import pt.tiago.nightfleet.core.engine.shotsForStars
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.HEATMAP_SIDE
import pt.tiago.nightfleet.data.Profile

/**
 * The rules shared by every battle: the end-of-battle summary, the stars of a plain duel and the shot map index.
 * The quick battle itself (a classic 10 × 10 duel against Coral) is no longer reachable from Home.
 */
object QuickBattle {

    /** Fixed level seed; each attempt derives its own seed from the attempt counter. */
    const val LEVEL_SEED: Long = 0x4E16_B47E_0000_0006L

    /** The quick battle's admiral. Plays on MEDIUM. */
    const val ADMIRAL_ID = "coral"

    /** The provisional admiral's name, shown in the log and the turn pill. */
    const val ADMIRAL_NAME = "Coral"

    /** Difficulty whose statistics record the quick battles. */
    val DIFFICULTY = Difficulty.MEDIUM

    /** Star thresholds of a battle without a certificate; campaign variants use their own. */
    const val THREE_STAR_MAX_SHOTS = 46
    const val TWO_STAR_MAX_SHOTS = 58

    fun rules(): RuleSet = RuleSet()

    /** Attempt counter: the number of quick battles already recorded at [DIFFICULTY]. */
    fun attemptIndex(profile: Profile): Int = profile.stats[DIFFICULTY]?.battles ?: 0

    fun attemptSeed(profile: Profile): Long = SeedStreams.attemptSeed(LEVEL_SEED, attemptIndex(profile))

    /** Stars of a won battle: 3 up to [THREE_STAR_MAX_SHOTS] shots, 2 up to [TWO_STAR_MAX_SHOTS], else 1. */
    fun stars(won: Boolean, shots: Int): Int = when {
        !won -> 0
        shots <= THREE_STAR_MAX_SHOTS -> 3
        shots <= TWO_STAR_MAX_SHOTS -> 2
        else -> 1
    }

    /** Shots needed for the next star, or null when the next star is out of reach (three stars or a loss). */
    fun nextStarShots(stars: Int): Int? = when (stars) {
        0 -> null
        1 -> TWO_STAR_MAX_SHOTS
        2 -> THREE_STAR_MAX_SHOTS
        else -> null
    }

    /** Hits as a whole percentage of shots, rounded half up. Zero shots give zero. */
    fun precision(hits: Int, shots: Int): Int = if (shots <= 0) 0 else (hits * 100 + shots / 2) / shots

    /** Result of a finished battle, read from the engine state. The player is side 0. */
    data class Summary(
        val won: Boolean,
        val reason: EndReason,
        val shots: Int,
        val hits: Int,
        val precision: Int,
        val stars: Int,
        val enemyShipsSunk: Int,
        val enemyShipsTotal: Int,
        val ownShipsSunk: Int,
        val longestHitStreak: Int,
        /** Enemy ships sunk by the player, per class name. */
        val sunkByClass: Map<String, Int>,
        /** The officer's hint was used: the stars are capped (see [OfficerHints.capStars]). */
        val usedHint: Boolean = false,
    )

    fun summarize(state: BattleState): Summary? {
        val result: BattleResult = state.result ?: return null
        val enemy = state.sides[1]
        val own = state.sides[0]
        val playerRecords = enemy.incoming.entries.filter { it.attacker == 0 }
        val shots = shotsForStars(state, 0)
        val hits = hitsForPrecision(state, 0)
        var streak = 0
        var longest = 0
        for (record in playerRecords) {
            val isHit = record.result is ShotResult.Hit ||
                record.result is ShotResult.Sunk
            streak = if (isHit) streak + 1 else 0
            longest = maxOf(longest, streak)
        }
        val sunk = enemy.ships.filter { it.sunk }
        val won = result.winner == 0
        return Summary(
            won = won,
            reason = result.reason,
            shots = shots,
            hits = hits,
            precision = precision(hits, shots),
            stars = stars(won, shots),
            enemyShipsSunk = sunk.size,
            enemyShipsTotal = enemy.ships.size,
            ownShipsSunk = own.ships.count { it.sunk },
            longestHitStreak = longest,
            sunkByClass = sunk.groupingBy { it.shipClass.name }.eachCount(),
        )
    }

    /** Index of the shot on the 10 × 10 heatmap for a square of a [width] by [width] board. */
    fun heatmapIndex(index: Int, width: Int): Int {
        // Seas are square: an 8 x 8 or 12 x 12 shot lands on the matching part of the 10 x 10 map, so a 12 x 12
        // column 11 does not wrap onto the next row.
        val row = (index / width) * HEATMAP_SIDE / width
        val col = (index % width) * HEATMAP_SIDE / width
        return (row * HEATMAP_SIDE + col).coerceIn(0, HEATMAP_SIDE * HEATMAP_SIDE - 1)
    }

    /** Class names are the stable keys of the per-class statistics. */
    fun classKey(shipClass: ShipClass): String = shipClass.name
}
