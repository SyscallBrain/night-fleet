package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.Ranks

/**
 * What the rank badge draws: enlisted ranks get chevrons,
 * officers get bars, flag officers get stars, and the Grand Admiral gets stars inside a laurel.
 */
data class Insignia(val chevrons: Int, val bars: Int, val stars: Int, val laurel: Boolean)

fun insigniaFor(rank: Rank): Insignia = when (rank) {
    Rank.CABIN_BOY, Rank.SEAMAN, Rank.LEADING_SEAMAN, Rank.PETTY_OFFICER ->
        Insignia(chevrons = rank.ordinal + 1, bars = 0, stars = 0, laurel = false)
    Rank.MIDSHIPMAN, Rank.ENSIGN, Rank.SUB_LIEUTENANT, Rank.LIEUTENANT, Rank.LIEUTENANT_COMMANDER, Rank.COMMANDER ->
        Insignia(chevrons = 0, bars = rank.ordinal - 3, stars = 0, laurel = false)
    Rank.CAPTAIN, Rank.REAR_ADMIRAL, Rank.VICE_ADMIRAL, Rank.ADMIRAL ->
        Insignia(chevrons = 0, bars = 0, stars = rank.ordinal - 9, laurel = false)
    Rank.GRAND_ADMIRAL -> Insignia(chevrons = 0, bars = 0, stars = 5, laurel = true)
}

/**
 * The rank card: where the player stands and what is missing for the next rank. Stars count
 * across the three difficulties; the Grand Admiral also needs the twelve HARD bosses.
 */
data class RankProgress(
    val rank: Rank,
    val totalStars: Int,
    /** The next rank, or null for the Grand Admiral. */
    val next: Rank?,
    /** Stars still missing for [next]; zero when only the bosses are missing. */
    val starsToNext: Int,
    val hardBossesWon: Int,
    val hardBossesNeeded: Int,
    /** 0..1 progress towards [next]. */
    val fraction: Float,
) {
    /** True while the next step is only the HARD bosses (the player is an Admiral with all stars). */
    val needsBossesOnly: Boolean get() = next == Rank.GRAND_ADMIRAL && starsToNext == 0
}

fun rankProgress(totalStars: Int, hardBossesWon: Int): RankProgress {
    val stars = totalStars.coerceAtLeast(0)
    val bosses = hardBossesWon.coerceIn(0, Ranks.GRAND_ADMIRAL_HARD_BOSSES)
    val needed = Ranks.GRAND_ADMIRAL_HARD_BOSSES
    val rank = Ranks.rankFor(stars, bosses)
    if (rank == Rank.GRAND_ADMIRAL) {
        return RankProgress(rank, stars, null, 0, bosses, needed, 1f)
    }
    if (rank == Rank.ADMIRAL) {
        // Every star is in, so the only step left is the bosses on HARD.
        return RankProgress(rank, stars, Rank.GRAND_ADMIRAL, 0, bosses, needed, bosses.toFloat() / needed)
    }
    val next = Rank.entries[rank.ordinal + 1]
    val span = (next.minStars - rank.minStars).coerceAtLeast(1)
    val fraction = ((stars - rank.minStars).toFloat() / span).coerceIn(0f, 1f)
    return RankProgress(rank, stars, next, (next.minStars - stars).coerceAtLeast(0), bosses, needed, fraction)
}
