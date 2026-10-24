package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** What the player is doing with the weapons column. Pure state: the screen only shows it. */
sealed class Targeting {
    /** Nothing chosen: a tap fires, or in a salvo game toggles a square of the salvo. */
    data object Idle : Targeting()

    /** Salvo game: the squares picked so far (row-major indices of the enemy sea), out of [required]. */
    data class Salvo(val picked: List<Int>, val required: Int) : Targeting()

    /** A weapon that takes one square (sonar, cross shell, repair). */
    data class Square(val weapon: Weapon) : Targeting()

    /**
     * A weapon that takes a whole row or column (torpedo, recon). [anchor] is the last square tapped:
     * the line is the row of the anchor when [isRow], otherwise its column. [fromStart] is the torpedo's margin
     * * (top or left edge when true).
     */
    data class Line(
        val weapon: Weapon,
        val anchor: Cell?,
        val isRow: Boolean,
        val fromStart: Boolean,
    ) : Targeting()

    /** Move one ship: [shipId] is null until a ship is chosen, then [deltas] are its legal steps (-1 and/or +1). */
    data class Move(val shipId: Int?, val deltas: List<Int>) : Targeting()
}

/** The result of a tap or a button in the targeting reducer. */
sealed class TargetTap {
    /** Nothing is sent; the targeting state moves to [targeting]. [refusal] is an engine-style reason code, or null. */
    data class Stay(val targeting: Targeting, val refusal: String? = null) : TargetTap()

    /** A tap on the enemy sea with no weapon chosen: the normal shot path decides (confirm or fire). */
    data class Fire(val index: Int) : TargetTap()

    /** The action is ready: the controller sends it through the engine. */
    data class Send(val action: Action) : TargetTap()
}

/**
 * Targeting rules of the weapons column. Each function is pure: it takes the state
 * and the tap and says what happens. Only [Send] reaches the engine; the engine still has the last word.
 */
object TargetingReducer {

    const val REFUSAL_ALREADY_FIRED = "ALREADY_FIRED"
    const val REFUSAL_SALVO_FULL = "SALVO_SIZE"

    /**
     * A button of the column. Choosing the weapon that is already chosen cancels it. Choosing a
     * weapon replaces an open salvo (weapons replace the whole salvo).
     */
    fun pickWeapon(current: Targeting, weapon: Weapon): Targeting {
        val chosen = (current as? Targeting.Square)?.weapon ?: (current as? Targeting.Line)?.weapon
        if (chosen == weapon) return Targeting.Idle
        return when (WeaponCatalog.kind(weapon)) {
            TargetKind.SQUARE_ENEMY, TargetKind.SQUARE_OWN -> Targeting.Square(weapon)
            TargetKind.LINE_ENEMY -> Targeting.Line(weapon = weapon, anchor = null, isRow = true, fromStart = true)
            TargetKind.NONE -> Targeting.Idle
        }
    }

    /** Button "Move a ship": opens the choice of a ship, or closes it. */
    fun pickMove(current: Targeting): Targeting = if (current is Targeting.Move) Targeting.Idle else Targeting.Move(null, emptyList())

    /**
     * A tap on the enemy sea. [open] is true when the square is still unknown (a tap on a known square is
     * refused). [salvoRequired] is the size of the salvo, or null when the salvo rule is off.
     */
    fun enemyTap(
        current: Targeting,
        cell: Cell,
        width: Int,
        height: Int,
        open: Boolean,
        salvoRequired: Int?,
    ): TargetTap {
        val index = cell.row * width + cell.col
        return when (current) {
            Targeting.Idle -> when {
                salvoRequired == null -> TargetTap.Fire(index)
                !open -> TargetTap.Stay(current, REFUSAL_ALREADY_FIRED)
                else -> TargetTap.Stay(Targeting.Salvo(listOf(index), salvoRequired))
            }
            is Targeting.Salvo -> salvoTap(current, index, open)
            is Targeting.Square -> when {
                WeaponCatalog.kind(current.weapon) != TargetKind.SQUARE_ENEMY -> TargetTap.Stay(current)
                !open -> TargetTap.Stay(current, REFUSAL_ALREADY_FIRED)
                else -> TargetTap.Send(Action.UseWeapon(current.weapon, WeaponTarget.CellTarget(cell)))
            }
            is Targeting.Line -> {
                val anchor = current.anchor
                val sameLine = anchor != null && lineIndex(cell, current.isRow) == lineIndex(anchor, current.isRow)
                if (sameLine) {
                    TargetTap.Send(
                        Action.UseWeapon(
                            current.weapon,
                            WeaponTarget.LineTarget(current.isRow, lineIndex(cell, current.isRow), current.fromStart),
                        ),
                    )
                } else {
                    TargetTap.Stay(current.copy(anchor = cell, fromStart = defaultFromStart(cell, current.isRow, width, height)))
                }
            }
            is Targeting.Move -> TargetTap.Stay(current)
        }
    }

