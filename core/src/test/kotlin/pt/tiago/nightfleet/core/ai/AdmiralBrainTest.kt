package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.engine.ShotLog
import pt.tiago.nightfleet.core.engine.ShotRecord
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64

/** The four tiers and their weapon policies, on real and hand-built views. */
class AdmiralBrainTest {

    private val neutral = Admirals.NEUTRAL

    private fun profile(
        weapons: Set<Weapon> = emptySet(),
        aggression: Int = 1000,
        delay: Int = 0,
        sonarThreshold: Int = 0,
        torpedoThreshold: Int = 0,
        blunder: Int = 0,
        bias: HuntBias = HuntBias.ADAPTIVE,
    ) = neutral.copy(
        weaponsAllowed = weapons,
        weaponAggression = aggression,
        weaponDelayTurns = delay,
        sonarThreshold = sonarThreshold,
        torpedoThreshold = torpedoThreshold,
        blunderBase = blunder,
        huntBias = bias,
    )

    private fun decide(
        view: AiView,
        profile: AdmiralProfile = neutral,
        tier: AiTier = AiTier.HARD,
        seed: Long = 1L,
    ): AiIntent = AdmiralBrain.decide(view, profile, tier, SplitMix64(seed))

    private fun fire(intent: AiIntent): Cell {
        assertTrue("expected Fire, got $intent", intent is AiIntent.Fire)
        return (intent as AiIntent.Fire).cell
    }

    @Test
    fun `easy never fires at a known square over many real battles`() = neverFiresAtKnown(AiTier.EASY)

    @Test
    fun `medium never fires at a known square over many real battles`() = neverFiresAtKnown(AiTier.MEDIUM)

    @Test
    fun `hard never fires at a known square over many real battles`() = neverFiresAtKnown(AiTier.HARD)

    @Test
    fun `relentless never fires at a known square over many real battles`() = neverFiresAtKnown(AiTier.RELENTLESS)

    private fun neverFiresAtKnown(tier: AiTier) {
        for (seed in 1L..12L) {
            val state = Scenarios.midBattle(seed, shots = (seed * 3).toInt())
            if (state.result != null) continue
            val view = Scenarios.viewOfMover(state)
            for (round in 0 until 3) {
                val intent = decide(view, Admirals.ALL[seed.toInt() % 12], tier, seed * 10 + round)
                val cell = (intent as AiIntent.Fire).cell
                assertEquals(KnownCell.UNKNOWN, view.kind(view.index(cell)))
            }
        }
    }

    @Test
    fun `every tier returns a legal engine action on a fresh battle`() {
        for (tier in AiTier.entries) {
            val state = Scenarios.midBattle(7L, shots = 0)
            val intent = decide(Scenarios.viewOfMover(state), tier = tier)
            assertTrue(intent.toAction() != null)
        }
    }

    @Test
    fun `decisions are a pure function of the view, profile, tier and rng`() {
        val view = Scenarios.viewOfMover(Scenarios.midBattle(9L, shots = 25))
        for (tier in AiTier.entries) {
            assertEquals(decide(view, Admirals.FROST, tier, 4L), decide(view, Admirals.FROST, tier, 4L))
        }
    }

    @Test
    fun `easy and medium follow an open hit to a neighbour`() {
        val kinds = mapOf("D4" to KnownCell.HIT)
        for (tier in listOf(AiTier.EASY, AiTier.MEDIUM, AiTier.HARD, AiTier.RELENTLESS)) {
            for (seed in 1L..10L) {
                val view = SyntheticViews.blank(8, 8, kinds = kinds)
                val cell = fire(decide(view, tier = tier, seed = seed))
                val neighbours = setOf(Cell(2, 3), Cell(4, 3), Cell(3, 2), Cell(3, 4))
                assertTrue("$tier chose $cell", cell in neighbours)
            }
        }
    }

    @Test
    fun `medium finishes a line of two hits at one of its open ends`() {
        // A carrier hit at D4 and E4: the open ends of the line are C4 and F4.
        val kinds = mapOf("D4" to KnownCell.HIT, "E4" to KnownCell.HIT)
        for (seed in 1L..10L) {
            val view = SyntheticViews.blank(8, 8, classes = listOf(ShipClass.CARRIER), kinds = kinds)
            val cell = fire(decide(view, tier = AiTier.MEDIUM, seed = seed))
            assertTrue("chose $cell", cell == Cell(3, 2) || cell == Cell(3, 5))
        }
    }

