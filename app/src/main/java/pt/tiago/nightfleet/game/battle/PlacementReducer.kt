package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.ProblemCode
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.data.Formation
import pt.tiago.nightfleet.data.MAX_FORMATIONS_PER_KEY
import pt.tiago.nightfleet.data.Placement as SavedPlacement

/** Why the last action was refused or what it did. The screen turns it into a string. */
sealed class PlacementNotice {
    data class Rejected(val code: ProblemCode) : PlacementNotice()
    data object AutoFailed : PlacementNotice()
    data object Incomplete : PlacementNotice()
    data object FormationSaved : PlacementNotice()
    data object FormationLoaded : PlacementNotice()
    data object FormationInvalid : PlacementNotice()
    data object FormationSlotsFull : PlacementNotice()
    data object FormationDeleted : PlacementNotice()
}

/**
 * The fleet placement screen as a pure state. Every action returns a new
 * state; a refused action keeps the old layout and sets [notice]. The view model only persists formations.
 */
data class PlacementState(
    val board: Board,
    val fleet: Fleet,
    val placed: List<Placement> = emptyList(),
    val dragging: ShipClass? = null,
    val dragRotation: Int = 0,
    val dragCell: Cell? = null,
    /** Problems of the drag preview at [dragCell] (empty when the preview is legal). */
    val dragProblems: List<ProblemCode> = emptyList(),
    val notice: PlacementNotice? = null,
    /** Saved formations for this board, fleet and terrain (at most [MAX_FORMATIONS_PER_KEY]). */
    val formations: List<Formation> = emptyList(),
    val autoCount: Int = 0,
) {
    /** Ships still to place, in fleet order. */
    val dock: List<ShipClass>
        get() {
            val remaining = fleet.ships.groupingBy { it }.eachCount().toMutableMap()
            placed.forEach { p -> remaining[p.shipClass] = (remaining[p.shipClass] ?: 0) - 1 }
            return fleet.ships.filter { ship ->
                val left = remaining[ship] ?: 0
                if (left > 0) remaining[ship] = left - 1
                left > 0
            }
        }

    val complete: Boolean get() = dock.isEmpty() && PlacementReducer.validateAll(board, fleet, placed).isEmpty()
}

object PlacementReducer {

    fun initial(board: Board, fleet: Fleet, formations: List<Formation> = emptyList()): PlacementState =
        PlacementState(board = board, fleet = fleet, formations = formations)

    /** Problems that matter while the fleet is being placed (the fleet may still be incomplete). */
    fun previewProblems(board: Board, fleet: Fleet, placements: List<Placement>): List<ProblemCode> =
        PlacementValidator.validate(board, fleet, placements).map { it.code }.filter { it != ProblemCode.INCOMPLETE_FLEET }

    /** Full problems of a layout: the fleet must be complete and legal. */
    fun validateAll(board: Board, fleet: Fleet, placements: List<Placement>): List<ProblemCode> =
        PlacementValidator.validate(board, fleet, placements).map { it.code }

    fun startDrag(state: PlacementState, shipClass: ShipClass): PlacementState {
        if (shipClass !in state.dock) return state
        return state.copy(dragging = shipClass, dragRotation = 0, dragCell = null, dragProblems = emptyList(), notice = null)
    }

    /** Moves the dragged ship's origin to [cell] and updates the preview. */
    fun dragOver(state: PlacementState, cell: Cell?): PlacementState {
        val ship = state.dragging ?: return state
        if (cell == null) return state.copy(dragCell = null, dragProblems = emptyList())
        val candidate = Placement(ship, cell, state.dragRotation)
        return state.copy(dragCell = cell, dragProblems = previewProblems(state.board, state.fleet, state.placed + candidate))
    }

    fun rotateDragged(state: PlacementState): PlacementState {
        val ship = state.dragging ?: return state
        val next = (state.dragRotation + 1) % ship.rotationCount
        val cell = state.dragCell
        val problems = cell?.let { previewProblems(state.board, state.fleet, state.placed + Placement(ship, it, next)) }.orEmpty()
        return state.copy(dragRotation = next, dragProblems = problems)
    }

