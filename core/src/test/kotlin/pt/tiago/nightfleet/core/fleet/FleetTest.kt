package pt.tiago.nightfleet.core.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain

class FleetTest {

    @Test
    fun `classic fleet has five ships and 17 squares`() {
        assertEquals(5, Fleet.CLASSIC.ships.size)
        assertEquals(17, Fleet.CLASSIC.cellCount)
    }

    @Test
    fun `small fleet has 12 squares`() {
        assertEquals(12, Fleet.SMALL.cellCount)
    }

    @Test
    fun `tutorial fleet has 7 squares`() {
        assertEquals(7, Fleet.TUTORIAL.cellCount)
    }

    @Test
    fun `large fleet has 22 squares`() {
        assertEquals(22, Fleet.LARGE.cellCount)
    }

    @Test
    fun `school fleet has two seaplanes and two midget submarines`() {
        assertEquals(2, Fleet.SCHOOL.ships.count { it == ShipClass.SEAPLANE })
        assertEquals(2, Fleet.SCHOOL.ships.count { it == ShipClass.MIDGET_SUB })
        assertEquals(22, Fleet.SCHOOL.cellCount)
    }

    @Test
    fun `classic fleet uses the submarine class for its three-square submarine`() {
        assertEquals(1, Fleet.CLASSIC.ships.count { it == ShipClass.SUBMARINE })
    }

    @Test
    fun `there are five presets with distinct names`() {
        assertEquals(5, Fleet.PRESETS.size)
        assertEquals(5, Fleet.PRESETS.map { it.name }.toSet().size)
    }

    @Test
    fun `every preset passes the 25 percent density rule on its own grid`() {
        val boards = mapOf(
            Fleet.CLASSIC to Board.water(10, 10),
            Fleet.SMALL to Board.water(8, 8),
            Fleet.TUTORIAL to Board.water(8, 8),
            Fleet.LARGE to Board.water(12, 12),
            Fleet.SCHOOL to Board.water(10, 10),
        )
        for ((fleet, board) in boards) {
            assertTrue(fleet.name, PlacementValidator.fleetProblems(board, fleet).isEmpty())
        }
    }

    @Test
    fun `an empty fleet is rejected`() {
        try {
            Fleet("EMPTY", emptyList())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `custom fleet cell count sums its ships`() {
        val fleet = Fleet("CUSTOM", listOf(ShipClass.SEAPLANE, ShipClass.MIDGET_SUB, ShipClass.FREIGHTER))
        assertEquals(7, fleet.cellCount)
    }

    @Test
    fun `fleet density is measured against water squares only`() {
        val terrain = List(64) { if (it < 40) Terrain.ISLAND else Terrain.WATER }
        val board = Board(8, 8, terrain)
        // 24 water squares: 7 squares would be 29 %, so the fleet is over the limit.
        assertEquals(1, PlacementValidator.fleetProblems(board, Fleet.TUTORIAL).size)
    }

    @Test
    fun `fleet equal to exactly 25 percent of water is allowed`() {
        val terrain = List(16) { if (it < 8) Terrain.WATER else Terrain.ISLAND }
        val board = Board(4, 4, terrain)
        assertTrue(PlacementValidator.fleetProblems(board, Fleet("TWO", listOf(ShipClass.DESTROYER))).isEmpty())
    }

    @Test
    fun `fleets compare by name and ships`() {
        assertEquals(Fleet.CLASSIC, Fleet("CLASSIC", Fleet.CLASSIC.ships))
    }

    @Test
    fun `placement of a seaplane has four squares`() {
        assertEquals(4, Placement(ShipClass.SEAPLANE, pt.tiago.nightfleet.core.grid.Cell(0, 0), 2).cells().size)
    }
}
