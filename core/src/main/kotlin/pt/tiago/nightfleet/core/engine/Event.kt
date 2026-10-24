package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon

/**
 * What the UI animates, in order. Every event names the side that acted (`actor`)
 * and the side it affects (`target`) when that applies.
 */
sealed class Event {
    /** A turn of [side] begins. */
    data class TurnStarted(val side: Int, val round: Int) : Event()

    data class Miss(val actor: Int, val target: Int, val cell: Cell) : Event()

    /** A shot hit a ship square, or a decoy (which reads as a hit to the shooter). */
    data class Hit(val actor: Int, val target: Int, val cell: Cell) : Event()

    data class Sunk(val actor: Int, val target: Int, val shipClass: ShipClass, val cells: List<Cell>) : Event()

    /** Automatic water around a sunk ship. */
    data class AutoWater(val actor: Int, val target: Int, val cells: List<Cell>) : Event()

    data class SubmergedContact(val actor: Int, val target: Int, val cell: Cell) : Event()

    data class MineHit(val actor: Int, val target: Int, val cell: Cell) : Event()

    /** [side] lost its turn (a timeout or a skip). */
    data class TurnSkipped(val side: Int) : Event()

    data class SonarReading(val actor: Int, val target: Int, val center: Cell, val count: Int) : Event()

    data class ReconReading(
        val actor: Int,
        val target: Int,
        val isRow: Boolean,
        val index: Int,
        val present: Boolean,
    ) : Event()

    data class TorpedoLaunched(
        val actor: Int,
        val target: Int,
        val isRow: Boolean,
        val index: Int,
        val fromStart: Boolean,
    ) : Event()

    data class TorpedoStep(val actor: Int, val target: Int, val cell: Cell) : Event()

    data class TorpedoBlocked(val actor: Int, val target: Int, val cell: Cell) : Event()

    data class TorpedoTrail(val actor: Int, val target: Int, val cells: List<Cell>) : Event()

    /** A decoy hit by [actor] is revealed. [target] is the side that owns the decoy. */
    data class DecoyRevealed(val actor: Int, val target: Int, val cell: Cell) : Event()

    /** [actor] repaired a square. [target] is the opponent, who learns the square is a ship again. */
    data class EnemyRepaired(val actor: Int, val target: Int, val cell: Cell) : Event()

    /** The enemy moved a ship. Carries no position. */
    data class EnemyShipMoved(val actor: Int, val target: Int) : Event()

    data class ChargeEarned(val side: Int, val weapon: Weapon) : Event()

    data class SubmarinesDived(val side: Int) : Event()

    data class SubmarinesSurfaced(val side: Int) : Event()

    /** Misses of [side] that fade from its view now. */
    data class MissFaded(val side: Int, val cells: List<Cell>) : Event()

    data class BattleEnded(val result: BattleResult) : Event()

    /** The action was refused. The state is unchanged. [reason] is a stable code, optionally with detail. */
    data class IllegalAction(val reason: String) : Event()
}
