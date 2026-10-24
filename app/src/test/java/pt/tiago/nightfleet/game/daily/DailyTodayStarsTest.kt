package pt.tiago.nightfleet.game.daily

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.withDailyResult
import java.time.LocalDate

class DailyTodayStarsTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 23)

    @Test
    fun aDayNotPlayedShowsNoStars() {
        assertEquals(DailyChallenge.TodayStars(0, 0), DailyChallenge.today(Profile(), today, Difficulty.MEDIUM))
    }

    @Test
    fun todayShowsTheBestStarsOfPuzzleAndHuntForTheDifficulty() {
        var profile = Profile()
        profile = profile.withDailyResult(today, Difficulty.MEDIUM, ChallengeKind.PUZZLE, 2)
        profile = profile.withDailyResult(today, Difficulty.MEDIUM, ChallengeKind.PUZZLE, 1)
        profile = profile.withDailyResult(today, Difficulty.MEDIUM, ChallengeKind.HUNT, 3)
        assertEquals(DailyChallenge.TodayStars(puzzle = 2, hunt = 3), DailyChallenge.today(profile, today, Difficulty.MEDIUM))
    }

    @Test
    fun otherDaysAndOtherDifficultiesDoNotCount() {
        var profile = Profile()
        profile = profile.withDailyResult(today.minusDays(1), Difficulty.MEDIUM, ChallengeKind.HUNT, 3)
        profile = profile.withDailyResult(today, Difficulty.HARD, ChallengeKind.PUZZLE, 3)
        assertEquals(DailyChallenge.TodayStars(0, 0), DailyChallenge.today(profile, today, Difficulty.MEDIUM))
    }

    @Test
    fun starGlyphsShowWonStarsFilledAndTheRestEmpty() {
        assertEquals("☆☆☆", DailyChallenge.starGlyphs(0))
        assertEquals("★☆☆", DailyChallenge.starGlyphs(1))
        assertEquals("★★☆", DailyChallenge.starGlyphs(2))
        assertEquals("★★★", DailyChallenge.starGlyphs(3))
    }

    @Test
    fun starGlyphsClampOutOfRangeCounts() {
        assertEquals("☆☆☆", DailyChallenge.starGlyphs(-2))
        assertEquals("★★★", DailyChallenge.starGlyphs(7))
    }
}
