package pt.tiago.nightfleet.game.freeplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.game.battle.MatchSetup

class FreePlayBuilderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun playerFleet(config: FreePlayConfig, terrain: List<Terrain>, seed: Long): List<Placement> {
        val base = FreePlayBuilder.baseRules(config, terrain)
        return (RandomPlacer.place(base.boardFor(0), base.fleetFor(0), SplitMix64(seed)) as PlacementResult.Success).placements
    }

    /** Every square touching a ship of [ships] (the ships' own squares included), row-major. */
    private fun touching(board: Board, ships: List<Placement>): Set<Int> {
        val out = HashSet<Int>()
        for (ship in ships) for (cell in ship.cells()) {
            val index = board.index(cell)
            out += index
            out += board.neighbours8(index).toSet()
        }
        return out
    }

    @Test
    fun theFleetFollowsTheGridAndTheChoice() {
        assertEquals(Fleet.SMALL, FreePlayConfig(grid = 8).fleetPreset)
        assertEquals(Fleet.LARGE, FreePlayConfig(grid = 12, fleet = FleetChoice.SCHOOL).fleetPreset)
        assertEquals(Fleet.SCHOOL, FreePlayConfig(grid = 10, fleet = FleetChoice.SCHOOL).fleetPreset)
        assertEquals(Fleet.CLASSIC, FreePlayConfig(grid = 10, fleet = FleetChoice.CLASSIC).fleetPreset)
    }

    @Test
    fun anImpossibleSetupIsReported() {
        assertTrue(FreePlayConfig().problems().isEmpty())
        assertEquals(listOf("GRID"), FreePlayConfig(grid = 9).problems())
        assertEquals(listOf("MINES"), FreePlayConfig(minesPerSide = 5).problems())
        assertEquals(listOf("SHOT_CLOCK"), FreePlayConfig(shotClock = 5).problems())
    }

    @Test
    fun theBaseRulesCarryTheOptionsAndTheDefaultWeapons() {
        val config = FreePlayConfig(
            grid = 12,
            salvo = true,
            diving = true,
            fog = true,
            movingShips = true,
            shotClock = 6,
            weapons = setOf(Weapon.SONAR, Weapon.TORPEDO),
        )
        val rules = FreePlayBuilder.baseRules(config, emptyList())
        assertEquals(12, rules.width)
        assertTrue(rules.salvo && rules.movingShips)
        assertNotNull(rules.diving)
        assertNotNull(rules.fog)
        assertEquals(6, rules.shotClockSeconds)
        assertEquals(setOf(Weapon.SONAR, Weapon.TORPEDO), rules.weapons.keys)
        assertTrue(rules.problems().isEmpty())
    }

    @Test
    fun withoutIslandsTheSeaIsAllWater() {
        assertTrue(FreePlayBuilder.terrainFor(FreePlayConfig(islands = false), seed = 3L).isEmpty())
    }

    @Test
    fun theIslandsAreOnlyLandAndTheFleetAlwaysFits() {
        for (grid in FreePlayConfig.GRID_SIZES) for (fleet in FleetChoice.entries) {
            val config = FreePlayConfig(grid = grid, fleet = fleet, islands = true)
            for (seed in 1L..12L) {
                val terrain = FreePlayBuilder.terrainFor(config, seed)
                if (terrain.isEmpty()) continue
                assertTrue("grid $grid seed $seed", terrain.all { it == Terrain.WATER || it == Terrain.ISLAND })
                assertTrue("grid $grid fleet $fleet seed $seed", TerrainGenerator.placeable(grid, grid, terrain, config.fleetPreset))
            }
        }
    }

    @Test
    fun theSameSeedGivesTheSameIslands() {
        val config = FreePlayConfig(islands = true)
        assertEquals(FreePlayBuilder.terrainFor(config, 42L), FreePlayBuilder.terrainFor(config, 42L))
    }

    @Test
    fun minesNeverTouchAShipOfTheirSea() {
        val config = FreePlayConfig(minesPerSide = 4)
        val terrain = emptyList<Terrain>()
        val player = playerFleet(config, terrain, seed = 17L)
        val setup = FreePlayBuilder.finish(config, terrain, attemptSeed = 555L, playerPlacements = player)
        assertNotNull(setup)
        setup!!
        for (side in 0..1) {
            val board = setup.rules.boardFor(side)
            val ships = setup.placements[side]
            val forbidden = touching(board, ships)
            assertEquals("mines of side $side", 4, board.mines.size)
            for (mine in board.mines) assertFalse("mine $mine of side $side touches a ship", mine in forbidden)
        }
    }

    @Test
    fun theSetupOfAGameIsAValidBattle() {
        val config = FreePlayConfig(
            grid = 10,
            islands = true,
            minesPerSide = 4,
            salvo = true,
            fog = true,
            diving = true,
            movingShips = true,
            weapons = setOf(Weapon.SONAR, Weapon.TORPEDO, Weapon.RECON, Weapon.CROSS_SHELL, Weapon.REPAIR, Weapon.DECOY),
            shotClock = 8,
        )
        val terrain = FreePlayBuilder.terrainFor(config, seed = 9L)
        val setup = FreePlayBuilder.finish(config, terrain, attemptSeed = 90L, playerPlacements = playerFleet(config, terrain, 4L))
        assertNotNull(setup)
        val created = setup!!.create()
        assertTrue(created is SetupResult.Created)
        val state = (created as SetupResult.Created).state
        assertEquals(1, state.sides[0].charges.getValue(Weapon.SONAR))
        assertEquals(0, state.sides[0].charges.getValue(Weapon.TORPEDO))
        assertEquals(0, state.sides[0].charges.getValue(Weapon.CROSS_SHELL))
        assertEquals(1, state.sides[0].charges.getValue(Weapon.REPAIR))
        assertEquals(8, state.rules.shotClockSeconds)
    }

    @Test
    fun theDecoyIsPlacedOnlyWhenTheWeaponIsInPlay() {
        val withDecoy = FreePlayConfig(weapons = setOf(Weapon.DECOY))
        val without = FreePlayConfig(weapons = setOf(Weapon.SONAR))
        val withSetup = FreePlayBuilder.finish(withDecoy, emptyList(), 7L, playerFleet(withDecoy, emptyList(), 2L))!!
        assertEquals(1, withSetup.decoys[0].size)
        assertEquals(1, withSetup.decoys[1].size)
        val withoutSetup = FreePlayBuilder.finish(without, emptyList(), 7L, playerFleet(without, emptyList(), 2L))!!
        assertTrue(withoutSetup.decoys.all { it.isEmpty() })
    }

    @Test
    fun theSameSeedAndPlacementGiveTheSameBattle() {
        val config = FreePlayConfig(minesPerSide = 3, islands = true, weapons = setOf(Weapon.DECOY))
        val terrain = FreePlayBuilder.terrainFor(config, seed = 5L)
        val player = playerFleet(config, terrain, seed = 8L)
        val first = FreePlayBuilder.finish(config, terrain, attemptSeed = 77L, playerPlacements = player)
        val second = FreePlayBuilder.finish(config, terrain, attemptSeed = 77L, playerPlacements = player)
        assertEquals(first, second)
        val other = FreePlayBuilder.finish(config, terrain, attemptSeed = 78L, playerPlacements = player)
        assertFalse(first == other)
    }

    @Test
    fun theStarterFollowsTheChoice() {
        assertEquals(0, FreePlayBuilder.firstPlayer(Starter.PLAYER, 1L))
        assertEquals(1, FreePlayBuilder.firstPlayer(Starter.ADMIRAL, 1L))
        val random = FreePlayBuilder.firstPlayer(Starter.RANDOM, 1L)
        assertTrue(random == 0 || random == 1)
        assertEquals(random, FreePlayBuilder.firstPlayer(Starter.RANDOM, 1L))
    }

    @Test
    fun theAdmiralFleetIsAlwaysPlaceableWithIslandsAndMines() {
        for (seed in 1L..40L) {
            val config = FreePlayConfig(grid = FreePlayConfig.GRID_SIZES[(seed % 3).toInt()], islands = true, minesPerSide = 4, weapons = setOf(Weapon.DECOY))
            val terrain = FreePlayBuilder.terrainFor(config, seed)
            val player = playerFleet(config, terrain, seed)
            assertNotNull("seed $seed", FreePlayBuilder.finish(config, terrain, attemptSeed = seed, playerPlacements = player))
        }
    }

    @Test
    fun aBrokenPlayerFleetIsNotAGame() {
        val config = FreePlayConfig()
        val player = playerFleet(config, emptyList(), 3L).dropLast(1)
        assertNull(FreePlayBuilder.finish(config, emptyList(), 1L, player))
    }

    @Test
    fun theLastSetupAndTheCounterAreRemembered() {
        val store = FreePlayStore(folder.newFolder("progress"))
        val state = FreePlayState(config = FreePlayConfig(grid = 8, fog = true, weapons = setOf(Weapon.TORPEDO)), attempts = 3)
        runBlocking {
            assertEquals(FreePlayState(), store.load())
            store.save(state)
            assertEquals(state, store.load())
        }
    }

    @Test
    fun eachGameDrawsItsOwnSeed() {
        assertFalse(FreePlayState(attempts = 0).nextAttemptSeed == FreePlayState(attempts = 1).nextAttemptSeed)
    }

    @Test
    fun theMatchSetupSurvivesAJsonRoundTrip() {
        val config = FreePlayConfig(minesPerSide = 2, weapons = setOf(Weapon.DECOY, Weapon.SONAR))
        val setup = FreePlayBuilder.finish(config, emptyList(), 21L, playerFleet(config, emptyList(), 6L))!!
        val encoded = pt.tiago.nightfleet.game.battle.SetupSave.encodeSetup(setup)
        assertEquals(setup, pt.tiago.nightfleet.game.battle.SetupSave.decodeSetup(encoded))
        assertTrue(setup is MatchSetup)
    }

    @Test
    fun theMinesOfAnEmptyOrFullSeaAreCounted() {
        val board = Board.water(8, 8)
        assertEquals(0, SetupGenerators.mines(board, emptyList(), count = 0, rng = SplitMix64(1L)).size)
        assertEquals(4, SetupGenerators.mines(board, emptyList(), count = 4, rng = SplitMix64(1L)).size)
        // A fleet on every square leaves no mine square: fewer mines than asked, never a crash.
        val full = (0 until 8).map { row -> Placement(pt.tiago.nightfleet.core.fleet.ShipClass.CRUISER, pt.tiago.nightfleet.core.grid.Cell(row, 0)) }
        assertTrue(SetupGenerators.mines(board, full, count = 4, rng = SplitMix64(1L)).size <= 4)
    }
}
