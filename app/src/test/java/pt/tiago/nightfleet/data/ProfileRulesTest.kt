package pt.tiago.nightfleet.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileRulesTest {
    private val start: LocalDate = LocalDate.of(2026, 1, 1)

    @Test
    fun totalStarsSumsEveryVariantAcrossDifficulties() {
        val profile = Profile(
            variants = mapOf(
                variantKey("s01m001", Difficulty.EASY) to VariantProgress(stars = 3),
                variantKey("s01m001", Difficulty.HARD) to VariantProgress(stars = 3),
                variantKey("s01m002", Difficulty.MEDIUM) to VariantProgress(stars = 2),
            ),
        )
        assertEquals(8, profile.totalStars())
    }

    @Test
    fun rankIsDerivedFromStarsAndHardBosses() {
        val fullStars = (0 until 1_800).associate { "m$it:EASY" to VariantProgress(stars = 3) }
        val bosses = mapOf(Difficulty.HARD to (1..12).map { "boss$it" }.toSet())
        assertEquals(Rank.GRAND_ADMIRAL, Profile(variants = fullStars, defeatedAdmirals = bosses).rank())
        assertEquals(Rank.ADMIRAL, Profile(variants = fullStars).rank())
        assertEquals(12, Profile(defeatedAdmirals = bosses).hardBossesWon())
    }

    @Test
    fun dailyRecordKeepsTheBestStarsOfTheDay() {
        var profile = Profile()
        profile = profile.withDailyResult(start, Difficulty.EASY, ChallengeKind.PUZZLE, 1)
        profile = profile.withDailyResult(start, Difficulty.EASY, ChallengeKind.PUZZLE, 3)
        profile = profile.withDailyResult(start, Difficulty.EASY, ChallengeKind.PUZZLE, 2)
        profile = profile.withDailyResult(start, Difficulty.EASY, ChallengeKind.HUNT, 1)
        val day = profile.dailyHistory.getValue(start.toString())
        assertEquals(3, day.best.getValue(Difficulty.EASY).getValue(ChallengeKind.PUZZLE))
        assertEquals(1, day.best.getValue(Difficulty.EASY).getValue(ChallengeKind.HUNT))
    }

    @Test
    fun dailyHistoryKeepsExactlyTwoHundredSeventyDays() {
        var profile = Profile()
        for (offset in 0 until 300) {
            profile = profile.withDailyResult(start.plusDays(offset.toLong()), Difficulty.HARD, ChallengeKind.HUNT, 1)
        }
        assertEquals(DAILY_RETENTION_DAYS, profile.dailyHistory.size)
        assertFalse(profile.dailyHistory.containsKey(start.plusDays(29).toString()))
        assertTrue(profile.dailyHistory.containsKey(start.plusDays(30).toString()))
        assertTrue(profile.dailyHistory.containsKey(start.plusDays(299).toString()))
    }

    @Test
    fun dailyPruningIsRelativeToTheRecordedDay() {
        var profile = Profile().withDailyResult(start, Difficulty.EASY, ChallengeKind.PUZZLE, 1)
        // A result 400 days later prunes the first day, even though nothing was written in between.
        profile = profile.withDailyResult(start.plusDays(400), Difficulty.EASY, ChallengeKind.PUZZLE, 2)
        assertEquals(listOf(start.plusDays(400).toString()), profile.dailyHistory.keys.toList())
    }

    @Test
    fun resetCampaignClearsCampaignButKeepsFormationsStatsDailyAndAchievements() {
        val profile = Profile(
            variants = mapOf("s01m001:EASY" to VariantProgress(stars = 3)),
            unlockedSeas = mapOf(Difficulty.EASY to 5, Difficulty.MEDIUM to 2, Difficulty.HARD to 1),
            bossSeries = mapOf("s01boss:EASY" to BossSeries(wins = 1)),
            defeatedAdmirals = mapOf(Difficulty.EASY to setOf("coral")),
            metAdmirals = setOf("coral"),
            formations = mapOf("10x10:classic:abc" to listOf(Formation(emptyList()))),
            stats = mapOf(Difficulty.EASY to DifficultyStats(battles = 4, wins = 2)),
            dailyHistory = mapOf(start.toString() to DailyDay()),
            achievements = mapOf("first_win" to AchievementProgress(unlockedAtEpochDay = 20_000)),
            unlockedPaints = setOf("gold"),
            selectedPaint = "gold",
        )
        val reset = profile.resetCampaign()
        assertTrue(reset.variants.isEmpty())
        assertTrue(reset.bossSeries.isEmpty())
        assertTrue(reset.defeatedAdmirals.isEmpty())
        assertTrue(reset.metAdmirals.isEmpty())
        assertEquals(Difficulty.entries.associateWith { 1 }, reset.unlockedSeas)
        assertEquals(profile.formations, reset.formations)
        assertEquals(profile.stats, reset.stats)
        assertEquals(profile.dailyHistory, reset.dailyHistory)
        assertEquals(profile.achievements, reset.achievements)
        assertEquals("gold", reset.selectedPaint)
        assertNull(reset.variants["s01m001:EASY"])
    }
}
