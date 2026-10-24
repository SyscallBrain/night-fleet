package pt.tiago.nightfleet.game.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.DAILY_RETENTION_DAYS
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.withDailyResult
import java.time.LocalDate

/** Daily history is keyed by the challenge date: a clock change never moves or loses a result. */
class DailyHistoryTest {

    private val day = LocalDate.of(2026, 10, 22)

    @Test
    fun aResultIsStoredUnderItsChallengeDate() {
        val profile = Profile().withDailyResult(day, Difficulty.MEDIUM, ChallengeKind.HUNT, 2)
        assertEquals(setOf("2026-10-22"), profile.dailyHistory.keys)
        assertEquals(2, DailyChallenge.bestStars(profile, day, Difficulty.MEDIUM, ChallengeKind.HUNT))
    }

    @Test
    fun theBestStarsOfTheDayAreKept() {
        val profile = Profile()
            .withDailyResult(day, Difficulty.HARD, ChallengeKind.PUZZLE, 3)
            .withDailyResult(day, Difficulty.HARD, ChallengeKind.PUZZLE, 1)
        assertEquals(3, DailyChallenge.bestStars(profile, day, Difficulty.HARD, ChallengeKind.PUZZLE))
    }

    @Test
    fun aClockSetBackOneDayDoesNotOverwriteTheRecordedDay() {
        // The phone clock says the 21st again: the result of the 22nd stays under the 22nd.
        val recorded = Profile().withDailyResult(day, Difficulty.EASY, ChallengeKind.HUNT, 3)
        val afterClockChange = recorded.withDailyResult(day.minusDays(1), Difficulty.EASY, ChallengeKind.HUNT, 1)
        assertEquals(3, DailyChallenge.bestStars(afterClockChange, day, Difficulty.EASY, ChallengeKind.HUNT))
        assertEquals(1, DailyChallenge.bestStars(afterClockChange, day.minusDays(1), Difficulty.EASY, ChallengeKind.HUNT))
    }

    @Test
    fun resultsOutsideTheRetentionWindowArePruned() {
        val old = day.minusDays(DAILY_RETENTION_DAYS.toLong())
        val profile = Profile()
            .withDailyResult(old, Difficulty.EASY, ChallengeKind.PUZZLE, 2)
            .withDailyResult(day, Difficulty.EASY, ChallengeKind.PUZZLE, 1)
        assertFalse(profile.dailyHistory.containsKey(old.toString()))
        assertTrue(profile.dailyHistory.containsKey(day.toString()))
    }

    @Test
    fun theStripShowsTheRecordedDaysOnly() {
        val profile = Profile()
            .withDailyResult(day.minusDays(2), Difficulty.MEDIUM, ChallengeKind.PUZZLE, 2)
            .withDailyResult(day, Difficulty.MEDIUM, ChallengeKind.HUNT, 1)
        val strip = DailyChallenge.strip(profile, day, Difficulty.MEDIUM)
        assertEquals(2, strip[strip.size - 3].puzzle)
        assertEquals(0, strip[strip.size - 2].puzzle)
        assertEquals(1, strip.last().hunt)
    }
}
