package pt.tiago.nightfleet.game.battle

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.game.freeplay.OpponentLevel

/**
 * Everything a battle needs to be created again: the rules, the mission, both fleets (resolved, so
 * a restore never draws a layout again), the decoys, who starts and the seed. Stored as JSON in the
 * match slot (see SavedMatch.setup), next to the actions that were played.
 */
@Serializable
data class MatchSetup(
    val rules: RuleSet,
    val mission: MissionType = MissionType.DUEL,
    val attemptSeed: Long,
    /** Fleets of side 0 and side 1, in side order. */
    val placements: List<List<Placement>>,
    /** Decoys (Iscos) of side 0 and side 1. */
    val decoys: List<List<Cell>> = listOf(emptyList(), emptyList()),
    /** Side that acts first. */
    val firstPlayer: Int = 0,
    /** Seat names of the two-player duel, side 0 first. Empty otherwise. */
    val names: List<String> = emptyList(),
    /** Opponent id of free play. Null for the duel. */
    val opponentId: String? = null,
    /** Level of the free play opponent (its brain and hints). Null for the duel. */
    val admiralLevel: OpponentLevel? = null,
    /** Shots allowed in a HUNT (null otherwise). Campaign hunts only. */
    val shotBudget: Int? = null,
    /**
     * The AI config of the campaign variant (its calibrated knobs). Null in free play, whose admiral keeps the
     * derived skill. Kept here so a restored battle plays the same admiral.
     */
    val admiralAi: AiConfigDto? = null,
) {
    /** Validates and builds the first state. Never throws. */
    fun create(): SetupResult = BattleSetup.create(
        rules = rules,
        mission = mission,
        placements = placements,
        firstPlayer = firstPlayer,
        attemptSeed = attemptSeed,
        shotBudget = shotBudget,
        decoys = decoys,
    )
}