    /** Drops the dragged ship at [cell]. A legal drop places it; an illegal one returns it to the dock with the reason. */
    fun drop(state: PlacementState, cell: Cell): PlacementState {
        val ship = state.dragging ?: return state
        val candidate = Placement(ship, cell, state.dragRotation)
        val problems = previewProblems(state.board, state.fleet, state.placed + candidate)
        return if (problems.isEmpty()) {
            state.copy(placed = state.placed + candidate, dragging = null, dragCell = null, dragProblems = emptyList(), notice = null)
        } else {
            state.copy(dragging = null, dragCell = null, dragProblems = emptyList(), notice = PlacementNotice.Rejected(problems.first()))
        }
    }

    fun cancelDrag(state: PlacementState): PlacementState =
        state.copy(dragging = null, dragCell = null, dragProblems = emptyList())

    /** Tapping a placed ship turns it to its next orientation, if the turned ship is still legal. */
    fun rotatePlaced(state: PlacementState, index: Int): PlacementState {
        val target = state.placed.getOrNull(index) ?: return state
        val turned = target.copy(rotationIndex = (target.rotationIndex + 1) % target.shipClass.rotationCount)
        val others = state.placed.filterIndexed { i, _ -> i != index }
        val problems = previewProblems(state.board, state.fleet, others + turned)
        return if (problems.isEmpty()) {
            val next = state.placed.toMutableList().also { it[index] = turned }
            state.copy(placed = next, notice = null)
        } else {
            state.copy(notice = PlacementNotice.Rejected(problems.first()))
        }
    }

    fun removePlaced(state: PlacementState, index: Int): PlacementState {
        if (index !in state.placed.indices) return state
        return state.copy(placed = state.placed.filterIndexed { i, _ -> i != index }, notice = null)
    }

    fun clear(state: PlacementState): PlacementState = state.copy(placed = emptyList(), dragging = null, notice = null)

    /**
     * Automatic layout with the balanced style. Each call uses a new seed derived from [baseSeed] and the
     * number of automatic layouts so far, so every tap gives a new layout.
     */
    fun auto(state: PlacementState, baseSeed: Long): PlacementState {
        val seed = SplitMix64.mix(baseSeed xor state.autoCount.toLong())
        val next = state.copy(autoCount = state.autoCount + 1, dragging = null)
        return when (val result = RandomPlacer.place(state.board, state.fleet, SplitMix64(seed))) {
            is PlacementResult.Success -> next.copy(placed = result.placements, notice = null)
            is PlacementResult.Failure -> next.copy(notice = PlacementNotice.AutoFailed)
        }
    }

    /** Saves the complete layout in the next free slot. */
    fun saveFormation(state: PlacementState): PlacementState = when {
        !state.complete -> state.copy(notice = PlacementNotice.Incomplete)
        state.formations.size >= MAX_FORMATIONS_PER_KEY -> state.copy(notice = PlacementNotice.FormationSlotsFull)
        else -> state.copy(
            formations = state.formations + Formation(state.placed.map { it.toSaved() }),
            notice = PlacementNotice.FormationSaved,
        )
    }

    /** Loads slot [index] after validating it against the current board and fleet. */
    fun loadFormation(state: PlacementState, index: Int): PlacementState {
        val formation = state.formations.getOrNull(index) ?: return state
        val placements = formation.placements.map { it.toCore() }
        if (placements.any { it == null }) return state.copy(notice = PlacementNotice.FormationInvalid)
        val valid = placements.filterNotNull()
        if (validateAll(state.board, state.fleet, valid).isNotEmpty()) {
            return state.copy(notice = PlacementNotice.FormationInvalid)
        }
        return state.copy(placed = valid, dragging = null, notice = PlacementNotice.FormationLoaded)
    }

    fun deleteFormation(state: PlacementState, index: Int): PlacementState {
        if (index !in state.formations.indices) return state
        return state.copy(
            formations = state.formations.filterIndexed { i, _ -> i != index },
            notice = PlacementNotice.FormationDeleted,
        )
    }

    private fun Placement.toSaved(): SavedPlacement =
        SavedPlacement(shipClass = shipClass.name, row = origin.row, col = origin.col, rotation = rotationIndex)

    private fun SavedPlacement.toCore(): Placement? {
        val ship = runCatching { ShipClass.valueOf(shipClass) }.getOrNull() ?: return null
        return Placement(ship, Cell(row, col), rotation)
    }

    /** Key of the formation slots: board size, fleet and terrain. */
    fun formationKey(board: Board, fleet: Fleet): String {
        val terrain = board.terrain.fold(17L) { acc, t -> acc * 31 + t.ordinal + 1 }
        return "${board.width}x${board.height}:${fleet.name}:${java.lang.Long.toHexString(terrain)}"
    }
}
