package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.DEFAULT_PAINT
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Ranks
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.hardBossesWon
import pt.tiago.nightfleet.data.rank

/** A fleet paint: changes only the hull colours and a pattern, never the hit or miss marks. */
data class Paint(
    val id: String,
    val kind: Kind,
    /** Admiral id for a boss paint. */
    val admiralId: String? = null,
    /** Rank for a rank paint. */
    val rank: Rank? = null,
) {
    enum class Kind { DEFAULT, BOSS, RANK }
}

/**
 * The fleet paints: one per boss defeated on any difficulty (12) and four per rank (4),
 * sixteen in total. The default paint is always available. The Grand Admiral finish is a separate
 * unlock: twelve bosses won on HARD.
 */
object PaintCatalog {
    const val DEFAULT_ID = DEFAULT_PAINT

    /** Ranks that unlock a paint. Spread over the progression. */
    val RANK_PAINTS: List<Rank> = listOf(Rank.MIDSHIPMAN, Rank.LIEUTENANT, Rank.CAPTAIN, Rank.ADMIRAL)

    val defaultPaint = Paint(DEFAULT_ID, Paint.Kind.DEFAULT)

    val bossPaints: List<Paint> = Admirals.all.map { Paint(bossPaintId(it.id), Paint.Kind.BOSS, admiralId = it.id) }

    val rankPaints: List<Paint> = RANK_PAINTS.map { Paint(rankPaintId(it), Paint.Kind.RANK, rank = it) }

    /** Every paint of the catalog, the default one first. Sixteen paints plus the default. */
    val all: List<Paint> = listOf(defaultPaint) + bossPaints + rankPaints

    /** The number of paints a player can unlock (the default is not counted). */
    val unlockable: Int = bossPaints.size + rankPaints.size

    fun bossPaintId(admiralId: String): String = "boss-$admiralId"

    fun rankPaintId(rank: Rank): String = "rank-${rank.name.lowercase()}"

    fun find(id: String): Paint? = all.firstOrNull { it.id == id }

    /** Ids of every paint unlocked by [profile]. The default paint is always included. */
    fun unlockedIds(profile: Profile): Set<String> {
        val defeated = profile.defeatedAdmirals.values.flatten().toSet()
        val bosses = bossPaints.filter { it.admiralId in defeated }.map { it.id }
        val rank = profile.rank()
        val ranks = rankPaints.filter { rank.ordinal >= it.rank!!.ordinal }.map { it.id }
        return (listOf(DEFAULT_ID) + bosses + ranks).toSet()
    }

    fun isUnlocked(profile: Profile, paintId: String): Boolean = paintId in unlockedIds(profile)

    /** The gold finish needs the twelve bosses won on HARD. Stars do not count. */
    fun grandAdmiralFinishUnlocked(profile: Profile): Boolean =
        profile.hardBossesWon() >= Ranks.GRAND_ADMIRAL_HARD_BOSSES

    /** How many of the bosses on HARD are still missing for the gold finish, never below zero. */
    fun hardBossesMissing(profile: Profile): Int =
        (Ranks.GRAND_ADMIRAL_HARD_BOSSES - profile.hardBossesWon()).coerceAtLeast(0)
}
