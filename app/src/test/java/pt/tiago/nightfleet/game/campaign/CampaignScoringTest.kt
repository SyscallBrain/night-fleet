package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.variantKey

class CampaignScoringTest {

    private val thresholds = StarsDto(two = 58, three = 46)

    private fun outcome(
        missionId: String = "s01m010",
        won: Boolean = true,
        shots: Int = 50,
        usedHint: Boolean = false,
        stars: Int? = null,
        admiralId: String? = null,
    ) = CampaignOutcome(
        missionId = missionId,
        difficulty = Difficulty.MEDIUM,
        won = won,
        shots = shots,
        usedHint = usedHint,
        thresholds = thresholds,
        admiralId = admiralId,
        stars = stars,
    )

    @Test
    fun aLossGivesNoStars() {
        assertEquals(0, CampaignScoring.stars(won = false, shots = 1, thresholds = thresholds, usedHint = false))
    }

    @Test
    fun winsAreScoredByShotThresholds() {
        assertEquals(3, CampaignScoring.stars(true, 46, thresholds, false))
        assertEquals(2, CampaignScoring.stars(true, 47, thresholds, false))
        assertEquals(2, CampaignScoring.stars(true, 58, thresholds, false))
        assertEquals(1, CampaignScoring.stars(true, 59, thresholds, false))
    }

    @Test
    fun anUncalibratedWinIsOneStar() {
        assertEquals(1, CampaignScoring.stars(true, 3, null, false))
    }

    @Test
    fun usingTheHintCapsTheResultAtTwoStars() {
        assertEquals(2, CampaignScoring.stars(true, 10, thresholds, true))
        assertEquals(1, CampaignScoring.stars(true, 70, thresholds, true))
    }

    @Test
    fun bossSeriesStars() {
        assertEquals(0, CampaignScoring.bossStars(1, 2, listOf(0, 0)))
        assertEquals(1, CampaignScoring.bossStars(2, 1, listOf(3, 0, 3)))
        assertEquals(2, CampaignScoring.bossStars(2, 0, listOf(3, 2)))
        assertEquals(3, CampaignScoring.bossStars(2, 0, listOf(3, 3)))
    }

    @Test
    fun hintsPerDifficulty() {
        assertEquals(3, CampaignScoring.hintsFor(Difficulty.EASY))
        assertEquals(2, CampaignScoring.hintsFor(Difficulty.MEDIUM))
        assertEquals(1, CampaignScoring.hintsFor(Difficulty.HARD))
    }

    @Test
    fun starsOnlyGoUpAndBestShotsOnlyGoDown() {
        val first = CampaignProgress.withResult(Profile(), outcome(shots = 50))
        val second = CampaignProgress.withResult(first, outcome(shots = 40, won = true))
        val third = CampaignProgress.withResult(second, outcome(shots = 80, won = false))
        val key = variantKey("s01m010", Difficulty.MEDIUM)
        assertEquals(VariantProgress(stars = 3, bestShots = 40, wins = 2, losses = 1), third.variants[key])
    }

    @Test
    fun aWorseResultDoesNotLowerStars() {
        val strong = CampaignProgress.withResult(Profile(), outcome(shots = 10))
        val weak = CampaignProgress.withResult(strong, outcome(shots = 90))
        assertEquals(3, weak.variants[variantKey("s01m010", Difficulty.MEDIUM)]!!.stars)
    }

    @Test
    fun aBossWinUnlocksTheNextSeaAndRecordsTheAdmiral() {
        val profile = CampaignProgress.withResult(
            Profile(),
            outcome(missionId = "s01m050", stars = 2, admiralId = "iris"),
        )
        assertEquals(2, profile.unlockedSeas[Difficulty.MEDIUM])
        assertEquals(setOf("iris"), profile.defeatedAdmirals[Difficulty.MEDIUM])
        assertEquals(1, profile.unlockedSeas[Difficulty.EASY])
    }

    @Test
    fun aBossLossUnlocksNothing() {
        val profile = CampaignProgress.withResult(Profile(), outcome(missionId = "s01m050", won = false, admiralId = "iris"))
        assertEquals(1, profile.unlockedSeas[Difficulty.MEDIUM])
        assertNull(profile.defeatedAdmirals[Difficulty.MEDIUM])
    }

    @Test
    fun aNormalWinDoesNotTouchSeaUnlocks() {
        val profile = CampaignProgress.withResult(Profile(), outcome(missionId = "s01m010"))
        assertEquals(1, profile.unlockedSeas[Difficulty.MEDIUM])
    }

    @Test
    fun startingAnAttemptCountsIt() {
        val (once, first) = CampaignProgress.startAttempt(Profile(), "s01m001", Difficulty.EASY)
        val (twice, second) = CampaignProgress.startAttempt(once, "s01m001", Difficulty.EASY)
        assertEquals(0L, first)
        assertEquals(1L, second)
        assertEquals(2L, twice.variants[variantKey("s01m001", Difficulty.EASY)]!!.attemptIndex)
        assertTrue(twice.variants.size == 1)
        assertFalse(twice.variants.containsKey(variantKey("s01m001", Difficulty.HARD)))
    }
}
