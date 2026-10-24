package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.variantKey

class BossAndHomeTest {

    private val thresholds = StarsDto(two = 58, three = 46)

    private fun stake(difficulty: Difficulty = Difficulty.MEDIUM) = CampaignStake(
        missionId = "s01m050",
        difficulty = difficulty,
        kind = MissionKind.BOSS,
        thresholds = thresholds,
        admiralId = "iris",
    )

    @Test
    fun aBossSeriesIsKeptBetweenDuelsAndCommittedWhenDecided() {
        val afterFirst = BossProgress.withDuel(Profile(), stake(), won = true, shots = 40)
        val running = BossProgress.running(afterFirst, "s01m050", Difficulty.MEDIUM)
        assertEquals(1, running?.wins)
        assertEquals(listOf(40), running?.shotsPerDuel)
        assertNull(afterFirst.variants[variantKey("s01m050", Difficulty.MEDIUM)])

        val afterSecond = BossProgress.withDuel(afterFirst, stake(), won = true, shots = 44)
        assertNull(BossProgress.running(afterSecond, "s01m050", Difficulty.MEDIUM))
        // Two wins, both at three stars: the series is worth three stars and unlocks sea 2 in Medium.
        assertEquals(3, afterSecond.variants[variantKey("s01m050", Difficulty.MEDIUM)]?.stars)
        assertEquals(2, afterSecond.unlockedSeas[Difficulty.MEDIUM])
        assertEquals(setOf("iris"), afterSecond.defeatedAdmirals[Difficulty.MEDIUM])
    }

    @Test
    fun aLostSeriesGivesNoStarsAndUnlocksNothing() {
        val lost1 = BossProgress.withDuel(Profile(), stake(), won = false, shots = 70)
        val lost2 = BossProgress.withDuel(lost1, stake(), won = false, shots = 80)
        assertNull(BossProgress.running(lost2, "s01m050", Difficulty.MEDIUM))
        assertEquals(0, lost2.variants[variantKey("s01m050", Difficulty.MEDIUM)]?.stars ?: 0)
        assertEquals(1, lost2.unlockedSeas[Difficulty.MEDIUM])
    }

    @Test
    fun aSplitSeriesIsWonWithOneStar() {
        val split1 = BossProgress.withDuel(Profile(), stake(), won = true, shots = 40)
        val split2 = BossProgress.withDuel(split1, stake(), won = false, shots = 90)
        val split3 = BossProgress.withDuel(split2, stake(), won = true, shots = 90)
        assertEquals(1, split3.variants[variantKey("s01m050", Difficulty.MEDIUM)]?.stars)
        assertEquals(2, split3.unlockedSeas[Difficulty.MEDIUM])
    }

    @Test
    fun homeFollowsTheCurrentMission() {
        val fresh = campaignHome(Profile(), Difficulty.EASY)
        assertEquals(1, fresh.sea)
        assertEquals(1, fresh.currentMission)
        assertEquals(50, fresh.bossMission)
        assertEquals(0, fresh.seaStars)
        assertEquals(1, fresh.routeStart())
    }

    @Test
    fun homeRouteStaysInsideTheSea() {
        val profile = Profile(
            variants = (1..30).associate { mission ->
                variantKey("s01m%03d".format(mission), Difficulty.HARD) to VariantProgress(stars = 2)
            },
        )
        val home = campaignHome(profile, Difficulty.HARD)
        assertEquals(31, home.currentMission)
        assertEquals(27, home.routeStart())
        assertTrue(home.routeStart() + CampaignHome.ROUTE_NODES - 1 <= home.bossMission)
    }

    @Test
    fun homeOfACompletedCampaignHasNoCurrentMission() {
        val profile = Profile(
            variants = (1..CampaignRules.TOTAL_MISSIONS).associate { mission ->
                variantKey("s%02dm%03d".format(CampaignRules.seaOf(mission), mission), Difficulty.EASY) to VariantProgress(stars = 1)
            },
            unlockedSeas = Difficulty.entries.associateWith { CampaignRules.SEAS },
        )
        val home = campaignHome(profile, Difficulty.EASY)
        assertNull(home.currentMission)
        assertEquals(12, home.sea)
    }
}
