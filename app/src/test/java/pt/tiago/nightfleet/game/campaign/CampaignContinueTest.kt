package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.SavedMatch

class CampaignContinueTest {

    private fun saved(kind: MatchKind = MatchKind.CAMPAIGN, mission: String? = "s02m056", difficulty: Difficulty? = Difficulty.HARD) =
        SavedMatch(matchId = "m", kind = kind, engineVersion = 1, missionId = mission, difficulty = difficulty)

    @Test
    fun withoutASavedPlayContinueOpensTheNextMissionSheet() {
        val target = CampaignContinue.target(Profile(), null, Difficulty.MEDIUM)
        assertEquals(CampaignContinue.Target.NextMission(1, Difficulty.MEDIUM), target)
    }

    @Test
    fun aSavedCampaignPlayResumesWithItsOwnMissionAndDifficulty() {
        // The selected difficulty is EASY, but the saved play is HARD: the play resumes as it was saved.
        val target = CampaignContinue.target(Profile(), saved(), Difficulty.EASY)
        assertEquals(CampaignContinue.Target.Resume(56, Difficulty.HARD), target)
    }

    @Test
    fun aDailySaveNeverCountsAsACampaignResume() {
        val daily = saved(kind = MatchKind.DAILY, mission = "daily-2026-10-22-MEDIUM-HUNT")
        val target = CampaignContinue.target(Profile(), daily, Difficulty.MEDIUM)
        assertEquals(CampaignContinue.Target.NextMission(1, Difficulty.MEDIUM), target)
    }

    @Test
    fun aSaveWithoutAMissionOrDifficultyFallsBackToTheNextMission() {
        assertEquals(
            CampaignContinue.Target.NextMission(1, Difficulty.EASY),
            CampaignContinue.target(Profile(), saved(mission = null), Difficulty.EASY),
        )
        assertEquals(
            CampaignContinue.Target.NextMission(1, Difficulty.EASY),
            CampaignContinue.target(Profile(), saved(difficulty = null), Difficulty.EASY),
        )
    }
}
