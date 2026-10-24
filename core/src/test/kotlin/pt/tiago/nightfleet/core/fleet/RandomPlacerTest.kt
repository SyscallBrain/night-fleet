package pt.tiago.nightfleet.core.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64

class RandomPlacerTest {

    private fun boardFor(fleet: Fleet): Board = when (fleet) {
        Fleet.LARGE -> Board.water(12, 12)
        Fleet.SMALL, Fleet.TUTORIAL -> Board.water(8, 8)
        else -> Board.water(10, 10)
    }

    private fun place(board: Board, fleet: Fleet, seed: Long, style: PlacementStyle = PlacementStyle.BALANCED): PlacementResult =
        RandomPlacer.place(board, fleet, SplitMix64(seed), style)

    private fun successOf(result: PlacementResult): List<Placement> = when (result) {
        is PlacementResult.Success -> result.placements
        is PlacementResult.Failure -> throw AssertionError("placement failed: ${result.reason}")
    }

    private fun borderSquares(placements: List<Placement>, board: Board): Int =
        placements.sumOf { p -> p.cells().count { it.row == 0 || it.col == 0 || it.row == board.height - 1 || it.col == board.width - 1 } }

    private fun coastalSquares(placements: List<Placement>, board: Board): Int =
        placements.sumOf { p ->
            p.cells().count { c -> board.neighbours8(board.index(c)).any { board.terrainAt(it) != Terrain.WATER } }
        }

    @Test
    fun `classic fleet on 10 by 10 is valid across 300 seeds`() {
        assertAllValid(Fleet.CLASSIC, 300, 1L)
    }

    @Test
    fun `small fleet on 8 by 8 is valid across 300 seeds`() {
        assertAllValid(Fleet.SMALL, 300, 2L)
    }

    @Test
    fun `tutorial fleet on 8 by 8 is valid across 300 seeds`() {
        assertAllValid(Fleet.TUTORIAL, 300, 3L)
    }

    @Test
    fun `large fleet on 12 by 12 is valid across 300 seeds`() {
        assertAllValid(Fleet.LARGE, 300, 4L)
    }

    @Test
    fun `school fleet with seaplanes is valid across 300 seeds`() {
        assertAllValid(Fleet.SCHOOL, 300, 5L)
    }

