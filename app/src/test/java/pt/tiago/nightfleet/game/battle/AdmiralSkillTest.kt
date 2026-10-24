package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import pt.tiago.nightfleet.core.ai.AiSkill
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.toSkill
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.game.freeplay.OpponentLevel

/** The skill an admiral plays with: the campaign passes the content knobs, free play keeps the derived ones. */
class AdmiralSkillTest {

    @Test
    fun aCampaignAdmiralPlaysTheContentSkillExactly() {
        val ai = AiConfigDto(tier = Difficulty.MEDIUM, blunder = 321, followUpMiss = 450, weaponDelay = 3)
        val brain = AdmiralOpponent.forLevel("iris", OpponentLevel.MEDIUM, attemptSeed = 77L, campaignAi = ai)
        assertEquals(ai.toSkill(brain.profile, AiTier.MEDIUM), brain.skill)
        assertEquals(AiSkill(blunderPermille = 321, followUpMissPermille = 450, weaponDelayTurns = 3), brain.skill)
    }

    @Test
    fun aCampaignSkillIsNotScaledByTheProfileOrTheTier() {
        // The derived skill of Iris on MEDIUM would scale her blunder by the tier; the content value is used as it is.
        val derived = AdmiralOpponent.forLevel("iris", OpponentLevel.MEDIUM, attemptSeed = 77L).skill
        val campaign = AdmiralOpponent.forLevel("iris", OpponentLevel.MEDIUM, 77L, AiConfigDto(Difficulty.MEDIUM, blunder = 100)).skill
        assertNotEquals(derived.blunderPermille, campaign.blunderPermille)
        assertEquals(100, campaign.blunderPermille)
    }

    @Test
    fun aFreePlayAdmiralKeepsTheDerivedSkill() {
        val brain = AdmiralOpponent.forLevel("haze", OpponentLevel.HARD, attemptSeed = 5L)
        assertEquals(AiSkill.derived(brain.profile, AiTier.HARD), brain.skill)
    }

    @Test
    fun theCampaignSkillSurvivesTheMatchSaveAsTheVariantAi() {
        val ai = AiConfigDto(tier = Difficulty.HARD, blunder = 640, followUpMiss = 825, weaponDelay = 2)
        val setup = MatchSetup(
            rules = pt.tiago.nightfleet.core.rules.RuleSet(),
            attemptSeed = 9L,
            placements = listOf(emptyList(), emptyList()),
            opponentId = "atoll",
            admiralLevel = OpponentLevel.HARD,
            admiralAi = ai,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(MatchSetup.serializer(), setup)
        val restored = kotlinx.serialization.json.Json.decodeFromString(MatchSetup.serializer(), json)
        assertEquals(ai, restored.admiralAi)
    }

    @Test
    fun anEscalatedCampaignAdmiralPlaysTheHigherTierWithItsWeaponSet() {
        // A Medium variant whose admiral was escalated to Hard, with no weapons from the delay on.
        val ai = AiConfigDto(
            tier = Difficulty.HARD,
            blunder = 300,
            followUpMiss = 0,
            weaponDelay = 999,
            admiralWeapons = listOf("SONAR"),
        )
        val brain = AdmiralOpponent.forLevel("iris", OpponentLevel.HARD, attemptSeed = 77L, campaignAi = ai)
        assertEquals(AiTier.HARD, brain.tier)
        assertEquals(setOf(Weapon.SONAR), brain.skill.admiralWeapons)
        assertEquals(999, brain.skill.weaponDelayTurns)
        assertEquals(ai.toSkill(brain.profile, AiTier.HARD), brain.skill)
    }
}
