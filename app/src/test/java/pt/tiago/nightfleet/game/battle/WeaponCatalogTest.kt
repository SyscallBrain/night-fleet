package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

class WeaponCatalogTest {

    @Test
    fun theButtonsAreTheEnabledWeaponsInColumnOrder() {
        val rules = WeaponFixtures.rules(
            weapons = mapOf(
                Weapon.REPAIR to WeaponConfig(1, 1),
                Weapon.SONAR to WeaponConfig(1, 2),
                Weapon.DECOY to WeaponConfig(1, 1),
                Weapon.TORPEDO to WeaponConfig(0, 1),
            ),
        )
        assertEquals(listOf(Weapon.SONAR, Weapon.TORPEDO, Weapon.REPAIR), WeaponCatalog.buttons(rules))
    }

    @Test
    fun noRulesMeanNoButtons() {
        assertTrue(WeaponCatalog.buttons(WeaponFixtures.rules()).isEmpty())
    }

    @Test
    fun eachWeaponTargetsOneSeaAndOneShape() {
        assertEquals(TargetKind.SQUARE_ENEMY, WeaponCatalog.kind(Weapon.SONAR))
        assertEquals(TargetKind.SQUARE_ENEMY, WeaponCatalog.kind(Weapon.CROSS_SHELL))
        assertEquals(TargetKind.SQUARE_OWN, WeaponCatalog.kind(Weapon.REPAIR))
        assertEquals(TargetKind.LINE_ENEMY, WeaponCatalog.kind(Weapon.TORPEDO))
        assertEquals(TargetKind.LINE_ENEMY, WeaponCatalog.kind(Weapon.RECON))
        assertEquals(TargetKind.NONE, WeaponCatalog.kind(Weapon.DECOY))
    }

    @Test
    fun theRechargeHintComesFromTheFirstEarnRule() {
        assertEquals(Recharge.Sink, WeaponCatalog.recharge(WeaponCatalog.defaultConfig(Weapon.TORPEDO)))
        assertEquals(Recharge.Streak(3), WeaponCatalog.recharge(WeaponCatalog.defaultConfig(Weapon.SONAR)))
        assertEquals(Recharge.Every(8), WeaponCatalog.recharge(WeaponCatalog.defaultConfig(Weapon.CROSS_SHELL)))
        assertEquals(Recharge.None, WeaponCatalog.recharge(WeaponCatalog.defaultConfig(Weapon.RECON)))
        assertEquals(Recharge.None, WeaponCatalog.recharge(null))
        assertEquals(
            Recharge.Sink,
            WeaponCatalog.recharge(WeaponConfig(1, 1, listOf(EarnRule(EarnOn.SINK), EarnRule(EarnOn.EVERY, 2)))),
        )
    }

    @Test
    fun aWeaponWithoutChargesIsEmptyAndShownAt45Percent() {
        assertTrue(WeaponCatalog.isEmpty(0))
        assertEquals(false, WeaponCatalog.isEmpty(1))
        assertEquals(0.45f, WeaponCatalog.EMPTY_ALPHA)
        assertEquals(0, WeaponCatalog.charges(emptyMap(), Weapon.SONAR))
    }
}
