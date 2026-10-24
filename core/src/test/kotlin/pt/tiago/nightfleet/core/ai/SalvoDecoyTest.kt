package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64

/** Salvo proposals, decoy placement and the water turns of the observation. */
class SalvoDecoyTest {

    @Test
    fun `a salvo gives the requested number of distinct unknown squares`() {
        for (tier in AiTier.entries) {
            val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT))
            val salvo = AdmiralBrain.decideSalvo(view, Admirals.NEUTRAL, tier, SplitMix64(3L), shots = 5)
            assertEquals("$tier", 5, salvo.size)
            assertEquals("$tier", salvo.size, salvo.toSet().size)
            assertTrue(salvo.all { view.kind(it) == KnownCell.UNKNOWN })
        }
    }

    @Test
    fun `a salvo never asks for more squares than are unknown`() {
        val kinds = (0 until 9).filter { it != 4 && it != 8 }.associate { "${'A' + it % 3}${it / 3 + 1}" to KnownCell.WATER }
        val view = SyntheticViews.blank(3, 3, kinds = kinds)
        val salvo = AdmiralBrain.decideSalvo(view, Admirals.NEUTRAL, AiTier.HARD, SplitMix64(1L), shots = 5)
        assertEquals(2, salvo.size)
    }

    @Test
    fun `a salvo of a single shot is the decision of the same view`() {
        val view = Scenarios.viewOfMover(Scenarios.midBattle(4L, shots = 10))
        val salvo = AdmiralBrain.decideSalvo(view, Admirals.HAZE, AiTier.HARD, SplitMix64(2L), shots = 1)
        assertEquals(1, salvo.size)
        assertEquals(KnownCell.UNKNOWN, view.kind(salvo[0]))
    }

    @Test
    fun `decoys touch no ship and no other decoy`() {
        val placement = (RandomPlacer.place(Board.water(10, 10), Fleet.CLASSIC, SplitMix64(5L)) as PlacementResult.Success).placements
        val board = Board.water(10, 10)
        val decoys = AdmiralPlacer.placeDecoys(board, placement, count = 4, profile = Admirals.ATOLL, rng = SplitMix64(9L))
        assertEquals(4, decoys.size)
        for (d in decoys) assertTrue(PlacementValidator.validateDecoy(board, placement, d).isEmpty())
        for (a in decoys) for (b in decoys) if (a != b) {
            assertFalse(Math.abs(a.row - b.row) <= 1 && Math.abs(a.col - b.col) <= 1)
        }
    }

    @Test
    fun `decoys are reproducible and follow the style`() {
        val placement = listOf(pt.tiago.nightfleet.core.fleet.Placement(ShipClass.CARRIER, pt.tiago.nightfleet.core.grid.Cell(0, 0), 0))
        val a = AdmiralPlacer.placeDecoys(Board.water(10, 10), placement, 3, Admirals.FROST, SplitMix64(1L))
        val b = AdmiralPlacer.placeDecoys(Board.water(10, 10), placement, 3, Admirals.FROST, SplitMix64(1L))
        assertEquals(a, b)
        // FROST likes the centre: its first decoy is near the middle of the sea.
        assertTrue(Math.abs(a[0].row - 4.5) <= 2 && Math.abs(a[0].col - 4.5) <= 2)
    }

    @Test
    fun `the observation exposes the turn of every direct water shot`() {
        val state = Scenarios.midBattle(8L, shots = 25)
        val observation = pt.tiago.nightfleet.core.observation.Observation.of(state, 0)
        for ((cell, turn) in observation.enemyWaterTurns) {
            assertEquals(KnownCell.WATER, observation.enemySea[cell])
            assertTrue(turn >= 0)
        }
        assertTrue(observation.enemyWaterTurns.isNotEmpty())
        assertEquals(observation.enemyWaterTurns, AiView.from(observation).waterTurns)
    }
}
