package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.rank
import pt.tiago.nightfleet.data.totalStars

/** What the Home screen shows of the campaign, computed from the profile. */
data class CampaignHome(
    val difficulty: Difficulty,
    /** Sea of the current mission, or the last sea when the campaign is complete in this difficulty. */
    val sea: Int,
    /** The mission to play next, or null when every sea is complete in this difficulty. */
    val currentMission: Int?,
    /** Stars of the sea (0..150). */
    val seaStars: Int,
    /** Stars of all seas and difficulties together (0..5 400). */
    val totalStars: Int,
    val rank: Rank,
    /** Boss mission of [sea]. */
    val bossMission: Int,
) {
    /**
     * The nine missions of the route card around the current one, clipped to the sea. Returns the first
     * mission of the route and the index of the current one inside it.
     */
    fun routeStart(): Int {
        val first = CampaignRules.firstMissionOf(sea)
        val last = CampaignRules.bossOf(sea)
        val center = currentMission ?: last
        return (center - ROUTE_NODES / 2).coerceIn(first, maxOf(first, last - ROUTE_NODES + 1))
    }

    companion object {
        const val ROUTE_NODES = 9
    }
}

/** Builds the Home summary of [difficulty]. Pure: the same profile always gives the same summary. */
fun campaignHome(profile: Profile, difficulty: Difficulty): CampaignHome {
    val current = CampaignRules.currentMission(profile, difficulty)
    val sea = current?.let(CampaignRules::seaOf) ?: CampaignRules.SEAS
    return CampaignHome(
        difficulty = difficulty,
        sea = sea,
        currentMission = current,
        seaStars = CampaignRules.seaStars(profile, sea, difficulty),
        totalStars = profile.totalStars(),
        rank = profile.rank(),
        bossMission = CampaignRules.bossOf(sea),
    )
}
