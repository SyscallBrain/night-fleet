package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet

/** Admiral presets, the free-play placer and the simulated human. */
class ProfilesAndPlayersTest {

    @Test
    fun `there are twelve admirals with unique ids`() {
        assertEquals(12, Admirals.ALL.size)
        assertEquals(12, Admirals.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `every admiral has two taunt keys named after it`() {
        for (admiral in Admirals.ALL) {
            assertEquals(2, admiral.tauntKeys.size)
            assertTrue(admiral.tauntKeys.all { it.startsWith("taunt_${admiral.id}_") })
        }
    }

    @Test
    fun `weapons follow the content table`() {
        assertEquals(setOf(Weapon.SONAR), Admirals.IRIS.weaponsAllowed)
        assertEquals(setOf(Weapon.RECON), Admirals.IRON.weaponsAllowed)
        assertEquals(Weapon.entries.toSet(), Admirals.LEVIATHAN.weaponsAllowed)
        assertEquals(Weapon.entries.toSet(), Admirals.grandAdmiralNox().weaponsAllowed)
    }

    @Test
    fun `captain shade copies a style chosen by seed`() {
        assertEquals(Admirals.captainShade(17L).placementStyle, Admirals.captainShade(17L).placementStyle)
        val styles = (0L..40L).map { Admirals.captainShade(it).placementStyle }.toSet()
        assertTrue("only ${styles.size} styles", styles.size >= 3)
    }

    @Test
    fun `grand admiral nox places like the style the player lost to`() {
        val history = PlacementStyle(edges = 3, cluster = 2)
        assertEquals(history, Admirals.grandAdmiralNox(history).placementStyle)
        assertEquals(PlacementStyle.BALANCED, Admirals.grandAdmiralNox().placementStyle)
    }

    @Test
    fun `invalid profile values are rejected`() {
        try {
            Admirals.NEUTRAL.copy(blunderBase = 1001)
            throw AssertionError("expected a rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("per-mille"))
        }
    }

    @Test
    fun `admiral placer gives valid layouts for every admiral`() {
        val fleet = Fleet.CLASSIC
        for (admiral in Admirals.ALL) for (seed in 1L..3L) {
            val result = AdmiralPlacer.place(Board.water(10, 10), fleet, admiral, SplitMix64(seed))
            val placements = (result as PlacementResult.Success).placements
            assertTrue(admiral.id, PlacementValidator.validate(Board.water(10, 10), fleet, placements).isEmpty())
        }
    }

    @Test
    fun `admiral placer is reproducible`() {
        val a = AdmiralPlacer.place(Board.water(10, 10), Fleet.CLASSIC, Admirals.FROST, SplitMix64(5L))
        val b = AdmiralPlacer.place(Board.water(10, 10), Fleet.CLASSIC, Admirals.FROST, SplitMix64(5L))
        assertEquals(a, b)
    }

    @Test
    fun `admiral placer reports a fleet that cannot fit`() {
        val crowded = Fleet("CROWDED", List(5) { ShipClass.CARRIER })
        val result = AdmiralPlacer.place(Board.water(8, 8), crowded, Admirals.IRIS, SplitMix64(1L))
        assertTrue(result is PlacementResult.Failure)
    }

    @Test
    fun `coral sets ships next to islands more often than frost`() {
        val islands = listOf(Cell(1, 1), Cell(1, 2), Cell(2, 1), Cell(2, 2))
        val terrain = List(100) { index -> if (Cell(index / 10, index % 10) in islands) Terrain.ISLAND else Terrain.WATER }
        val board = Board(10, 10, terrain)
        fun coastal(profile: AdmiralProfile): Int = (1L..30L).sumOf { seed ->
            val placements = (AdmiralPlacer.place(board, Fleet.CLASSIC, profile, SplitMix64(seed)) as PlacementResult.Success).placements
            placements.sumOf { p -> p.cells().count { c -> board.neighbours8(c.row * 10 + c.col).any { board.terrainAt(it) != Terrain.WATER } } }
        }
        assertTrue(coastal(Admirals.CORAL) > coastal(Admirals.FROST))
    }

    @Test
    fun `human players are reproducible`() {
        val a = BattleRunner.duel(RuleSet(), 11L, listOf(BattleRunner.Side(HumanModel.MEDIUM_PLAYER, Admirals.HAZE, AiTier.HARD), BattleRunner.Side(AdmiralBrain, Admirals.HAZE, AiTier.MEDIUM)))
        val b = BattleRunner.duel(RuleSet(), 11L, listOf(BattleRunner.Side(HumanModel.MEDIUM_PLAYER, Admirals.HAZE, AiTier.HARD), BattleRunner.Side(AdmiralBrain, Admirals.HAZE, AiTier.MEDIUM)))
        assertEquals(a, b)
    }

    @Test
    fun `human players finish battles without illegal moves`() {
        for (player in listOf(HumanModel.EASY_PLAYER, HumanModel.MEDIUM_PLAYER, HumanModel.HARD_PLAYER)) {
            for (seed in 1L..5L) {
                val outcome = BattleRunner.hunt(RuleSet(), seed, BattleRunner.Side(player, Admirals.NEUTRAL, AiTier.HARD))
                assertTrue(outcome.shots[0] > 0)
            }
        }
    }

    @Test
    fun `easy human players need more shots than hard ones`() {
        fun average(player: HumanModel): Double = (1L..30L).map { seed ->
            BattleRunner.hunt(RuleSet(), seed, BattleRunner.Side(player, Admirals.NEUTRAL, AiTier.HARD)).shots[0].toDouble()
        }.average()
        assertTrue(average(HumanModel.EASY_PLAYER) > average(HumanModel.HARD_PLAYER))
    }

    @Test
    fun `human players never shoot a known square`() {
        val view = Scenarios.viewOfMover(Scenarios.midBattle(3L, shots = 30))
        for (seed in 1L..20L) {
            val intent = HumanModel.HARD_PLAYER.decide(view, Admirals.NEUTRAL, AiTier.HARD, SplitMix64(seed))
            val cell = (intent as AiIntent.Fire).cell
            assertEquals(KnownCell.UNKNOWN, view.kind(view.index(cell)))
        }
    }

    @Test
    fun `seeds of the layout stream differ per side`() {
        assertNotEquals(SeedStreams.seed(5L, SeedStreams.Stream.LAYOUT, 0), SeedStreams.seed(5L, SeedStreams.Stream.LAYOUT, 1))
    }
}
