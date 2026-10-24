package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.observation.KnownCell

/** Hand-checked counts on small boards: density, exact constraints and their edge cases. */
class KnowledgeAndDensityTest {

    private fun density(view: AiView, target: Boolean = false, weakExcluded: Boolean = true): Density =
        Density.compute(Knowledge(view), target, weakExcluded)

    @Test
    fun `three by three destroyer has centre 4 placements, edges 3 and corners 2`() {
        val d = density(SyntheticViews.blank(3, 3))
        assertEquals(12, d.placements)
        assertEquals(4L * Density.SCALE / 12, d.score[4])
        assertEquals(3L * Density.SCALE / 12, d.score[1])
        assertEquals(2L * Density.SCALE / 12, d.score[0])
    }

    @Test
    fun `four by four destroyer has 24 placements`() {
        assertEquals(24, density(SyntheticViews.blank(4, 4)).placements)
    }

    @Test
    fun `seaplane on three by three has eight placements in four rotations`() {
        val shape = Geometry.of(3, 3, ShipClass.SEAPLANE)
        assertEquals(8, shape.placements.size)
        assertEquals(setOf(0, 1, 2, 3), shape.placements.map { it.rotationIndex }.toSet())
    }

    @Test
    fun `seaplane density covers the stem square of every rotation`() {
        val view = SyntheticViews.blank(3, 3, classes = listOf(ShipClass.SEAPLANE))
        val d = density(view)
        assertTrue(d.total > 0)
        assertTrue(d.score[4] > 0)
    }

    @Test
    fun `contact square needs a submarine`() {
        val contact = SyntheticViews.blank(3, 3, classes = listOf(ShipClass.DESTROYER), kinds = mapOf("B2" to KnownCell.CONTACT))
        assertEquals(0L, density(contact).total)
        val sub = SyntheticViews.blank(3, 3, classes = listOf(ShipClass.SUBMARINE), kinds = mapOf("B2" to KnownCell.CONTACT))
        assertTrue(density(sub).total > 0)
    }

    @Test
    fun `diagonal of an open hit is wasted  no ship can be there`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT))
        val d = density(view, target = true)
        assertEquals(0L, d.score[0])
        assertTrue(d.score[1] > 0)
    }

    @Test
    fun `target mode weighs the placements that cover the open hit by twenty`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT))
        val d = density(view, target = true)
        // Four placements cover the hit, each weighing 20, and each orthogonal neighbour is covered by one of them.
        assertEquals(20L * Density.SCALE / 80, d.score[1])
    }

    @Test
    fun `sunk ship blocks its neighbours`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("A1" to KnownCell.SUNK, "B1" to KnownCell.SUNK))
        val d = density(view)
        assertEquals(0L, d.score[2])
        assertEquals(0L, d.score[3])
        assertTrue(d.score[7] > 0)
    }

    @Test
    fun `mine blocks its eight neighbours`() {
        val view = SyntheticViews.blank(4, 4, kinds = mapOf("B2" to KnownCell.MINE))
        val d = density(view)
        assertEquals(0L, d.score[0])
        assertTrue(d.score[12] > 0)
    }

    @Test
    fun `sonar window with count zero leaves no ship inside it`() {
        val view = SyntheticViews.blank(4, 4, readings = listOf(SyntheticViews.sonar("A1", 0)))
        val d = density(view)
        assertEquals(0L, d.score[0])
        assertEquals(0L, d.score[5])
        assertTrue(d.total > 0)
    }

    @Test
    fun `recon that finds nothing on a row excludes surface ships on it`() {
        val view = SyntheticViews.blank(
            4,
            4,
            readings = listOf(ReconReading(isRow = true, index = 0, present = false, turn = 3)),
        )
        val d = density(view)
        assertEquals(0L, d.score[0])
        assertTrue(d.score[5] > 0)
    }

    @Test
    fun `weak water is used only when nothing else fits`() {
        // D4 is the only unknown square and every neighbour of it is weak water: a ship on D4 needs weak water.
        val kinds = (0 until 16).filter { it != 15 }.associate { i -> "${'A' + i % 4}${i / 4 + 1}" to KnownCell.WATER }
        // D4 (15) is not a miss of the viewer, so it is not in the shot log.
        val view = SyntheticViews.blank(4, 4, kinds = kinds, enemyShipMovedAtTurn = 5, waterTurns = (0 until 15).associateWith { 1 })
        assertEquals(0L, density(view, weakExcluded = true).total)
        val fallback = Density.forKnowledge(Knowledge(view))
        assertTrue(fallback.total > 0)
        assertTrue(fallback.score[15] > 0)
    }

    @Test
    fun `water shot after the enemy move is not weak evidence`() {
        val kinds = (0 until 9).associate { i -> "${'A' + i % 3}${i / 3 + 1}" to KnownCell.WATER }
        val view = SyntheticViews.blank(3, 3, kinds = kinds, enemyShipMovedAtTurn = 5, waterTurns = (0 until 9).associateWith { 7 })
        assertFalse(view.isWeakWater(0))
    }

    @Test
    fun `repaired square pulls the placements that cover it`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("A1" to KnownCell.REPAIRED))
        val d = density(view)
        assertTrue(d.score[1] > d.score[8])
    }

    @Test
    fun `a placement that touches an open hit it does not cover is excluded`() {
        val k = Knowledge(SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT)))
        assertFalse(k.candidate(false, intArrayOf(0, 1), weakExcluded = true))
        assertTrue(k.candidate(false, intArrayOf(3, 4), weakExcluded = true))
    }

    @Test
    fun `a placement made only of open hits is not a live ship`() {
        val k = Knowledge(SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT, "B3" to KnownCell.HIT)))
        assertFalse(k.candidate(false, intArrayOf(4, 5), weakExcluded = true))
    }

    @Test
    fun `contact on a square makes non-submarine placements there impossible`() {
        val k = Knowledge(SyntheticViews.blank(3, 3, kinds = mapOf("A1" to KnownCell.CONTACT)))
        assertFalse(k.candidate(false, intArrayOf(0, 1), weakExcluded = true))
        assertTrue(k.candidate(true, intArrayOf(0, 1), weakExcluded = true))
    }

    @Test
    fun `shipsMayTouch lifts the touching rules`() {
        val rules = SyntheticViews.rules(3, 3, listOf(ShipClass.DESTROYER), touch = true)
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT), rules = rules)
        val k = Knowledge(view)
        assertTrue(k.candidate(false, intArrayOf(0, 1), weakExcluded = true))
    }

    @Test
    fun `density of a board with no placement is zero`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.ISLAND))
        val d = density(view)
        assertTrue(d.total > 0)
        val blocked = SyntheticViews.blank(
            3,
            3,
            kinds = (0 until 9).filter { it != 4 }.associate { "${'A' + it % 3}${it / 3 + 1}" to KnownCell.ISLAND },
        )
        assertEquals(0L, density(blocked).total)
    }

    @Test
    fun `line probabilities are scaled and capped at one`() {
        val d = density(SyntheticViews.blank(3, 3))
        assertTrue(d.rowP.all { it in 0..Density.SCALE })
        assertTrue(d.colP.all { it in 0..Density.SCALE })
    }
}
