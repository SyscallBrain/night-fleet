package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.meta.Seas

/** The result of one campaign battle (or of a whole boss series), as the stars and the profile need it. */
data class CampaignOutcome(
    /** Mission id, `s03m112`. */
    val missionId: String,
    val difficulty: Difficulty,
    val won: Boolean,
    val shots: Int,
    val usedHint: Boolean,
    /** Star thresholds of the variant. Null when the variant is not calibrated: a win is then one star. */
    val thresholds: StarsDto?,
    /** Admiral of the sea, recorded as defeated when a boss is won. */
    val admiralId: String? = null,
    /** Stars already decided by the caller (boss series). Null means "compute them from the battle". */
    val stars: Int? = null,
)

/** Stars rules. Pure. */
object CampaignScoring {

    /**
     * Stars of a single battle. A win is one star; at or under `two` shots, two; at or under `three`, three.
     * Using the hint caps the result at two stars.
     */
    fun stars(won: Boolean, shots: Int, thresholds: StarsDto?, usedHint: Boolean): Int {
        if (!won) return 0
        if (thresholds == null) return 1
        var stars = 1
        if (shots <= thresholds.two) stars = 2
        if (shots <= thresholds.three) stars = 3
        return if (usedHint) minOf(stars, 2) else stars
    }

    /**
     * Stars of a convoy escort: a win is one star, a win with all the freighters afloat is two,
     * and all the freighters afloat with the enemy fleet sunk is three. Using the hint caps the result at two stars.
     */
    fun convoyStars(won: Boolean, allFreightersAfloat: Boolean, enemyFleetSunk: Boolean, usedHint: Boolean): Int {
        if (!won) return 0
        var stars = 1
        if (allFreightersAfloat) stars = 2
        if (allFreightersAfloat && enemyFleetSunk) stars = 3
        return if (usedHint) minOf(stars, 2) else stars
    }

    /**
     * Stars of a boss series: 1 for winning the series (two duels), 2 for winning 2-0, 3 for 2-0 with
     * both duels at three stars. [duelStars] holds the stars of each duel that was played.
     */
    fun bossStars(playerWins: Int, admiralWins: Int, duelStars: List<Int>): Int {
        if (playerWins < 2) return 0
        if (admiralWins > 0) return 1
        return if (duelStars.size == 2 && duelStars.all { it == 3 }) 3 else 2
    }

    /** Hints per difficulty: Easy 3, Medium 2, Hard 1. */
    fun hintsFor(difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> 3
        Difficulty.MEDIUM -> 2
        Difficulty.HARD -> 1
    }
}

/** Progress writes of the campaign. Each function returns a new profile and never touches the disk. */
object CampaignProgress {

    /**
     * Records the result of a battle. Stars only go up, the best shot count only goes down, wins and
     * losses count the battles, and a boss win unlocks the next sea in that difficulty.
     */
    fun withResult(profile: Profile, outcome: CampaignOutcome): Profile {
        val key = variantKey(outcome.missionId, outcome.difficulty)
        val old = profile.variants[key] ?: VariantProgress()
        val stars = outcome.stars ?: CampaignScoring.stars(outcome.won, outcome.shots, outcome.thresholds, outcome.usedHint)
        val updated = old.copy(
            stars = maxOf(old.stars, stars),
            bestShots = if (outcome.won) minOf(old.bestShots ?: outcome.shots, outcome.shots) else old.bestShots,
            wins = old.wins + if (outcome.won) 1 else 0,
            losses = old.losses + if (outcome.won) 0 else 1,
            usedHint = old.usedHint || outcome.usedHint,
        )
        var next = profile.copy(variants = profile.variants + (key to updated))
        val ref = Seas.parseMissionId(outcome.missionId)
        if (ref != null && CampaignRules.isBoss(ref.mission) && stars >= 1) {
            val unlocked = maxOf(CampaignRules.unlockedSea(profile, outcome.difficulty), CampaignRules.seaUnlockedBy(ref.sea))
            next = next.copy(unlockedSeas = next.unlockedSeas + (outcome.difficulty to unlocked))
            outcome.admiralId?.let { id ->
                val defeated = next.defeatedAdmirals[outcome.difficulty].orEmpty() + id
                next = next.copy(defeatedAdmirals = next.defeatedAdmirals + (outcome.difficulty to defeated))
            }
        }
        return next
    }

    /** Counts one start of a variant and returns the attempt index it uses. */
    fun startAttempt(profile: Profile, missionId: String, difficulty: Difficulty): Pair<Profile, Long> {
        val key = variantKey(missionId, difficulty)
        val old = profile.variants[key] ?: VariantProgress()
        return profile.copy(variants = profile.variants + (key to old.copy(attemptIndex = old.attemptIndex + 1))) to old.attemptIndex
    }
}
