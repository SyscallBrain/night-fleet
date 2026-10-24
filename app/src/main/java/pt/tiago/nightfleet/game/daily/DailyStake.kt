package pt.tiago.nightfleet.game.daily

import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.data.Difficulty

/**
 * What a daily hunt is for: the day and difficulty it belongs to, the thresholds that turn it into stars, and the id
 * of this play. The play id is unique per play (the day alone repeats), so every play is committed once, and it is
 * the id of its saved match. [dayKey] names the day for a resume; see [DailyChallenge.matchId].
 */
data class DailyStake(
    val date: java.time.LocalDate,
    val difficulty: Difficulty,
    val dayKey: String,
    val playId: String,
    val thresholds: StarsDto?,
)
