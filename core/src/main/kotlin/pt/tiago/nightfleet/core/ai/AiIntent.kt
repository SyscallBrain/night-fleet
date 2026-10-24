package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** What an admiral decides to do on its turn. Every intent is proposed from an [AiView] only. */
sealed class AiIntent {
    data class Fire(val cell: Cell) : AiIntent()

    /** Sonar on the 3 by 3 window centred on [center]. */
    data class Sonar(val center: Cell) : AiIntent()

    /** Torpedo along a row or column, starting from its first ([fromStart]) or last square. */
    data class Torpedo(val isRow: Boolean, val index: Int, val fromStart: Boolean) : AiIntent()

    /** Aerial reconnaissance of a whole row or column. */
    data class Recon(val isRow: Boolean, val index: Int) : AiIntent()

    /** Cross shell centred on [center]. */
    data class Cross(val center: Cell) : AiIntent()

    /** Repair one hit square of an own ship. */
    data class Repair(val cell: Cell) : AiIntent()

    /** Move one own ship ([shipIndex] in the own fleet list) by [delta] squares along its axis. */
    data class Move(val shipIndex: Int, val delta: Int) : AiIntent()

    /** The engine action for this intent. Every intent maps to exactly one action. */
    fun toAction(): Action = when (this) {
        is Fire -> Action.Fire(cell)
        is Sonar -> Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(center))
        is Torpedo -> Action.UseWeapon(Weapon.TORPEDO, WeaponTarget.LineTarget(isRow, index, fromStart))
        is Recon -> Action.UseWeapon(Weapon.RECON, WeaponTarget.LineTarget(isRow, index))
        is Cross -> Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(center))
        is Repair -> Action.Repair(cell)
        is Move -> Action.MoveShip(shipIndex, delta)
    }
}
