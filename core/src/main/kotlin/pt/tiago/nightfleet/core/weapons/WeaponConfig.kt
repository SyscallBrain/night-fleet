package pt.tiago.nightfleet.core.weapons

import kotlinx.serialization.Serializable

/** What earns a charge of a weapon. */
@Serializable
enum class EarnOn {
    /** One charge per ship sunk by the side. */
    SINK,

    /** One charge each time the side's hit streak reaches a multiple of [EarnRule.n]. */
    STREAK,

    /** One charge each time the side has taken a multiple of [EarnRule.n] turns. */
    EVERY,
}

@Serializable
data class EarnRule(val on: EarnOn, val n: Int = 1)

/**
 * Charges of one weapon in a mission or variant: how many a side starts with, the most it can hold
 * and what earns more. The same config applies to both sides.
 */
@Serializable
data class WeaponConfig(
    val initial: Int = 0,
    val max: Int = 2,
    val earn: List<EarnRule> = emptyList(),
)
