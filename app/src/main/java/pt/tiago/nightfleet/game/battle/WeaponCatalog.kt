package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.EarnRule

/** Which sea a weapon aims at and how it is aimed. */
enum class TargetKind {
    /** One square of the enemy sea: sonar, cross shell. */
    SQUARE_ENEMY,

    /** One square of the player's own sea: repair. */
    SQUARE_OWN,

    /** A whole row or column of the enemy sea: torpedo, reconnaissance. */
    LINE_ENEMY,

    /** Not fired from the column: the decoy is placed at setup. */
    NONE,
}

/** How a weapon recharges, as the button hint describes it. */
sealed class Recharge {
    /** Nothing earns more charges: the weapon is used up when its charges run out. */
    data object None : Recharge()

    /** Sink a ship to recharge. */
    data object Sink : Recharge()

    /** [n] hits in a row. */
    data class Streak(val n: Int) : Recharge()

    /** Every [n] of the side's own turns. */
    data class Every(val n: Int) : Recharge()
}

/** Static facts about the special weapons: column order, targeting kind, recharge and default configs. */
object WeaponCatalog {

    /** Column order of the weapons column. The decoy has no button. */
    val COLUMN_ORDER: List<Weapon> = listOf(Weapon.SONAR, Weapon.TORPEDO, Weapon.RECON, Weapon.CROSS_SHELL, Weapon.REPAIR)

    /** Charges shown at 45 % opacity when a weapon has none left. */
    const val EMPTY_ALPHA = 0.45f

    fun kind(weapon: Weapon): TargetKind = when (weapon) {
        Weapon.SONAR, Weapon.CROSS_SHELL -> TargetKind.SQUARE_ENEMY
        Weapon.REPAIR -> TargetKind.SQUARE_OWN
        Weapon.TORPEDO, Weapon.RECON -> TargetKind.LINE_ENEMY
        Weapon.DECOY -> TargetKind.NONE
    }

    /** The buttons of the column: the weapons enabled in [rules], in column order. */
    fun buttons(rules: RuleSet): List<Weapon> = COLUMN_ORDER.filter { rules.weapons.containsKey(it) }

    /** The first recharge rule of [config], as the hint shows it. */
    fun recharge(config: WeaponConfig?): Recharge {
        val rule: EarnRule = config?.earn?.firstOrNull() ?: return Recharge.None
        return when (rule.on) {
            EarnOn.SINK -> Recharge.Sink
            EarnOn.STREAK -> Recharge.Streak(rule.n)
            EarnOn.EVERY -> Recharge.Every(rule.n)
        }
    }

    /** Whether a button is shown at reduced opacity: no charge left. */
    fun isEmpty(charges: Int): Boolean = charges <= 0

    /** Charges of [weapon] for [side] in the engine state. */
    fun charges(charges: Map<Weapon, Int>, weapon: Weapon): Int = charges[weapon] ?: 0

    /**
     * Default configs of the free play setup : sonar 1 with 2 at most
     * and a charge per 3 hits in a row, torpedo 0 with 1 at most, sink a ship to recharge, recon 1,
     * cross 0 and one per 8 own turns, repair 1, decoy 1.
     */
    fun defaultConfig(weapon: Weapon): WeaponConfig = when (weapon) {
        Weapon.SONAR -> WeaponConfig(initial = 1, max = 2, earn = listOf(EarnRule(EarnOn.STREAK, 3)))
        Weapon.TORPEDO -> WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK)))
        Weapon.RECON -> WeaponConfig(initial = 1, max = 1, earn = emptyList())
        Weapon.CROSS_SHELL -> WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.EVERY, 8)))
        Weapon.REPAIR -> WeaponConfig(initial = 1, max = 1, earn = emptyList())
        Weapon.DECOY -> WeaponConfig(initial = 1, max = 1, earn = emptyList())
    }
}
