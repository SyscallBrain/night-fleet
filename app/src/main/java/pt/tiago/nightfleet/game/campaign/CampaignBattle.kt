package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.data.BossSeries
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.variantKey

/**
 * What a battle of the campaign is for: the mission, the difficulty and the thresholds that turn the
 * battle into stars. Carried by the battle view model so that its result is committed as campaign
 * progress and not as a free play game.
 */
data class CampaignStake(
    val missionId: String,
    val difficulty: Difficulty,
    val kind: MissionKind,
    val thresholds: StarsDto?,
    /** Admiral of the sea (the opponent of the battle). */
    val admiralId: String?,
    /** The boss series this duel belongs to, as it stands before the duel. Null outside a boss. */
    val series: BossSeries? = null,
) {
    /** A boss duel: its result counts as one duel of a series, see [BossProgress]. */
    val isBossDuel: Boolean get() = kind == MissionKind.BOSS
}

/**
 * The boss series of the campaign: best of three duels. The
 * series is kept in the profile after every duel, so it survives a restart. A series that is decided
 * is committed as the boss result (stars from [CampaignScoring.bossStars]) and removed.
 *
 * A duel that ends in a turn-limit draw is replayed once (the series keeps the same duel). A second draw
 * in the same duel counts as a loss for the player.
 */
object BossProgress {

    /** Series so far in [difficulty] of the boss [missionId], or null when none is running. */
    fun running(profile: Profile, missionId: String, difficulty: Difficulty): BossSeries? =
        profile.bossSeries[variantKey(missionId, difficulty)]

    /**
     * Records the end of one duel attempt. [won] and [draw] are the result of the attempt, [shots] the player's
     * shots and [usedHint] whether the player used the officer's hint in it. Returns the profile with the series
     * updated; when the series is decided, the boss variant is committed (stars, unlock of the next sea, defeated
     * admiral) and the series is removed.
     */
    fun withDuel(
        profile: Profile,
        stake: CampaignStake,
        won: Boolean,
        shots: Int,
        draw: Boolean = false,
        usedHint: Boolean = false,
    ): Profile {
        val key = variantKey(stake.missionId, stake.difficulty)
        val series = profile.bossSeries[key] ?: BossSeries()
        if (draw && !series.replayPending) {
            // The first draw of this duel: nothing is decided, the duel is replayed once.
            return profile.copy(bossSeries = profile.bossSeries + (key to series.copy(replayPending = true)))
        }
        val updated = advance(series, won, draw, shots, usedHint)
        if (!isDecided(updated)) return profile.copy(bossSeries = profile.bossSeries + (key to updated))
        val outcome = CampaignOutcome(
            missionId = stake.missionId,
            difficulty = stake.difficulty,
            won = updated.wins >= 2,
            shots = shots,
            usedHint = updated.hintsPerDuel.any { it },
            thresholds = stake.thresholds,
            admiralId = stake.admiralId,
            stars = stars(updated, stake.thresholds),
        )
        return CampaignProgress.withResult(profile.copy(bossSeries = profile.bossSeries - key), outcome)
    }

    /**
     * The series after one finished duel attempt. A second draw in the same duel (a replay that ends in a draw too)
     * is a loss for the player, so [draw] only counts as a draw when it is not the replay.
     */
    fun advance(series: BossSeries, won: Boolean, draw: Boolean, shots: Int, usedHint: Boolean): BossSeries {
        val playerWon = won && !draw
        return series.copy(
            wins = series.wins + if (playerWon) 1 else 0,
            losses = series.losses + if (playerWon) 0 else 1,
            shotsPerDuel = series.shotsPerDuel + shots,
            hintsPerDuel = series.hintsPerDuel + usedHint,
            replayPending = false,
        )
    }

    /** A series is decided when one side has two duels. */
    fun isDecided(series: BossSeries): Boolean = series.wins >= 2 || series.losses >= 2

    /** Stars of a decided series: each duel is rated with the thresholds of the boss variant. */
    fun stars(series: BossSeries, thresholds: StarsDto?): Int {
        val duelStars = series.shotsPerDuel.mapIndexed { index, duelShots ->
            CampaignScoring.stars(true, duelShots, thresholds, series.hintsPerDuel.getOrElse(index) { false })
        }
        return CampaignScoring.bossStars(series.wins, series.losses, duelStars)
    }
}
