package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.meta.Seas

class CampaignRulesTest {

    /** A profile where [missions] have the given stars in [difficulty]. */
    private fun profileWith(
        difficulty: Difficulty = Difficulty.MEDIUM,
        stars: Map<Int, Int> = emptyMap(),
        unlockedSea: Int = 1,
    ): Profile = Profile(
        variants = stars.mapKeys { (mission, _) ->
            variantKey(Seas.missionId(CampaignRules.seaOf(mission), mission), difficulty)
        }.mapValues { (_, s) -> VariantProgress(stars = s) },
        unlockedSeas = Difficulty.entries.associateWith { if (it == difficulty) unlockedSea else 1 },
    )

    @Test
    fun seaAndBossArithmetic() {
        assertEquals(1, CampaignRules.seaOf(1))
        assertEquals(1, CampaignRules.seaOf(50))
        assertEquals(2, CampaignRules.seaOf(51))
        assertEquals(50, CampaignRules.bossOf(1))
        assertEquals(600, CampaignRules.bossOf(12))
        assertTrue(CampaignRules.isBoss(150))
        assertFalse(CampaignRules.isBoss(151))
        assertEquals(101, CampaignRules.firstMissionOf(3))
    }

    @Test
    fun firstMissionOfTheFirstSeaIsAlwaysUnlocked() {
        val profile = profileWith()
        assertTrue(CampaignRules.isUnlocked(profile, 1, Difficulty.MEDIUM))
        assertFalse(CampaignRules.isUnlocked(profile, 2, Difficulty.MEDIUM))
    }

    @Test
    fun oneStarUnlocksTheNextMissionOfTheSameDifficultyOnly() {
        val profile = profileWith(stars = mapOf(1 to 1))
        assertTrue(CampaignRules.isUnlocked(profile, 2, Difficulty.MEDIUM))
        assertFalse(CampaignRules.isUnlocked(profile, 2, Difficulty.HARD))
        assertFalse(CampaignRules.isUnlocked(profile, 3, Difficulty.MEDIUM))
    }

    @Test
    fun zeroStarsDoNotUnlockTheNextMission() {
        val profile = Profile(variants = mapOf(variantKey("s01m001", Difficulty.EASY) to VariantProgress(stars = 0)))
        assertFalse(CampaignRules.isUnlocked(profile, 2, Difficulty.EASY))
    }

    @Test
    fun missionsOfLockedSeasStayLocked() {
        val profile = profileWith(unlockedSea = 1)
        assertFalse(CampaignRules.isUnlocked(profile, 51, Difficulty.MEDIUM))
        assertFalse(CampaignRules.isUnlocked(profile, 0, Difficulty.MEDIUM))
        assertFalse(CampaignRules.isUnlocked(profile, 601, Difficulty.MEDIUM))
    }

    @Test
    fun currentMissionIsTheFirstUnlockedMissionWithoutStars() {
        assertEquals(1, CampaignRules.currentMission(Profile(), Difficulty.EASY))
        val profile = profileWith(stars = mapOf(1 to 3, 2 to 1, 3 to 0))
        assertEquals(3, CampaignRules.currentMission(profile, Difficulty.MEDIUM))
    }

    @Test
    fun currentMissionIsTheBossWhenTheSeaIsOtherwiseDone() {
        val stars = (1..49).associateWith { 2 }
        val profile = profileWith(stars = stars)
        assertEquals(50, CampaignRules.currentMission(profile, Difficulty.MEDIUM))
    }

    @Test
    fun bossWinUnlocksTheNextSeaInThatDifficultyOnly() {
        // Sea 1 is complete (all missions with a star, boss included): the boss of sea 1 was won.
        val profile = profileWith(stars = (1..50).associateWith { 1 })
        val after = profile.copy(unlockedSeas = profile.unlockedSeas + (Difficulty.MEDIUM to 2))
        assertTrue(CampaignRules.isUnlocked(after, 51, Difficulty.MEDIUM))
        assertFalse(CampaignRules.isUnlocked(after, 51, Difficulty.EASY))
        assertEquals(51, CampaignRules.currentMission(after, Difficulty.MEDIUM))
    }

    @Test
    fun seaUnlockedByCapsAtTheLastSea() {
        assertEquals(2, CampaignRules.seaUnlockedBy(1))
        assertEquals(12, CampaignRules.seaUnlockedBy(12))
    }

    @Test
    fun seaStarsAndClearedFollowTheBoss() {
        val profile = profileWith(stars = mapOf(1 to 3, 2 to 2, 50 to 1))
        assertEquals(6, CampaignRules.seaStars(profile, 1, Difficulty.MEDIUM))
        assertTrue(CampaignRules.seaCleared(profile, 1, Difficulty.MEDIUM))
        assertFalse(CampaignRules.seaCleared(profile, 1, Difficulty.HARD))
        assertEquals(0, CampaignRules.seaStars(profile, 2, Difficulty.MEDIUM))
    }

    @Test
    fun noMissionIsCurrentWhenEveryUnlockedMissionIsComplete() {
        val stars = (1..CampaignRules.TOTAL_MISSIONS).associateWith { 1 }
        val profile = profileWith(stars = stars, unlockedSea = 12)
        assertNull(CampaignRules.currentMission(profile, Difficulty.MEDIUM))
    }

    @Test
    fun starsAreClampedToTheRange() {
        val profile = Profile(variants = mapOf(variantKey("s01m001", Difficulty.HARD) to VariantProgress(stars = 9)))
        assertEquals(3, CampaignRules.stars(profile, 1, Difficulty.HARD))
    }
}
