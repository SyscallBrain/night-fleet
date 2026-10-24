package pt.tiago.nightfleet.game.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.game.campaign.CampaignOutcome
import pt.tiago.nightfleet.game.campaign.CampaignProgress
import pt.tiago.nightfleet.game.campaign.CampaignRules

/** Campaign puzzle missions commit their stars like battles: one star unlocks the next mission. */
class PuzzleStarsTest {

    @Test
    fun aPuzzleWithoutHelpIsThreeStars() {
        assertEquals(3, PuzzleStars.of(hintsUsed = 0, verifyUsed = false, errorsShown = 0))
    }

    @Test
    fun oneHelpCapsThePuzzleAtTwoStars() {
        assertEquals(2, PuzzleStars.of(hintsUsed = 0, verifyUsed = true, errorsShown = 2))
        assertEquals(2, PuzzleStars.of(hintsUsed = 1, verifyUsed = false, errorsShown = 0))
    }

    @Test
    fun moreThanOneHelpLeavesOneStar() {
        assertEquals(1, PuzzleStars.of(hintsUsed = 1, verifyUsed = true, errorsShown = 0))
        assertEquals(1, PuzzleStars.of(hintsUsed = 3, verifyUsed = true, errorsShown = 4))
    }

    @Test
    fun aSolvedPuzzleWithOneStarUnlocksTheNextMissionInThatDifficulty() {
        val mission = "s01m001"
        assertFalse(CampaignRules.isUnlocked(Profile(), 2, Difficulty.MEDIUM))
        val profile = CampaignProgress.withResult(
            Profile(),
            CampaignOutcome(
                missionId = mission,
                difficulty = Difficulty.MEDIUM,
                won = true,
                shots = 0,
                usedHint = true,
                thresholds = null,
                stars = PuzzleStars.of(hintsUsed = 2, verifyUsed = false, errorsShown = 0),
            ),
        )
        assertEquals(1, profile.variants.getValue(variantKey(mission, Difficulty.MEDIUM)).stars)
        assertTrue(CampaignRules.isUnlocked(profile, 2, Difficulty.MEDIUM))
        // The other difficulties progress separately.
        assertFalse(CampaignRules.isUnlocked(profile, 2, Difficulty.HARD))
    }
}