    @Test
    fun `hard keeps shooting along the row of its carrier`() {
        val kinds = mapOf("D4" to KnownCell.HIT, "E4" to KnownCell.HIT)
        val view = SyntheticViews.blank(8, 8, classes = listOf(ShipClass.CARRIER), kinds = kinds)
        val cell = fire(decide(view, tier = AiTier.HARD))
        assertEquals(3, cell.row)
        assertFalse(cell.col == 3 || cell.col == 4)
    }

    @Test
    fun `medium hunts on the parity of the smallest live ship`() {
        val view = SyntheticViews.blank(8, 8, classes = listOf(ShipClass.CARRIER, ShipClass.DESTROYER))
        for (seed in 1L..20L) {
            val cell = fire(decide(view, tier = AiTier.MEDIUM, seed = seed))
            assertEquals(0, (cell.row + cell.col) % 2)
        }
    }

    @Test
    fun `medium with a parity phase of one hunts the odd squares`() {
        val view = SyntheticViews.blank(8, 8)
        val odd = neutral.copy(parityPhase = 1)
        for (seed in 1L..20L) {
            val cell = fire(decide(view, odd, AiTier.MEDIUM, seed))
            assertEquals(1, (cell.row + cell.col) % 2)
        }
    }

    @Test
    fun `a blunder of one thousand per-mille fires at random unknown squares`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("A1" to KnownCell.HIT, "B1" to KnownCell.HIT))
        val blunder = neutral.copy(blunderBase = 1000)
        val cells = (1L..60L).map { fire(decide(view, blunder, AiTier.EASY, it)) }.toSet()
        assertTrue("only ${cells.size} distinct squares", cells.size > 20)
    }

    @Test
    fun `relentless has no blunders`() {
        assertEquals(0, AdmiralBrain.blunderPermille(neutral.copy(blunderBase = 900), AiTier.RELENTLESS))
    }

    @Test
    fun `blunder rates scale by tier`() {
        val p = neutral.copy(blunderBase = 400)
        assertEquals(400, AdmiralBrain.blunderPermille(p, AiTier.EASY))
        assertEquals(240, AdmiralBrain.blunderPermille(p, AiTier.MEDIUM))
        assertEquals(100, AdmiralBrain.blunderPermille(p, AiTier.HARD))
    }

    @Test
    fun `no unknown square is an error`() {
        val view = SyntheticViews.blank(3, 3, kinds = (0 until 9).associate { "${'A' + it % 3}${it / 3 + 1}" to KnownCell.WATER })
        try {
            decide(view)
            fail("expected an error")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("No square"))
        }
    }

    @Test
    fun `weapons wait for the delay`() {
        val view = SyntheticViews.blank(8, 8, turn = 2, charges = mapOf(Weapon.SONAR to 1))
        val p = profile(weapons = setOf(Weapon.SONAR), delay = 5)
        assertTrue(decide(view, p, AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `no charge means no weapon`() {
        val view = SyntheticViews.blank(8, 8)
        val p = profile(weapons = setOf(Weapon.SONAR))
        assertTrue(decide(view, p, AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `a weapon that is charged but not allowed by the admiral is not used`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.TORPEDO to 1))
        val p = profile(weapons = setOf(Weapon.SONAR))
        assertTrue(decide(view, p, AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `easy uses sonar at random`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.SONAR to 1))
        val p = profile(weapons = setOf(Weapon.SONAR))
        assertTrue(decide(view, p, AiTier.EASY) is AiIntent.Sonar)
    }

    @Test
    fun `hard sonars the best window in hunt mode`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.SONAR to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.SONAR)), AiTier.HARD)
        assertTrue(intent is AiIntent.Sonar)
        val center = (intent as AiIntent.Sonar).center
        assertTrue(center.row in 1..6 && center.col in 1..6)
    }

    @Test
    fun `hard does not sonar when the threshold is not reached`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.SONAR to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.SONAR), sonarThreshold = 1000), AiTier.HARD)
        assertTrue(intent is AiIntent.Fire)
    }

    @Test
    fun `hard fires a cross when a target has three unknown squares in its cross`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT), charges = mapOf(Weapon.CROSS_SHELL to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.CROSS_SHELL)), AiTier.HARD)
        assertTrue("was $intent", intent is AiIntent.Cross)
    }

    @Test
    fun `hard torpedoes a line with density when the threshold is zero`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.TORPEDO to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.TORPEDO)), AiTier.HARD)
        assertTrue("was $intent", intent is AiIntent.Torpedo)
    }

    @Test
    fun `hard reads a line in hunt mode when recon is charged`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.RECON to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.RECON)), AiTier.HARD)
        assertTrue("was $intent", intent is AiIntent.Recon)
    }

    @Test
    fun `recon never reads the same line twice`() {
        // Rows 0 to 6 were read and came up empty, so the only ships are on row 7: the next recon must be elsewhere.
        val readings = (0 until 7).map { ReconReading(isRow = true, index = it, present = false, turn = 1) }
        val view = SyntheticViews.blank(8, 8, readings = readings, charges = mapOf(Weapon.RECON to 1))
        val intent = decide(view, profile(weapons = setOf(Weapon.RECON)), AiTier.HARD)
        assertTrue("was $intent", intent is AiIntent.Recon)
        intent as AiIntent.Recon
        assertFalse(intent.isRow && intent.index in 0..6)
    }

    @Test
    fun `repair mends a length-four ship one hit from sunk`() {
        val ship = SyntheticViews.ownShip(ShipClass.BATTLESHIP, row = 0, col = 0, hits = listOf(0, 1, 2))
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.REPAIR to 1), ownShips = listOf(ship))
        val intent = decide(view, profile(weapons = setOf(Weapon.REPAIR)), AiTier.HARD)
        assertEquals(AiIntent.Repair(Cell(0, 0)), intent)
    }

    @Test
    fun `repair is not used on a ship with two squares left`() {
        val ship = SyntheticViews.ownShip(ShipClass.BATTLESHIP, row = 0, col = 0, hits = listOf(0, 1))
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.REPAIR to 1), ownShips = listOf(ship))
        assertTrue(decide(view, profile(weapons = setOf(Weapon.REPAIR)), AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `move slides a cruiser that enemy shots surround`() {
        val ship = SyntheticViews.ownShip(ShipClass.CRUISER, row = 4, col = 3)
        val shots = ShotLog(
            listOf(3 * 8 + 3, 3 * 8 + 4, 3 * 8 + 5).map { ShotRecord(turn = 9, attacker = 1, cell = it, result = ShotResult.Miss) },
        )
        val rules = SyntheticViews.rules(8, 8, listOf(ShipClass.CRUISER), movingShips = true)
        val view = SyntheticViews.blank(8, 8, rules = rules, ownShips = listOf(ship), incoming = shots)
        val intent = decide(view, neutral, AiTier.HARD)
        assertTrue("was $intent", intent is AiIntent.Move)
        intent as AiIntent.Move
        assertEquals(0, intent.shipIndex)
        assertTrue(intent.delta == 1 || intent.delta == -1)
    }

    @Test
    fun `move is not proposed after the one move of the battle`() {
        val ship = SyntheticViews.ownShip(ShipClass.CRUISER, row = 4, col = 3)
        val shots = ShotLog(listOf(3 * 8 + 3, 3 * 8 + 4, 3 * 8 + 5).map { ShotRecord(9, 1, it, ShotResult.Miss) })
        val rules = SyntheticViews.rules(8, 8, listOf(ShipClass.CRUISER), movingShips = true)
        val view = SyntheticViews.blank(8, 8, rules = rules, ownShips = listOf(ship), incoming = shots, moveUsed = true)
        assertTrue(decide(view, neutral, AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `relentless uses its weapons whatever the aggression, hard does not`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.SONAR to 1))
        val p = profile(weapons = setOf(Weapon.SONAR), aggression = 0)
        assertTrue(decide(view, p, AiTier.RELENTLESS) is AiIntent.Sonar)
        assertTrue(decide(view, p, AiTier.HARD) is AiIntent.Fire)
    }

    @Test
    fun `aggression zero never uses a weapon`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.SONAR to 1, Weapon.TORPEDO to 1))
        val p = profile(weapons = setOf(Weapon.SONAR, Weapon.TORPEDO), aggression = 0)
        for (seed in 1L..10L) assertTrue(decide(view, p, AiTier.HARD, seed) is AiIntent.Fire)
    }
}
