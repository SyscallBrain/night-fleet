package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ProblemCode
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.data.Formation
import pt.tiago.nightfleet.data.Placement as SavedPlacement

class PlacementReducerTest {

    private val board: Board = QuickBattle.rules().boardFor(0)
    private val fleet: Fleet = QuickBattle.rules().fleet

    private fun fresh(formations: List<Formation> = emptyList()) = PlacementReducer.initial(board, fleet, formations)

    @Test
    fun theDockStartsWithEveryShipOfTheFleet() {
        val state = fresh()
        assertEquals(fleet.ships, state.dock)
        assertFalse(state.complete)
    }

    @Test
    fun aLegalDropPlacesTheDraggedShip() {
        var state = PlacementReducer.startDrag(fresh(), ShipClass.CARRIER)
        state = PlacementReducer.dragOver(state, Cell(0, 0))
        assertTrue(state.dragProblems.isEmpty())
        state = PlacementReducer.drop(state, Cell(0, 0))
        assertEquals(1, state.placed.size)
        assertFalse(ShipClass.CARRIER in state.dock)
        assertNull(state.dragging)
    }

    @Test
    fun touchingShipsAreRefusedWithTheReason() {
        var state = PlacementReducer.drop(PlacementReducer.startDrag(fresh(), ShipClass.CARRIER), Cell(0, 0))
        state = PlacementReducer.startDrag(state, ShipClass.BATTLESHIP)
        state = PlacementReducer.drop(state, Cell(1, 0))
        assertEquals(PlacementNotice.Rejected(ProblemCode.TOUCHING), state.notice)
        assertEquals(1, state.placed.size)
        assertTrue(ShipClass.BATTLESHIP in state.dock)
    }

    @Test
    fun aDragPreviewReportsProblemsBeforeTheDrop() {
        var state = PlacementReducer.drop(PlacementReducer.startDrag(fresh(), ShipClass.CARRIER), Cell(0, 0))
        state = PlacementReducer.dragOver(PlacementReducer.startDrag(state, ShipClass.CRUISER), Cell(0, 3))
        assertTrue(state.dragProblems.contains(ProblemCode.OVERLAP))
    }

    @Test
    fun droppingOutsideTheBoardIsRefused() {
        var state = PlacementReducer.startDrag(fresh(), ShipClass.CARRIER)
        state = PlacementReducer.drop(state, Cell(0, 8))
        assertTrue(state.notice is PlacementNotice.Rejected)
        assertTrue(state.placed.isEmpty())
    }

    @Test
    fun tappingAPlacedShipRotatesItOnlyWhenTheTurnIsLegal() {
        var state = PlacementReducer.drop(PlacementReducer.startDrag(fresh(), ShipClass.CARRIER), Cell(0, 0))
        state = PlacementReducer.rotatePlaced(state, 0)
        assertEquals(1, state.placed[0].rotationIndex)
        assertEquals(listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0), Cell(3, 0), Cell(4, 0)), state.placed[0].cells())
    }

    @Test
    fun aRotationThatTouchesAnotherShipIsRefused() {
        var state = PlacementReducer.drop(PlacementReducer.startDrag(fresh(), ShipClass.CARRIER), Cell(0, 0))
        state = PlacementReducer.drop(PlacementReducer.startDrag(state, ShipClass.DESTROYER), Cell(5, 0))
        // The carrier turned vertical at column 0 (rows 0..4) touches the destroyer at row 5: refused.
        val rotated = PlacementReducer.rotatePlaced(state, 0)
        assertTrue(rotated.notice is PlacementNotice.Rejected)
        assertEquals(state.placed, rotated.placed)
    }

    @Test
    fun autoFillsTheWholeFleetAndEachTapGivesANewLayout() {
        val first = PlacementReducer.auto(fresh(), BattleFixtures.SEED)
        assertTrue(first.complete)
        val second = PlacementReducer.auto(first, BattleFixtures.SEED)
        assertTrue(second.complete)
        assertFalse(first.placed == second.placed)
    }

    @Test
    fun aCompleteLayoutIsSavedAndCanBeLoadedAgain() {
        val complete = PlacementReducer.auto(fresh(), BattleFixtures.SEED)
        val saved = PlacementReducer.saveFormation(complete)
        assertEquals(PlacementNotice.FormationSaved, saved.notice)
        assertEquals(1, saved.formations.size)
        val cleared = PlacementReducer.clear(saved)
        val loaded = PlacementReducer.loadFormation(cleared, 0)
        assertEquals(PlacementNotice.FormationLoaded, loaded.notice)
        assertEquals(complete.placed, loaded.placed)
    }

    @Test
    fun savingAnIncompleteLayoutIsRefused() {
        val state = PlacementReducer.saveFormation(fresh())
        assertEquals(PlacementNotice.Incomplete, state.notice)
        assertTrue(state.formations.isEmpty())
    }

    @Test
    fun aFormationHoldsAtMostFiveSlots() {
        var state = PlacementReducer.auto(fresh(), BattleFixtures.SEED)
        repeat(5) { state = PlacementReducer.saveFormation(state) }
        assertEquals(5, state.formations.size)
        state = PlacementReducer.saveFormation(state)
        assertEquals(PlacementNotice.FormationSlotsFull, state.notice)
        state = PlacementReducer.deleteFormation(state, 0)
        assertEquals(4, state.formations.size)
        assertEquals(PlacementNotice.FormationDeleted, state.notice)
    }

    @Test
    fun aFormationThatNoLongerFitsIsNotLoaded() {
        // Two overlapping ships: the saved layout is invalid for this sea.
        val broken = Formation(
            listOf(
                SavedPlacement("CARRIER", 0, 0, 0),
                SavedPlacement("BATTLESHIP", 0, 0, 0),
                SavedPlacement("CRUISER", 4, 0, 0),
                SavedPlacement("SUBMARINE", 6, 0, 0),
                SavedPlacement("DESTROYER", 8, 0, 0),
            ),
        )
        val current = PlacementReducer.drop(PlacementReducer.startDrag(fresh(listOf(broken)), ShipClass.DESTROYER), Cell(9, 9))
        val state = PlacementReducer.loadFormation(current, 0)
        assertEquals(PlacementNotice.FormationInvalid, state.notice)
        assertEquals(current.placed, state.placed)
    }

    @Test
    fun aFormationWithAnUnknownShipIsNotLoaded() {
        val broken = Formation(listOf(SavedPlacement("UFO", 0, 0, 0)))
        val state = PlacementReducer.loadFormation(fresh(listOf(broken)), 0)
        assertEquals(PlacementNotice.FormationInvalid, state.notice)
    }

    @Test
    fun theFormationKeyChangesWithTheTerrain() {
        val plain = PlacementReducer.formationKey(board, fleet)
        val withIsland = Board(
            width = 10,
            height = 10,
            terrain = board.terrain.mapIndexed { i, t -> if (i == 0) pt.tiago.nightfleet.core.grid.Terrain.ISLAND else t },
        )
        assertFalse(plain == PlacementReducer.formationKey(withIsland, fleet))
        assertEquals(plain, PlacementReducer.formationKey(board, fleet))
    }

    @Test
    fun theFleetOfPlacementsMatchesTheEngineValidator() {
        assertTrue(PlacementReducer.validateAll(board, fleet, BattleFixtures.fixedPlacements()).isEmpty())
        val overlapping = BattleFixtures.fixedPlacements() + Placement(ShipClass.DESTROYER, Cell(0, 0))
        assertTrue(PlacementReducer.validateAll(board, fleet, overlapping).isNotEmpty())
    }
}
