package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.meta.MissionRef
import pt.tiago.nightfleet.meta.Seas

/**
 * Pure campaign rules over [Profile]. No Android, no I/O.
 *
 * Mission numbers are campaign ordinals (1..600); mission N x 50 is the boss of sea N. A mission is
 * unlocked when it is the first mission of an unlocked sea, or when the previous mission of the same
 * sea has at least one star in the same difficulty. Beating the boss of a sea (one star or more)
 * unlocks the next sea in that difficulty only. The three difficulties progress separately.
 */
object CampaignRules {
    const val SEAS = Seas.COUNT
    const val MISSIONS_PER_SEA = Seas.MISSIONS_PER_SEA
    const val TOTAL_MISSIONS = SEAS * MISSIONS_PER_SEA

    fun seaOf(mission: Int): Int = (mission - 1) / MISSIONS_PER_SEA + 1

    fun isBoss(mission: Int): Boolean = mission % MISSIONS_PER_SEA == 0

    /** First mission of [sea] (1-based). */
    fun firstMissionOf(sea: Int): Int = (sea - 1) * MISSIONS_PER_SEA + 1

    /** Boss mission of [sea] (1-based). */
    fun bossOf(sea: Int): Int = sea * MISSIONS_PER_SEA

    fun ref(mission: Int): MissionRef = MissionRef(seaOf(mission), mission)

    /** Highest sea unlocked in [difficulty] (1..12). */
    fun unlockedSea(profile: Profile, difficulty: Difficulty): Int =
        (profile.unlockedSeas[difficulty] ?: 1).coerceIn(1, SEAS)

    /** Stars of one variant (0..3). Missing variants have none. */
    fun stars(profile: Profile, mission: Int, difficulty: Difficulty): Int {
        if (mission !in 1..TOTAL_MISSIONS) return 0
        val id = Seas.missionId(seaOf(mission), mission)
        return profile.variants[variantKey(id, difficulty)]?.stars?.coerceIn(0, 3) ?: 0
    }

    /** Whether [mission] can be played in [difficulty]. */
    fun isUnlocked(profile: Profile, mission: Int, difficulty: Difficulty): Boolean {
        if (mission !in 1..TOTAL_MISSIONS) return false
        val sea = seaOf(mission)
        if (sea > unlockedSea(profile, difficulty)) return false
        if (mission == firstMissionOf(sea)) return true
        return stars(profile, mission - 1, difficulty) >= 1
    }

    /**
     * The mission to play next in [difficulty]: the first unlocked mission without stars. Null when
     * every unlocked mission is complete (the last sea is cleared).
     */
    fun currentMission(profile: Profile, difficulty: Difficulty): Int? {
        val last = unlockedSea(profile, difficulty) * MISSIONS_PER_SEA
        return (1..last).firstOrNull { stars(profile, it, difficulty) == 0 }
    }

    /** Sum of the stars of [sea] in [difficulty] (0..150). */
    fun seaStars(profile: Profile, sea: Int, difficulty: Difficulty): Int =
        (firstMissionOf(sea)..bossOf(sea)).sumOf { stars(profile, it, difficulty) }

    /** A sea is cleared in a difficulty when its boss has at least one star there. */
    fun seaCleared(profile: Profile, sea: Int, difficulty: Difficulty): Boolean =
        stars(profile, bossOf(sea), difficulty) >= 1

    /** The sea that the boss of [sea] unlocks, capped at the last sea. */
    fun seaUnlockedBy(sea: Int): Int = (sea + 1).coerceAtMost(SEAS)
}
