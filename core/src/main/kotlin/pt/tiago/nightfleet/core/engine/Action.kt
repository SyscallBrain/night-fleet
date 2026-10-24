package pt.tiago.nightfleet.core.engine

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/**
 * Something the side to act does. The actor is always `state.current`.
 * Invalid actions answer [Event.IllegalAction] and leave the state unchanged.
 */
@Serializable
sealed class Action {
    /** Shoot one square of the enemy sea. */
    @Serializable
    data class Fire(val cell: Cell) : Action()

    /** Salvo (rule [salvo][pt.tiago.nightfleet.core.rules.RuleSet.salvo]): one shot per listed square, in order. */
    @Serializable
    data class Salvo(val cells: List<Cell>) : Action()

    /** Special weapon. Uses the whole turn. */
    @Serializable
    data class UseWeapon(val weapon: Weapon, val target: WeaponTarget) : Action()

    /** Move one of your ships one square along its axis (rule movingShips). Uses the turn. */
    @Serializable
    data class MoveShip(val shipId: Int, val delta: Int) : Action()

    /** Repair one of your damaged squares: the same as `UseWeapon(REPAIR, CellTarget(cell))`. */
    @Serializable
    data class Repair(val cell: Cell) : Action()

    /** The side lost its time and passes the turn without shooting. Sent only by the app. */
    @Serializable
    data object Timeout : Action()
}
