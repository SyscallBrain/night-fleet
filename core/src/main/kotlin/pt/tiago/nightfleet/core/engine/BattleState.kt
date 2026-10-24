package pt.tiago.nightfleet.core.engine

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon

/**
 * Complete, immutable state of a battle. Side 0 and side 1 are the two seas.
 * Squares are row-major indices of the board. Every collection is ordered, so equal states always
 * have the same hash.
 */
@Serializable
data class BattleState(
    val engineVersion: Int,
    val rules: RuleSet,
    val mission: MissionType,
    /** Seed of this attempt; it only feeds the derived streams (see SeedStreams). */
    val attemptSeed: Long,
    /** Shots the player may fire in HUNT. Null otherwise. */
    val shotBudget: Int?,
    val sides: List<SideState>,
    /** Turns taken so far (every shot, salvo, weapon, move or timeout counts as one; skips do not). */
    val turn: Int,
    /** Completed rounds: both sides acted in DUEL-like missions; in HUNT every turn is a round. */
    val round: Int,
    /** Side to act. */
    val current: Int,
    /** Turns each side still has to skip (mines). Always two entries. */
    val pendingSkips: List<Int>,
    /** Engine-side RNG state. Unused by the classic rules; kept for later rules. */
    val rngState: Long,
    /** Null while the battle runs. */
    val result: BattleResult?,
)

@Serializable
data class SideState(
    val board: Board,
    val ships: List<ShipState>,
    /** Decoys (Iscos) of this sea, placed at setup. Hidden until hit. */
    val decoys: List<DecoyState>,
    /** Every shot received by this sea, in order, plus the automatic water and repairs recorded in it. */
    val incoming: ShotLog,
    /** Charges per weapon. Always contains every [Weapon], in declaration order. */
    val charges: Map<Weapon, Int>,
    val moveUsed: Boolean,
    val repairUsed: Boolean,
    /** Consecutive turns with at least one hit by this side. */
    val hitStreak: Int,
    /** Turns this side has taken itself (skipped turns excluded). Drives fog fading and EVERY charges. */
    val turnsTaken: Int = 0,
    /** Shots of this side counted for stars: one per fired square, weapon uses included. */
    val starShots: Int = 0,
    /** Turn of this side's ship move, if it moved one (at most one per battle). */
    val movedAtTurn: Int? = null,
    /** Sonar readings taken by this side. */
    val sonarReadings: List<SonarReadingState> = emptyList(),
    /** Reconnaissance readings taken by this side. */
    val reconReadings: List<ReconReadingState> = emptyList(),
)

/** A ship of one side. [hits] are the squares of this ship that were shot, sorted. */
@Serializable
data class ShipState(
    val placement: Placement,
    val hits: List<Int> = emptyList(),
    val sunk: Boolean = false,
) {
    val shipClass: ShipClass get() = placement.shipClass
}

/**
 * Decoy (Isco) of a sea. [hitBy] is the side that shot it and [hitTurn] the turn of that shot;
 * [revealed] becomes true at the end of the shooter's next turn.
 */
@Serializable
data class DecoyState(
    val cell: Int,
    val hitBy: Int? = null,
    val hitTurn: Int? = null,
    val revealed: Boolean = false,
)

/** A sonar reading: unhit ship squares (submerged and decoys included) in the 3 by 3 around [center]. */
@Serializable
data class SonarReadingState(val center: Int, val count: Int, val turn: Int)

/** A reconnaissance reading of a whole line: [present] when an unhit surface ship square (or decoy) lies in it. */
@Serializable
data class ReconReadingState(val isRow: Boolean, val index: Int, val present: Boolean, val turn: Int)

/**
 * One entry of a sea's shot log. [turn] is the global turn at which the entry was made and
 * [ownTurn] the number of turns the attacker had taken before it (fog fading counts these).
 */
@Serializable
data class ShotRecord(
    val turn: Int,
    val attacker: Int,
    val cell: Int,
    val result: ShotResult,
    val ownTurn: Int = 0,
)

@Serializable
sealed class ShotResult {
    @Serializable
    data object Miss : ShotResult()

    @Serializable
    data object Hit : ShotResult()

    /** The shot that sank a ship. [cells] are the ship's squares, sorted: they are public once sunk. */
    @Serializable
    data class Sunk(val shipClass: ShipClass, val cells: List<Int>) : ShotResult()

    /** Water marked automatically around a sunk ship. Not a shot: it does not count for budgets. */
    @Serializable
    data object AutoWater : ShotResult()

    /** A mine exploded under the attacker's shot or torpedo. The mine is disabled. */
    @Serializable
    data object MineHit : ShotResult()

    /** The attacker shot a decoy. It reads as a hit until the decoy is revealed. */
    @Serializable
    data object DecoyHit : ShotResult()

    /** The attacker shot a submerged submarine: no damage. */
    @Serializable
    data object Contact : ShotResult()

    /** Water seen by a torpedo on its way. Not a shot. */
    @Serializable
    data object Trail : ShotResult()

    /** A damaged square of this sea was repaired: the square is a known, unhit ship square again. */
    @Serializable
    data object Repaired : ShotResult()
}

/** Ordered log of everything that happened to one sea. */
@Serializable
data class ShotLog(val entries: List<ShotRecord> = emptyList()) {

    fun append(record: ShotRecord): ShotLog = ShotLog(entries + record)

    /** The most recent record of [attacker] on [cell], or null if the attacker never touched it. */
    fun latest(attacker: Int, cell: Int): ShotRecord? =
        entries.lastOrNull { it.attacker == attacker && it.cell == cell }
}

@Serializable
data class BattleResult(
    /** Winning side, or null when nobody won (draw). */
    val winner: Int?,
    val reason: EndReason,
)

@Serializable
enum class EndReason {
    /** A fleet was sunk. In HUNT the winner is side 0 (player). */
    FLEET_SUNK,

    /** HUNT: the shot budget ran out before the fleet was sunk. */
    BUDGET_EXHAUSTED,

    /** The turn limit was reached: a draw. */
    TURN_LIMIT,

    /** CONVOY: the player survived the required rounds with enough freighters afloat. */
    CONVOY_SURVIVED,

    /** CONVOY: fewer freighters than the minimum were left afloat. */
    CONVOY_LOST,
}
