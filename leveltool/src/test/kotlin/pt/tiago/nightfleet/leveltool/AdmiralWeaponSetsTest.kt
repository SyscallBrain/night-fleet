package pt.tiago.nightfleet.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionKind

/** The admiral weapon sets and the Easy-to-Medium-to-Hard escalation rule. */
class AdmiralWeaponSetsTest {

    private fun planned(sea: Int, weapons: Set<Mech>) = Planned(sea, 10, MissionKind.DUEL, emptySet(), weapons, null)

    /** The sea that introduces a weapon, read from the campaign table (the same lookup as the generator). */
    private fun introOf(mech: Mech): Int = INTROS.entries.first { mech in it.value }.key

    @Test
    fun easyAdmiralsFireOnlySonarFromSeaSix() {
        val all = setOf(Mech.SONAR, Mech.RECON, Mech.TORPEDO, Mech.CROSS)
        assertEquals(listOf("SONAR"), admiralWeapons(planned(6, all), Difficulty.EASY))
        assertEquals(listOf("SONAR"), admiralWeapons(planned(9, all), Difficulty.EASY))
    }

    @Test
    fun easyAdmiralsBeforeSeaSixFollowTheMediumRule() {
        val weapons = setOf(Mech.SONAR, Mech.RECON)
        assertEquals(admiralWeapons(planned(5, weapons), Difficulty.MEDIUM), admiralWeapons(planned(5, weapons), Difficulty.EASY))
    }

    @Test
    fun mediumAdmiralsUseTheWeaponsIntroducedAtLeastOneSeaEarlier() {
        val weapons = setOf(Mech.SONAR, Mech.RECON, Mech.TORPEDO)
        val expected = weapons.filter { introOf(it) < 6 }.sortedBy { it.ordinal }.map { WEAPON_OF.getValue(it) }
        assertEquals(expected, admiralWeapons(planned(6, weapons), Difficulty.MEDIUM))
    }

    @Test
    fun hardAdmiralsUseEveryWeaponOfTheMission() {
        val weapons = setOf(Mech.SONAR, Mech.RECON, Mech.TORPEDO, Mech.DECOY)
        assertEquals(weapons.sortedBy { it.ordinal }.map { WEAPON_OF.getValue(it) }, admiralWeapons(planned(9, weapons), Difficulty.HARD))
    }

    @Test
    fun aMissionWithoutWeaponsGivesNoAdmiralWeapons() {
        for (difficulty in Difficulty.entries) assertEquals(emptyList<String>(), admiralWeapons(planned(3, emptySet()), difficulty))
    }

    @Test
    fun everyAdmiralSetOfSeaTwelveIsInsideTheWeaponsOfItsVariant() {
        var checked = 0
        for (output in buildSea(12, DEFAULT_SEED)) {
            if (output.mission.type == MissionKind.PUZZLE) continue
            for ((difficulty, variant) in output.mission.variants) {
                val admiral = variant.ai.admiralWeapons ?: error("${output.mission.id} ${difficulty.name} has no admiral set")
                assertTrue("${output.mission.id} ${difficulty.name}: $admiral", admiral.all { it in variant.weapons })
                assertEquals(difficulty, variant.ai.tier)
                checked++
            }
        }
        assertTrue(checked > 0)
    }
}
