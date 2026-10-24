package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon

/** The admiral's own weapon set: the skill restricts the profile's weapons, an empty set means none. */
class AdmiralWeaponSetTest {

    private val view = SyntheticViews.blank(
        8, 8,
        classes = listOf(ShipClass.DESTROYER, ShipClass.CRUISER),
        charges = mapOf(Weapon.SONAR to 2, Weapon.TORPEDO to 1),
    )

    private fun weaponsOver(skill: AiSkill, seeds: LongRange): List<AiIntent> =
        seeds.map { AdmiralBrain.decide(view, Admirals.WITCH, AiTier.HARD, SplitMix64(it), skill) }

    @Test
    fun theUnrestrictedWitchFiresWeaponsOnThisView() {
        // The control: without a restriction the weapons do come out, so the next tests mean something.
        val intents = weaponsOver(AiSkill(0), 1L..40L)
        assertTrue(intents.any { it !is AiIntent.Fire })
    }

    @Test
    fun anEmptyWeaponSetNeverFiresAWeapon() {
        val intents = weaponsOver(AiSkill(0, admiralWeapons = emptySet()), 1L..40L)
        assertTrue(intents.all { it is AiIntent.Fire })
    }

    @Test
    fun aRestrictedSetOnlyFiresWeaponsItContains() {
        val intents = weaponsOver(AiSkill(0, admiralWeapons = setOf(Weapon.TORPEDO)), 1L..40L)
        assertTrue(intents.none { it is AiIntent.Sonar })
    }

    @Test
    fun theSkillSetIsIntersectedWithTheProfileSet() {
        // Wolf may use only torpedo and sonar: a skill that asks for sonar and cross shell leaves sonar only.
        val allowed = AdmiralBrain.weaponsOf(Admirals.WOLF, AiSkill(0, admiralWeapons = setOf(Weapon.SONAR, Weapon.CROSS_SHELL)))
        assertEquals(setOf(Weapon.SONAR), allowed)
        assertEquals(Admirals.WOLF.weaponsAllowed, AdmiralBrain.weaponsOf(Admirals.WOLF, AiSkill(0)))
    }
}
