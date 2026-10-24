package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Fx.BOTTOM_RIGHT
import pt.tiago.nightfleet.core.engine.Fx.TOP_LEFT
import pt.tiago.nightfleet.core.engine.Fx.dest
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.grid.Board

/** BattleSetup: validation of rules, placements and mission parameters, and the initial state. */
class BattleSetupTest {

    private val rules = Fx.rules(fleet = Fx.TWIN)

    private fun create(
        placements: List<List<Placement>?> = listOf(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(TOP_LEFT, dest(3, 4))),
        rules: RuleSet = this.rules,
        mission: MissionType = MissionType.DUEL,
        firstPlayer: Int = 0,
        budget: Int? = null,
        seed: Long = 1L,
    ): SetupResult = BattleSetup.create(rules, mission, placements, firstPlayer, seed, budget)

    private fun reasonsOf(result: SetupResult): List<String> =
        (result as? SetupResult.Rejected)?.reasons ?: throw AssertionError("expected rejection, got $result")

    private fun stateOf(result: SetupResult): BattleState =
        (result as? SetupResult.Created)?.state ?: throw AssertionError("expected created, got $result")

    @Test
    fun `valid duel placements create a battle`() {
        assertTrue(create() is SetupResult.Created)
    }

    @Test
    fun `new battle starts at turn zero and round zero with the first player to act`() {
        val s = stateOf(create(firstPlayer = 1))
        assertEquals(0, s.turn)
        assertEquals(0, s.round)
        assertEquals(1, s.current)
        assertNull(s.result)
    }

    @Test
    fun `new battle records the engine version and the attempt seed`() {
        val s = stateOf(create(seed = 77L))
        assertEquals(ENGINE_VERSION, s.engineVersion)
        assertEquals(77L, s.attemptSeed)
    }

    @Test
    fun `new battle has no skips pending and every weapon charge at zero`() {
        val s = stateOf(create())
        assertEquals(listOf(0, 0), s.pendingSkips)
        for (side in s.sides) {
            assertEquals(Weapon.entries, side.charges.keys.toList())
            assertTrue(side.charges.values.all { it == 0 })
        }
    }

    @Test
    fun `each side starts with an empty log, no streak and no used abilities`() {
        val s = stateOf(create())
        for (side in s.sides) {
            assertTrue(side.incoming.entries.isEmpty())
            assertEquals(0, side.hitStreak)
            assertTrue(!side.moveUsed && !side.repairUsed)
        }
    }

    @Test
    fun `overlapping placements are rejected with the side and the problem`() {
        val reasons = reasonsOf(create(placements = listOf(listOf(dest(0, 0), dest(0, 1)), listOf(TOP_LEFT, dest(3, 4)))))
        assertTrue(reasons.contains("PLACEMENT side 0: OVERLAP"))
    }

    @Test
    fun `touching placements are rejected`() {
        val reasons = reasonsOf(create(placements = listOf(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(dest(0, 0), dest(1, 2)))))
        assertTrue(reasons.contains("PLACEMENT side 1: TOUCHING"))
    }