    /** The full requirement: 10 000 fleets per preset, valid and inside the node budget. */
    @Test
    fun `ten thousand fleets per preset are valid and within the node budget`() {
        val started = System.nanoTime()
        var maxNodes = 0
        for (fleet in Fleet.PRESETS) {
            val board = boardFor(fleet)
            for (seed in 0 until 10_000L) {
                val result = place(board, fleet, seed * 7919L + 17L)
                if (result !is PlacementResult.Success) {
                    throw AssertionError("${fleet.name} seed $seed failed: ${(result as PlacementResult.Failure).reason}")
                }
                assertTrue(PlacementValidator.validate(board, fleet, result.placements).isEmpty())
                maxNodes = maxOf(maxNodes, result.nodes)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("RandomPlacer: 50 000 fleets in $elapsedMs ms, max nodes $maxNodes (budget ${RandomPlacer.NODE_BUDGET})")
        assertTrue(maxNodes <= RandomPlacer.NODE_BUDGET)
    }

    @Test
    fun `same seed gives the same layout`() {
        val a = successOf(place(Board.water(10, 10), Fleet.CLASSIC, 99L))
        val b = successOf(place(Board.water(10, 10), Fleet.CLASSIC, 99L))
        assertEquals(a, b)
    }

    @Test
    fun `different seeds usually give different layouts`() {
        val layouts = (1L..20L).map { successOf(place(Board.water(10, 10), Fleet.CLASSIC, it)) }.toSet()
        assertTrue(layouts.size > 15)
    }

    @Test
    fun `placements follow the fleet order`() {
        val placements = successOf(place(Board.water(10, 10), Fleet.CLASSIC, 5L))
        assertEquals(Fleet.CLASSIC.ships, placements.map { it.shipClass })
    }

    @Test
    fun `placed ships never sit on land`() {
        val terrain = List(100) { if (it % 9 == 4) Terrain.ISLAND else Terrain.WATER }
        val board = Board(10, 10, terrain)
        for (seed in 0 until 50L) {
            val placements = successOf(place(board, Fleet.SMALL, seed))
            for (p in placements) for (c in p.cells()) assertEquals(Terrain.WATER, board.terrainAt(board.index(c)))
        }
    }

    @Test
    fun `placed ships avoid mines and the squares next to them`() {
        val mines = listOf(0, 55, 99)
        val board = Board(10, 10, List(100) { Terrain.WATER }, mines = mines)
        for (seed in 0 until 50L) {
            val placements = successOf(place(board, Fleet.SMALL, seed))
            assertTrue(PlacementValidator.validate(board, Fleet.SMALL, placements).isEmpty())
        }
    }

    @Test
    fun `balanced placement uses the interior as well as the edges`() {
        val board = Board.water(10, 10)
        val interior = (0L until 60L).sumOf { seed ->
            successOf(place(board, Fleet.CLASSIC, seed)).sumOf { p ->
                p.cells().count { it.row in 1..8 && it.col in 1..8 }
            }
        }
        assertTrue("interior squares $interior", interior > 0)
    }

    @Test
    fun `edge style places more ships on the border than balanced`() {
        val board = Board.water(10, 10)
        val edgeStyle = PlacementStyle(edges = 100)
        val balanced = (0L until 40L).sumOf { borderSquares(successOf(place(board, Fleet.CLASSIC, it)), board) }
        val edged = (0L until 40L).sumOf { borderSquares(successOf(place(board, Fleet.CLASSIC, it, edgeStyle)), board) }
        assertTrue("edged $edged vs balanced $balanced", edged > balanced)
    }

    @Test
    fun `negative centre weight pushes ships to the rim`() {
        val board = Board.water(10, 10)
        val rim = PlacementStyle(centre = -100)
        val centre = PlacementStyle(centre = 100)
        val rimBorder = (0L until 40L).sumOf { borderSquares(successOf(place(board, Fleet.CLASSIC, it, rim)), board) }
        val centreBorder = (0L until 40L).sumOf { borderSquares(successOf(place(board, Fleet.CLASSIC, it, centre)), board) }
        assertTrue("rim $rimBorder vs centre $centreBorder", rimBorder > centreBorder)
    }

    @Test
    fun `near-island style hugs the land`() {
        val terrain = List(100) { if (it == 44 || it == 45 || it == 54 || it == 55) Terrain.ISLAND else Terrain.WATER }
        val board = Board(10, 10, terrain)
        val plain = (0L until 40L).sumOf { coastalSquares(successOf(place(board, Fleet.CLASSIC, it)), board) }
        val hugging = (0L until 40L).sumOf { coastalSquares(successOf(place(board, Fleet.CLASSIC, it, PlacementStyle(nearIslands = 100))), board) }
        assertTrue("hugging $hugging vs plain $plain", hugging > plain)
    }

    @Test
    fun `cluster style keeps ships together`() {
        val board = Board.water(10, 10)
        fun spanOf(p: List<Placement>): Int {
            val rows = p.flatMap { it.cells() }.map { it.row }
            val cols = p.flatMap { it.cells() }.map { it.col }
            return (rows.max() - rows.min()) + (cols.max() - cols.min())
        }
        val clustered = (0L until 30L).sumOf { spanOf(successOf(place(board, Fleet.CLASSIC, it, PlacementStyle(cluster = 100)))) }
        val spread = (0L until 30L).sumOf { spanOf(successOf(place(board, Fleet.CLASSIC, it, PlacementStyle(spread = 100)))) }
        assertTrue("clustered $clustered vs spread $spread", clustered < spread)
    }

    @Test
    fun `checkerboard sea cannot hold a destroyer`() {
        val terrain = List(64) { if ((it / 8 + it % 8) % 2 == 0) Terrain.WATER else Terrain.ISLAND }
        val board = Board(8, 8, terrain)
        val fleet = Fleet("TWO", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))
        val result = place(board, fleet, 1L)
        assertTrue(result is PlacementResult.Failure)
        assertEquals(RandomPlacer.REASON_NO_LAYOUT, (result as PlacementResult.Failure).reason)
    }

    @Test
    fun `fleet denser than 25 percent is refused without searching`() {
        val board = Board.water(4, 4)
        val result = place(board, Fleet.CLASSIC, 1L)
        assertEquals(RandomPlacer.REASON_INVALID_FLEET, (result as PlacementResult.Failure).reason)
        assertEquals(0, result.nodes)
    }

    @Test
    fun `failure never exceeds the node budget`() {
        val terrain = List(144) { if ((it / 12 + it % 12) % 2 == 0) Terrain.WATER else Terrain.ISLAND }
        val board = Board(12, 12, terrain)
        val fleet = Fleet("MIDGETS", List(10) { ShipClass.MIDGET_SUB })
        val result = place(board, fleet, 3L)
        val nodes = when (result) {
            is PlacementResult.Success -> result.nodes
            is PlacementResult.Failure -> result.nodes
        }
        assertTrue(nodes <= RandomPlacer.NODE_BUDGET)
    }

    @Test
    fun `touching is allowed when the rule says so`() {
        val board = Board.water(8, 8)
        val fleet = Fleet("PACK", List(8) { ShipClass.MIDGET_SUB })
        val result = RandomPlacer.place(board, fleet, SplitMix64(4L), shipsMayTouch = true)
        val placements = successOf(result)
        assertTrue(PlacementValidator.validate(board, fleet, placements, shipsMayTouch = true).isEmpty())
    }

    @Test
    fun `placement style defaults to zero weights`() {
        assertEquals(PlacementStyle(), PlacementStyle.BALANCED)
    }

    private fun assertAllValid(fleet: Fleet, count: Int, base: Long) {
        val board = boardFor(fleet)
        for (i in 0 until count) {
            val result = place(board, fleet, base * 100_003L + i)
            val placements = successOf(result)
            assertTrue("${fleet.name} #$i", PlacementValidator.validate(board, fleet, placements).isEmpty())
            val nodes = (result as PlacementResult.Success).nodes
            assertTrue(nodes <= RandomPlacer.NODE_BUDGET)
        }
    }
}
