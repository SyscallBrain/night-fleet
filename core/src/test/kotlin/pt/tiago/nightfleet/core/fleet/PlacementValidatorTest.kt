package pt.tiago.nightfleet.core.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain

class PlacementValidatorTest {

    private val board = Board.water(8, 8)
    private val one = Fleet("ONE", listOf(ShipClass.DESTROYER))
    private val two = Fleet("TWO", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))

    private fun dest(row: Int, col: Int, rot: Int = 0) = Placement(ShipClass.DESTROYER, Cell(row, col), rot)

    private fun codes(problems: List<PlacementProblem>) = problems.map { it.code }

    @Test
    fun `a single valid ship has no problems`() {
        assertTrue(PlacementValidator.validate(board, one, listOf(dest(3, 3))).isEmpty())
    }

    @Test
    fun `two ships with a gap of one square are valid`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(0, 0), dest(0, 3)))
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `overlapping ships are reported`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(3, 4)))
        assertTrue(codes(problems).contains(ProblemCode.OVERLAP))
    }

    @Test
    fun `orthogonal touching is reported with both ship indices`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(4, 3)))
        val touching = problems.single { it.code == ProblemCode.TOUCHING }
        assertEquals(0, touching.shipIndex)
        assertEquals(1, touching.otherIndex)
    }

    @Test
    fun `diagonal touching is reported`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(4, 5)))
        assertEquals(listOf(ProblemCode.TOUCHING), codes(problems))
    }

    @Test
    fun `touching is reported once per pair`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(4, 3)))
        assertEquals(1, problems.count { it.code == ProblemCode.TOUCHING })
    }

    @Test
    fun `ships may touch when the rule allows it`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(4, 4)), shipsMayTouch = true)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `overlap is still reported when touching is allowed`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(3, 3), dest(3, 3)), shipsMayTouch = true)
        assertTrue(codes(problems).contains(ProblemCode.OVERLAP))
    }

    @Test
    fun `ship on an island is a terrain problem`() {
        val terrain = List(64) { if (it == 27) Terrain.ISLAND else Terrain.WATER }
        val problems = PlacementValidator.validate(Board(8, 8, terrain), one, listOf(dest(3, 2)))
        assertEquals(ProblemCode.TERRAIN, problems.single().code)
        assertEquals(Cell(3, 3), problems.single().cell)
    }

    @Test
    fun `ship on a reef is a terrain problem`() {
        val terrain = List(64) { if (it == 0) Terrain.REEF else Terrain.WATER }
        val problems = PlacementValidator.validate(Board(8, 8, terrain), one, listOf(dest(0, 0)))
        assertEquals(listOf(ProblemCode.TERRAIN), codes(problems))
    }

    @Test
    fun `ship beyond the right edge is out of bounds`() {
        val problems = PlacementValidator.validate(board, one, listOf(dest(0, 7)))
        assertEquals(listOf(ProblemCode.OUT_OF_BOUNDS), codes(problems))
    }

    @Test
    fun `ship at negative coordinates is out of bounds`() {
        val problems = PlacementValidator.validate(board, one, listOf(dest(-1, 0)))
        assertTrue(codes(problems).all { it == ProblemCode.OUT_OF_BOUNDS })
        assertEquals(2, problems.size)
    }

    @Test
    fun `vertical ship beyond the bottom edge is out of bounds`() {
        val problems = PlacementValidator.validate(board, one, listOf(dest(7, 0, rot = 1)))
        assertEquals(listOf(ProblemCode.OUT_OF_BOUNDS), codes(problems))
    }

    @Test
    fun `ship on a mine is a mine problem`() {
        val mined = Board(8, 8, List(64) { Terrain.WATER }, mines = listOf(27))
        val problems = PlacementValidator.validate(mined, one, listOf(dest(3, 2)))
        assertTrue(codes(problems).contains(ProblemCode.MINE))
    }

    @Test
    fun `ship next to a mine is a mine-near-ship problem`() {
        val mined = Board(8, 8, List(64) { Terrain.WATER }, mines = listOf(26))
        val problems = PlacementValidator.validate(mined, one, listOf(dest(3, 3)))
        assertTrue(codes(problems).contains(ProblemCode.MINE_NEAR_SHIP))
    }

    @Test
    fun `ship two squares away from a mine is valid`() {
        val mined = Board(8, 8, List(64) { Terrain.WATER }, mines = listOf(24))
        val problems = PlacementValidator.validate(mined, one, listOf(dest(3, 3)))
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `missing ship is an incomplete fleet`() {
        val problems = PlacementValidator.validate(board, two, listOf(dest(0, 0)))
        assertTrue(codes(problems).contains(ProblemCode.INCOMPLETE_FLEET))
    }

    @Test
    fun `extra ship is an incomplete fleet`() {
        val problems = PlacementValidator.validate(board, one, listOf(dest(0, 0), dest(5, 5)))
        assertTrue(codes(problems).contains(ProblemCode.INCOMPLETE_FLEET))
    }

    @Test
    fun `wrong ship class is an incomplete fleet`() {
        val problems = PlacementValidator.validate(board, one, listOf(Placement(ShipClass.CRUISER, Cell(0, 0))))
        assertTrue(codes(problems).contains(ProblemCode.INCOMPLETE_FLEET))
    }

    @Test
    fun `invalid rotation index is reported`() {
        val problems = PlacementValidator.validate(board, one, listOf(dest(0, 0, rot = 5)))
        assertEquals(listOf(ProblemCode.INVALID_ROTATION), codes(problems))
    }

    @Test
    fun `fleet over 25 percent of water is a density problem`() {
        val small = Board.water(4, 4)
        val fleet = Fleet("BIG", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER, ShipClass.DESTROYER))
        val problems = PlacementValidator.fleetProblems(small, fleet)
        assertEquals(listOf(ProblemCode.DENSITY_OVER_25_PERCENT), codes(problems))
    }

    @Test
    fun `validate reports density for an overfull fleet`() {
        val small = Board.water(4, 4)
        val fleet = Fleet("BIG", listOf(ShipClass.CARRIER))
        val problems = PlacementValidator.validate(small, fleet, listOf(Placement(ShipClass.CARRIER, Cell(0, 0))))
        assertTrue(codes(problems).contains(ProblemCode.DENSITY_OVER_25_PERCENT))
        assertTrue(codes(problems).contains(ProblemCode.OUT_OF_BOUNDS))
    }

    @Test
    fun `seaplane in the four rotations is valid on an empty sea`() {
        for (rot in 0 until 4) {
            val fleet = Fleet("SEA", listOf(ShipClass.SEAPLANE))
            val problems = PlacementValidator.validate(board, fleet, listOf(Placement(ShipClass.SEAPLANE, Cell(2, 2), rot)))
            assertTrue("rotation $rot", problems.isEmpty())
        }
    }

    @Test
    fun `seaplane touching a destroyer diagonally is reported`() {
        val fleet = Fleet("MIX", listOf(ShipClass.SEAPLANE, ShipClass.DESTROYER))
        val seaplane = Placement(ShipClass.SEAPLANE, Cell(0, 0), 0)
        val destroyer = dest(2, 2) // (2,2) is diagonal to the seaplane square (1,1)
        val problems = PlacementValidator.validate(board, fleet, listOf(seaplane, destroyer))
        assertTrue(codes(problems).contains(ProblemCode.TOUCHING))
    }

    @Test
    fun `decoy on an empty square is valid`() {
        assertTrue(PlacementValidator.validateDecoy(board, listOf(dest(0, 0)), Cell(5, 5)).isEmpty())
    }

    @Test
    fun `decoy touching a ship diagonally is reported`() {
        val problems = PlacementValidator.validateDecoy(board, listOf(dest(0, 0)), Cell(1, 2))
        assertEquals(listOf(ProblemCode.TOUCHING), codes(problems))
    }

    @Test
    fun `decoy on a ship square is an overlap`() {
        val problems = PlacementValidator.validateDecoy(board, listOf(dest(0, 0)), Cell(0, 1))
        assertEquals(listOf(ProblemCode.OVERLAP), codes(problems))
    }

    @Test
    fun `decoy outside the board is out of bounds`() {
        val problems = PlacementValidator.validateDecoy(board, emptyList(), Cell(8, 0))
        assertEquals(listOf(ProblemCode.OUT_OF_BOUNDS), codes(problems))
    }

    @Test
    fun `decoy on an island is a terrain problem`() {
        val terrain = List(64) { if (it == 9) Terrain.ISLAND else Terrain.WATER }
        val problems = PlacementValidator.validateDecoy(Board(8, 8, terrain), emptyList(), Cell(1, 1))
        assertEquals(listOf(ProblemCode.TERRAIN), codes(problems))
    }

    @Test
    fun `decoy touching a ship is allowed when ships may touch`() {
        val problems = PlacementValidator.validateDecoy(board, listOf(dest(0, 0)), Cell(1, 2), shipsMayTouch = true)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `problems are returned in a deterministic order for the same input`() {
        val placements = listOf(dest(3, 3), dest(4, 4), dest(9, 9))
        val a = PlacementValidator.validate(board, Fleet("THREE", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER, ShipClass.DESTROYER)), placements)
        val b = PlacementValidator.validate(board, Fleet("THREE", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER, ShipClass.DESTROYER)), placements)
        assertEquals(a, b)
    }
}