    private fun salvoTap(current: Targeting.Salvo, index: Int, open: Boolean): TargetTap = when {
        index in current.picked -> TargetTap.Stay(current.copy(picked = current.picked - index))
        !open -> TargetTap.Stay(current, REFUSAL_ALREADY_FIRED)
        current.picked.size >= current.required -> TargetTap.Stay(current, REFUSAL_SALVO_FULL)
        else -> TargetTap.Stay(current.copy(picked = current.picked + index))
    }

    /**
     * A tap on the player's own sea. A repair targets it; a move picks the ship under the finger, with the
     * legal steps from [legalDeltas]. Other states ignore it.
     */
    fun ownTap(current: Targeting, cell: Cell, shipAt: (Cell) -> Int?, legalDeltas: (Int) -> List<Int>): TargetTap = when (current) {
        is Targeting.Square ->
            if (current.weapon == Weapon.REPAIR) TargetTap.Send(Action.Repair(cell)) else TargetTap.Stay(current)
        is Targeting.Move -> {
            val ship = shipAt(cell)
            if (ship == null) {
                TargetTap.Stay(current)
            } else {
                val deltas = legalDeltas(ship)
                TargetTap.Stay(
                    Targeting.Move(ship, deltas),
                    refusal = if (deltas.isEmpty()) "MOVE_BLOCKED" else null,
                )
            }
        }
        else -> TargetTap.Stay(current)
    }

    /** An arrow of the moving ship: sends the move when the step is legal (the arrows only show legal steps). */
    fun moveStep(current: Targeting, delta: Int): TargetTap {
        val move = current as? Targeting.Move ?: return TargetTap.Stay(current)
        val ship = move.shipId ?: return TargetTap.Stay(current)
        if (delta !in move.deltas) return TargetTap.Stay(current, "MOVE_BLOCKED")
        return TargetTap.Send(Action.MoveShip(ship, delta))
    }

    /** The line of a torpedo flips between a row and a column, through the same square. */
    fun flipAxis(current: Targeting.Line, width: Int, height: Int): Targeting.Line {
        val anchor = current.anchor ?: return current.copy(isRow = !current.isRow)
        val isRow = !current.isRow
        return current.copy(isRow = isRow, fromStart = defaultFromStart(anchor, isRow, width, height))
    }

    /** The torpedo's margin toggles between the two edges of its line. */
    fun flipMargin(current: Targeting.Line): Targeting.Line = current.copy(fromStart = !current.fromStart)

    /** Row index for a row line, column index for a column line. */
    fun lineIndex(cell: Cell, isRow: Boolean): Int = if (isRow) cell.row else cell.col

    /** The margin nearest to [cell]: top or left when the cell is in the first half of its line. */
    fun defaultFromStart(cell: Cell, isRow: Boolean, width: Int, height: Int): Boolean =
        if (isRow) cell.col * 2 + 1 < width else cell.row * 2 + 1 < height
}

/** Legal moves of the ships of the side to act, derived from the engine itself. */
object MovePlanner {

    /** The steps (-1 and/or +1 along the ship's axis) the engine accepts for [shipId]. Empty when none. */
    fun legalDeltas(state: BattleState, shipId: Int): List<Int> {
        if (!state.rules.movingShips || state.sides[state.current].moveUsed) return emptyList()
        return listOf(-1, 1).filter { delta ->
            Engine.apply(state, Action.MoveShip(shipId, delta)).events.none { it is Event.IllegalAction }
        }
    }

    /** Ships of the side to act that can move at all (undamaged and with a legal step). */
    fun movableShips(state: BattleState): List<Int> =
        state.sides[state.current].ships.indices.filter { legalDeltas(state, it).isNotEmpty() }
}
