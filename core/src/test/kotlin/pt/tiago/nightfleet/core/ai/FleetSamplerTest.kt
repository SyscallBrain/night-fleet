package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64

/** The sampler returns complete fleets that satisfy every constraint of the view. */
class FleetSamplerTest {

    private fun assertConsistent(k: Knowledge, fleet: List<Placement>, expected: List<ShipClass>) {
        assertEquals(expected.sorted(), fleet.map { it.shipClass }.sorted())
        val owner = IntArray(k.n) { -1 }
        fleet.forEachIndexed { i, placement ->
            for (cell in placement.cells()) {
                val x = cell.row * k.w + cell.col
                assertEquals("square $x is shared", -1, owner[x])
                owner[x] = i
                assertTrue("square $x may not hold a ship", k.allowed(x, weakExcluded = false))
                assertTrue("contact needs a submarine", !k.contact[x] || placement.shipClass.submersible)
            }
        }
        for (x in k.mustList) assertTrue("must-cover square $x is not covered", owner[x] >= 0)
        if (!k.shipsMayTouch) {
            for (x in 0 until k.n) if (owner[x] >= 0) for (y in k.nb8[x]) {
                if (owner[y] >= 0) assertEquals("ships touch", owner[x], owner[y])
            }
        }
        for (s in k.sonar) {
            val count = s.box.count { owner[it] >= 0 && !k.hit[it] }
            assertEquals("sonar window", s.count, count)
        }
        for (r in k.recon) {
            val any = r.line.any { owner[it] >= 0 && !k.hit[it] }
            assertEquals("recon line", r.present, any)
        }
    }

    /** A late-game view of a real battle whose consistent fleets can be enumerated completely. */
    private fun lateView(): AiView? {
        for (seed in 1L..40L) {
            val state = Scenarios.midBattle(seed, shots = 72)
            if (state.result != null) continue
            val view = Scenarios.viewOfMover(state)
            if (FleetSampler.sample(Knowledge(view), SplitMix64(1L), samples = 4_000) != null) return view
        }
        return null
    }

    /** A small view with exactly two consistent fleets: a destroyer must cover the hit at D4. */
    private fun tinyView(): AiView = SyntheticViews.blank(
        4,
        4,
        classes = listOf(ShipClass.DESTROYER),
        kinds = mapOf("D4" to KnownCell.HIT, "A1" to KnownCell.WATER, "B1" to KnownCell.WATER),
        readings = listOf(SyntheticViews.sonar("A2", 0)),
    )

    @Test
    fun `a complete enumeration of a late battle satisfies every constraint`() {
        val view = lateView() ?: error("no late view could be enumerated")
        val k = Knowledge(view)
        val expected = view.enemyRemaining.flatMap { e -> List(e.count) { e.shipClass } }
        val result = FleetSampler.sample(k, SplitMix64(7L), samples = 4_000, keepFleets = true)!!
        assertTrue(result.samples > 0)
        assertEquals(result.samples, result.fleets.size)
        for (fleet in result.fleets) assertConsistent(k, fleet, expected)
    }

    @Test
    fun `a tiny view enumerates both fleets and respects sonar and hit`() {
        val view = tinyView()
        val k = Knowledge(view)
        val result = FleetSampler.sample(k, SplitMix64(3L), samples = 100, keepFleets = true)
        assertNotNull(result)
        assertEquals(2, result!!.samples)
        for (fleet in result.fleets) assertConsistent(k, fleet, listOf(ShipClass.DESTROYER))
    }

    @Test
    fun `a complete enumeration does not depend on the rng`() {
        val k = Knowledge(tinyView())
        val a = FleetSampler.sample(k, SplitMix64(1L), samples = 100)!!
        val b = FleetSampler.sample(k, SplitMix64(99L), samples = 100)!!
        assertTrue(a.freq.contentEquals(b.freq))
    }

    @Test
    fun `a partial enumeration gives no samples`() {
        // The cap of wanted fleets is reached before the enumeration is complete.
        assertNull(FleetSampler.sample(Knowledge(tinyView()), SplitMix64(1L), samples = 1))
    }

    @Test
    fun `frequencies sit only on unknown squares`() {
        val view = lateView() ?: error("no late view")
        val k = Knowledge(view)
        val freq = FleetSampler.sample(k, SplitMix64(9L), samples = 4_000)!!.freq
        for (x in 0 until k.n) if (freq[x] > 0) assertTrue("square $x", k.unknown[x])
        assertTrue(freq.any { it > 0 })
    }

    @Test
    fun `sonar readings are exact in a complete enumeration`() {
        val k = Knowledge(tinyView())
        val fleets = FleetSampler.sample(k, SplitMix64(4L), samples = 100, keepFleets = true)!!.fleets
        // A2 is a sonar centre with count 0: no destroyer square may lie in its window.
        for (fleet in fleets) for (cell in fleet.flatMap { it.cells() }) {
            assertTrue(cell.row !in 0..2 || cell.col !in 0..1)
        }
    }

    @Test
    fun `an inconsistent view runs out of budget and the brain falls back to density`() {
        // One destroyer cannot cover four hits that are far apart: no fleet is consistent with it.
        val kinds = mapOf("A1" to KnownCell.HIT, "H1" to KnownCell.HIT, "A8" to KnownCell.HIT, "H8" to KnownCell.HIT)
        val view = SyntheticViews.blank(8, 8, classes = listOf(ShipClass.DESTROYER), kinds = kinds)
        val before = AiStats.samplerFallbackCount
        AdmiralBrain.decide(view, Admirals.NEUTRAL, AiTier.RELENTLESS, SplitMix64(1L))
        assertTrue(AiStats.samplerFallbackCount > before)
    }

    @Test
    fun `a class with no legal placement gives no samples`() {
        val view = SyntheticViews.blank(3, 3, classes = listOf(ShipClass.CARRIER))
        assertNull(FleetSampler.sample(Knowledge(view), SplitMix64(1L)))
    }
}
