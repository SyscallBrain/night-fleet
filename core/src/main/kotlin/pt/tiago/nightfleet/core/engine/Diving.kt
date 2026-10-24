package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.rules.DivingRule

/** Phase of the submarines. Starts surfaced. */
enum class SubmarinePhase { SURFACED, SUBMERGED }

/** Phase of the submarines in the current round of [state], or null when the diving rule is off. */
fun submarinePhase(state: BattleState): SubmarinePhase? = phaseOf(state.rules.diving, state.round)

/** Rounds left in the current phase before it changes, or null when the diving rule is off. */
fun roundsUntilPhaseChange(state: BattleState): Int? {
    val rule = state.rules.diving ?: return null
    val cycle = rule.surfaceRounds + rule.submergedRounds
    val position = Math.floorMod(state.round, cycle)
    return if (position < rule.surfaceRounds) rule.surfaceRounds - position else cycle - position
}

/** Pure function of the round: the phase depends on nothing else. */
internal fun phaseOf(rule: DivingRule?, round: Int): SubmarinePhase? {
    if (rule == null) return null
    val cycle = rule.surfaceRounds + rule.submergedRounds
    return if (Math.floorMod(round, cycle) < rule.surfaceRounds) SubmarinePhase.SURFACED else SubmarinePhase.SUBMERGED
}
