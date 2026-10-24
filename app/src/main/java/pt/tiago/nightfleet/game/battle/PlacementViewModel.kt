package pt.tiago.nightfleet.game.battle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.data.Formation

/**
 * The placement screen's state. The reducer is pure ([PlacementReducer]); this class only feeds it
 * the saved formations and writes them back to the profile.
 */
class PlacementViewModel(
    private val container: AppContainer,
    /** The rules the fleet is placed for: the grid, the fleet and the terrain of the battle. */
    rules: RuleSet = QuickBattle.rules(),
) : ViewModel() {

    private val board = rules.boardFor(0)
    private val fleet = rules.fleet
    private val key = PlacementReducer.formationKey(board, fleet)
    private val autoBase = kotlin.random.Random.nextLong()

    private val mutable = MutableStateFlow(PlacementReducer.initial(board, fleet))
    val state: StateFlow<PlacementState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = container.profile.load()
            mutable.update { it.copy(formations = profile.formations[key].orEmpty()) }
        }
    }

    fun pickShip(shipClass: ShipClass) = mutable.update { state ->
        // Tapping the chosen ship again puts it back in the dock.
        if (state.dragging == shipClass) PlacementReducer.cancelDrag(state) else PlacementReducer.startDrag(state, shipClass)
    }

    /** A tap on the sea: drops the chosen ship, or turns a placed ship. */
    fun tapSea(cell: Cell) = mutable.update { state ->
        val index = placedIndexAt(state, cell)
        when {
            state.dragging != null -> PlacementReducer.drop(state, cell)
            index != null -> PlacementReducer.rotatePlaced(state, index)
            else -> state
        }
    }

    /** A long press on a placed ship sends it back to the dock. */
    fun longPressSea(cell: Cell) = mutable.update { state ->
        val index = placedIndexAt(state, cell)
        if (index == null) state else PlacementReducer.removePlaced(state, index)
    }

    fun auto() = mutable.update { PlacementReducer.auto(it, autoBase) }

    fun clear() = mutable.update { PlacementReducer.clear(it) }

    fun saveFormation() {
        val next = PlacementReducer.saveFormation(mutable.value)
        mutable.value = next
        if (next.notice == PlacementNotice.FormationSaved) persist(next.formations)
    }

    fun loadFormation(index: Int) = mutable.update { PlacementReducer.loadFormation(it, index) }

    fun deleteFormation(index: Int) {
        val next = PlacementReducer.deleteFormation(mutable.value, index)
        mutable.value = next
        if (next.notice == PlacementNotice.FormationDeleted) persist(next.formations)
    }

    private fun persist(formations: List<Formation>) {
        viewModelScope.launch {
            container.profile.update { profile -> profile.copy(formations = profile.formations + (key to formations)) }
        }
    }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    /** The layout to play, when the fleet is complete. */
    fun readyPlacements(): List<pt.tiago.nightfleet.core.fleet.Placement>? =
        mutable.value.takeIf { it.complete }?.placed

    companion object {
        /** Index in [PlacementState.placed] of the ship that covers [cell], or null. */
        fun placedIndexAt(state: PlacementState, cell: Cell): Int? {
            val index = state.placed.indexOfFirst { p -> p.cells().any { it == cell } }
            return index.takeIf { it >= 0 }
        }
    }
}
