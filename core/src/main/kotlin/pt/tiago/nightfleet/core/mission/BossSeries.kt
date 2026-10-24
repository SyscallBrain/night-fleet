package pt.tiago.nightfleet.core.mission

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.engine.BattleResult

/** Result of one duel of a boss series, as the series records it. */
@Serializable
enum class DuelOutcome {
    PLAYER_WON,
    ADMIRAL_WON,
    DRAW,
    ;

    companion object {
        /** Maps the end of a DUEL (side 0 is the player) to an outcome. */
        fun of(result: BattleResult): DuelOutcome = when (result.winner) {
            0 -> PLAYER_WON
            1 -> ADMIRAL_WON
            else -> DRAW
        }
    }
}

/** One played duel of a series: [duel] is its index, [attempt] 1 or 2 (2 is the replay after a draw). */
@Serializable
data class DuelRecord(val duel: Int, val attempt: Int, val outcome: DuelOutcome)

@Serializable
data class BossScore(val playerWins: Int, val admiralWins: Int, val draws: Int)

@Serializable
enum class SeriesStatus { IN_PROGRESS, WON, LOST }

/**
 * Pure state machine of a BOSS series: best of three duels against the admiral of the
 * sea. The starting player alternates from duel to duel. A draw replays the duel once; a second
 * draw in the same duel counts as an admiral win. Every method returns a new value.
 */
@Serializable
data class BossSeries(
    val seriesSeed: Long,
    val records: List<DuelRecord> = emptyList(),
) {
    /** Verdicts: one per duel decided so far. A draw that is not replayed yet has no verdict. */
    val verdicts: List<DuelOutcome>
        get() = records
            .filter { it.outcome != DuelOutcome.DRAW || it.attempt == 2 }
            .map { if (it.outcome == DuelOutcome.DRAW) DuelOutcome.ADMIRAL_WON else it.outcome }

    /** Index of the duel being played (the number of decided duels). */
    val duelIndex: Int get() = verdicts.size

    /** 2 when the current duel is the replay of a draw, otherwise 1. */
    val attempt: Int
        get() = if (records.lastOrNull()?.let { it.outcome == DuelOutcome.DRAW && it.attempt == 1 } == true) 2 else 1

    val score: BossScore
        get() = BossScore(
            playerWins = verdicts.count { it == DuelOutcome.PLAYER_WON },
            admiralWins = verdicts.count { it == DuelOutcome.ADMIRAL_WON },
            draws = records.count { it.outcome == DuelOutcome.DRAW },
        )

    val status: SeriesStatus
        get() = when {
            score.playerWins >= 2 -> SeriesStatus.WON
            score.admiralWins >= 2 -> SeriesStatus.LOST
            else -> SeriesStatus.IN_PROGRESS
        }

    /** Side that starts the current duel: the player (0) in duels 0 and 2, the admiral (1) in duel 1. */
    val startingPlayer: Int get() = duelIndex % 2

    /** Seed of the setup of the current duel attempt. Replays get their own seed. */
    fun nextSetupSeed(): Long = SeedStreams.attemptSeed(seriesSeed, duelIndex * 2 + attempt - 1)

    /** Records the outcome of the current duel attempt. Fails when the series is already decided. */
    fun record(outcome: DuelOutcome): BossSeries {
        check(status == SeriesStatus.IN_PROGRESS) { "The boss series is already decided" }
        return copy(records = records + DuelRecord(duelIndex, attempt, outcome))
    }
}