    @Test
    fun `a fleet that does not match the rules is rejected`() {
        val reasons = reasonsOf(create(placements = listOf(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT, dest(3, 4)))))
        assertTrue(reasons.contains("PLACEMENT side 0: INCOMPLETE_FLEET"))
    }

    @Test
    fun `a sea with a ship on land is rejected`() {
        val terrain = List(64) { if (it == 1) Terrain.ISLAND else Terrain.WATER }
        val reasons = reasonsOf(create(rules = Fx.rules(fleet = Fx.TWIN, terrain = terrain)))
        assertTrue(reasons.any { it.startsWith("PLACEMENT side 1: TERRAIN") })
    }

    @Test
    fun `hidden fleets are generated from the attempt seed`() {
        val hidden = listOf(null, null)
        val a = stateOf(BattleSetup.create(RuleSet(), MissionType.DUEL, hidden, 0, 1234L))
        val b = stateOf(BattleSetup.create(RuleSet(), MissionType.DUEL, hidden, 0, 1234L))
        assertEquals(a.sides.map { it.ships }, b.sides.map { it.ships })
    }

    @Test
    fun `different attempt seeds give different hidden fleets`() {
        val hidden = listOf(null, null)
        val a = stateOf(BattleSetup.create(RuleSet(), MissionType.DUEL, hidden, 0, 1L))
        val b = stateOf(BattleSetup.create(RuleSet(), MissionType.DUEL, hidden, 0, 2L))
        assertNotEquals(a.sides.map { it.ships }, b.sides.map { it.ships })
    }

    @Test
    fun `generated hidden fleets are valid layouts`() {
        val s = stateOf(BattleSetup.create(RuleSet(), MissionType.DUEL, listOf(null, null), 0, 9L))
        for (side in s.sides) {
            val placements = side.ships.map { it.placement }
            assertTrue(PlacementValidator.validate(side.board, s.rules.fleet, placements).isEmpty())
        }
    }

    @Test
    fun `a hidden fleet can be mixed with a given fleet`() {
        val s = stateOf(BattleSetup.create(Fx.rules(fleet = Fx.PAIR), MissionType.HUNT, listOf(listOf(BOTTOM_RIGHT), null), 0, 5L, 10))
        assertEquals(BOTTOM_RIGHT, s.sides[0].ships.single().placement)
        assertEquals(1, s.sides[1].ships.size)
    }

    @Test
    fun `hunt needs a shot budget`() {
        val reasons = reasonsOf(create(mission = MissionType.HUNT, firstPlayer = 0, budget = null))
        assertEquals(listOf("HUNT_NEEDS_BUDGET"), reasons)
    }

    @Test
    fun `hunt budget must be at least one`() {
        assertEquals(listOf("HUNT_NEEDS_BUDGET"), reasonsOf(create(mission = MissionType.HUNT, budget = 0)))
    }

    @Test
    fun `hunt must start with the player`() {
        val reasons = reasonsOf(create(mission = MissionType.HUNT, firstPlayer = 1, budget = 5))
        assertEquals(listOf("HUNT_FIRST_PLAYER_MUST_BE_0"), reasons)
    }

    @Test
    fun `hunt with a budget is created with the budget stored`() {
        val s = stateOf(create(mission = MissionType.HUNT, budget = 12))
        assertEquals(12, s.shotBudget)
        assertEquals(MissionType.HUNT, s.mission)
    }

    @Test
    fun `duel must not carry a shot budget`() {
        assertEquals(listOf("BUDGET_ONLY_FOR_HUNT"), reasonsOf(create(budget = 5)))
    }

    @Test
    fun `first player must be side zero or one`() {
        assertEquals(listOf("FIRST_PLAYER: must be 0 or 1"), reasonsOf(create(firstPlayer = 2)))
    }

    @Test
    fun `exactly two sides are required`() {
        val reasons = reasonsOf(create(placements = listOf(listOf(BOTTOM_RIGHT, dest(5, 0)))))
        assertEquals(listOf("PLACEMENTS: exactly two sides"), reasons)
    }

    @Test
    fun `grid smaller than 8 is rejected by the rules`() {
        val reasons = reasonsOf(create(rules = RuleSet(width = 7, height = 8, fleet = Fx.TWIN)))
        assertTrue(reasons.any { it.startsWith("GRID_SIZE") })
    }

    @Test
    fun `grid larger than 12 is rejected by the rules`() {
        val reasons = reasonsOf(create(rules = RuleSet(width = 8, height = 13, fleet = Fx.TWIN)))
        assertTrue(reasons.any { it.startsWith("GRID_SIZE") })
    }

    @Test
    fun `a terrain list of the wrong size is rejected`() {
        val reasons = reasonsOf(create(rules = Fx.rules(fleet = Fx.TWIN, terrain = List(10) { Terrain.WATER })))
        assertTrue(reasons.any { it.startsWith("TERRAIN_SIZE") })
    }

    @Test
    fun `a turn limit below one is rejected`() {
        val reasons = reasonsOf(create(rules = Fx.rules(fleet = Fx.TWIN, maxTurns = 0)))
        assertTrue(reasons.any { it.startsWith("MAX_TURNS") })
    }

    @Test
    fun `shipsMayTouch is allowed in M1`() {
        val touching = rules.copy(shipsMayTouch = true)
        assertTrue(create(placements = listOf(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(TOP_LEFT, dest(1, 2))), rules = touching) is SetupResult.Created)
    }

    @Test
    fun `classic rules default to a 10 by 10 sea with the classic fleet`() {
        val defaults = RuleSet()
        assertEquals(10, defaults.width)
        assertEquals(10, defaults.height)
        assertEquals(Fleet.CLASSIC, defaults.fleet)
        assertEquals(400, defaults.maxTurns)
    }

    @Test
    fun `boards are built from the rules and the mines of the side`() {
        val boardFor = RuleSet(width = 8, height = 8, mines = listOf(listOf(3), listOf()))
        assertEquals(listOf(3), boardFor.boardFor(0).mines)
        assertEquals(Board.water(8, 8), boardFor.boardFor(1))
    }

    @Test
    fun `a board built from a placement reports the same ship class`() {
        val s = stateOf(create())
        assertEquals(ShipClass.DESTROYER, s.sides[0].ships.first().shipClass)
    }

    private fun assertNull(value: Any?) = org.junit.Assert.assertNull(value)
}
