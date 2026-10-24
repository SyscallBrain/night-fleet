package pt.tiago.nightfleet.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.ai.AiSkill
import pt.tiago.nightfleet.core.ai.AiTier

/** The single mapping from the content AI config to the effective admiral skill. */
class AiMappingTest {

    @Test
    fun theContentBlunderIsTheEffectivePerMilleWithoutScaling() {
        // The profile's own blunder (Frost 80, scaled to 20 on HARD) must not change the content value.
        val skill = AiConfigDto(tier = Difficulty.HARD, blunder = 437).toSkill(Admirals.FROST, AiTier.HARD)
        assertEquals(437, skill.blunderPermille)
    }

    @Test
    fun theFollowUpMissIsTakenFromTheContent() {
        val skill = AiConfigDto(tier = Difficulty.HARD, blunder = 0, followUpMiss = 675).toSkill(Admirals.ATOLL, AiTier.HARD)
        assertEquals(675, skill.followUpMissPermille)
    }

    @Test
    fun aMissingFollowUpMissIsNoFollowUpMiss() {
        assertEquals(0, AiConfigDto(tier = Difficulty.EASY, blunder = 300).toSkill(Admirals.IRIS, AiTier.EASY).followUpMissPermille)
    }

    @Test
    fun theWeaponDelayDefaultsToTheProfileAndAnExplicitValueWins() {
        val byProfile = AiConfigDto(tier = Difficulty.MEDIUM, blunder = 100).toSkill(Admirals.HAZE, AiTier.MEDIUM)
        assertEquals(Admirals.HAZE.weaponDelayTurns, byProfile.weaponDelayTurns)
        assertEquals(AiSkill.derived(Admirals.HAZE, AiTier.MEDIUM).weaponDelayTurns, byProfile.weaponDelayTurns)
        val explicit = AiConfigDto(tier = Difficulty.MEDIUM, blunder = 100, weaponDelay = 9).toSkill(Admirals.HAZE, AiTier.MEDIUM)
        assertEquals(9, explicit.weaponDelayTurns)
    }

    @Test
    fun theMappingIsNotTheDerivedSkillOfFreePlay() {
        // Free play scales the profile's blunder by the tier; a campaign variant does not.
        val derived = AiSkill.derived(Admirals.IRIS, AiTier.EASY)
        val content = AiConfigDto(tier = Difficulty.EASY, blunder = 10).toSkill(Admirals.IRIS, AiTier.EASY)
        assertEquals(300, derived.blunderPermille)
        assertEquals(10, content.blunderPermille)
    }

    @Test
    fun theAdmiralWeaponSetIsMappedByName() {
        val restricted = AiConfigDto(tier = Difficulty.EASY, blunder = 0, admiralWeapons = listOf("SONAR")).toSkill(Admirals.WITCH, AiTier.EASY)
        assertEquals(setOf(pt.tiago.nightfleet.core.weapons.Weapon.SONAR), restricted.admiralWeapons)
        val none = AiConfigDto(tier = Difficulty.EASY, blunder = 0, admiralWeapons = emptyList()).toSkill(Admirals.WITCH, AiTier.EASY)
        assertEquals(emptySet<pt.tiago.nightfleet.core.weapons.Weapon>(), none.admiralWeapons)
        assertEquals(null, AiConfigDto(tier = Difficulty.EASY, blunder = 0).toSkill(Admirals.WITCH, AiTier.EASY).admiralWeapons)
    }

    @Test
    fun theEscalatedTierIsTheTierOfTheAdmiral() {
        assertEquals(AiTier.HARD, AiConfigDto(tier = Difficulty.HARD, blunder = 0).aiTier())
        assertEquals(AiTier.MEDIUM, AiConfigDto(tier = Difficulty.MEDIUM, blunder = 0).aiTier())
    }

    @Test
    fun aFollowUpMissAboveNineHundredIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigDto(tier = Difficulty.HARD, blunder = 0, followUpMiss = 950).toSkill(Admirals.WOLF, AiTier.HARD)
        }
    }

    @Test
    fun aBlunderOverOneThousandIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AiConfigDto(tier = Difficulty.HARD, blunder = 1001).toSkill(Admirals.WOLF, AiTier.HARD)
        }
    }
}
