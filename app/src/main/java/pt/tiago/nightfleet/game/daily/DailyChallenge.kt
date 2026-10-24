package pt.tiago.nightfleet.game.daily

import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyHuntDto
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.DailyIndex
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import java.time.LocalDate
import pt.tiago.nightfleet.core.content.Difficulty as ContentDifficulty

/**
 * The daily challenge. Pure: the same date, pool and difficulty always pick the
 * same entries. The day is the local date of the device; the history is keyed by that date, so a clock change
 * only moves the challenge that is offered, never the stored results.
 */
object DailyChallenge {

    /** Days shown in the strip of past results. */
    const val STRIP_DAYS = 14

    /** The pool entry of [kind] and [difficulty] for [date], or null when the pool has none of them. */
    fun pick(pool: DailyPoolDto, date: LocalDate, kind: DailyKind, difficulty: Difficulty): DailyEntryDto? {
        val entries = entries(pool, kind, difficulty)
        if (entries.isEmpty()) return null
        val index = DailyIndex.indexOf(date.toEpochDay(), pool.version, kind, difficulty.toContent(), entries.size)
        return entries[index]
    }

    /** The pool entries of one kind and difficulty, in the order of the pool file. */
    fun entries(pool: DailyPoolDto, kind: DailyKind, difficulty: Difficulty): List<DailyEntryDto> =
        pool.entries.filter { it.kind == kind && it.difficulty.name == difficulty.name }

    /** The hunt that [entry] refers to, or null when the hunts file does not have it. */
    fun hunt(hunts: DailyHuntsDto, entry: DailyEntryDto): DailyHuntDto? =
        hunts.hunts.firstOrNull { it.id == entry.ref && it.difficulty.name == entry.difficulty.name }

    /** Id of the saved match of a daily challenge: the date is part of it, so a resume never picks another day. */
    fun matchId(date: LocalDate, difficulty: Difficulty, kind: ChallengeKind): String =
        "daily-$date-${difficulty.name}-${kind.name}"

    /** Best stars of one challenge on [date] (0 when not played). */
    fun bestStars(profile: Profile, date: LocalDate, difficulty: Difficulty, kind: ChallengeKind): Int =
        profile.dailyHistory[date.toString()]?.best?.get(difficulty)?.get(kind) ?: 0

    /** The best stars of today's puzzle and hunt for one difficulty (0 when not played). */
    data class TodayStars(val puzzle: Int, val hunt: Int)

    fun today(profile: Profile, today: LocalDate, difficulty: Difficulty): TodayStars = TodayStars(
        puzzle = bestStars(profile, today, difficulty, ChallengeKind.PUZZLE),
        hunt = bestStars(profile, today, difficulty, ChallengeKind.HUNT),
    )

    /** Three glyphs: a filled star for each star won, an empty one for the rest. */
    fun starGlyphs(stars: Int): String {
        val won = stars.coerceIn(0, 3)
        return "★".repeat(won) + "☆".repeat(3 - won)
    }

    /** One day of the strip: the date and the best stars of each kind. */
    data class StripDay(val date: LocalDate, val puzzle: Int, val hunt: Int)

    /** The last [days] days up to [today], oldest first, for [difficulty]. */
    fun strip(profile: Profile, today: LocalDate, difficulty: Difficulty, days: Int = STRIP_DAYS): List<StripDay> =
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            StripDay(
                date = date,
                puzzle = bestStars(profile, date, difficulty, ChallengeKind.PUZZLE),
                hunt = bestStars(profile, date, difficulty, ChallengeKind.HUNT),
            )
        }

    private fun Difficulty.toContent(): ContentDifficulty = ContentDifficulty.valueOf(name)
}
