package pt.tiago.nightfleet.core.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Fx
import pt.tiago.nightfleet.core.engine.Fx.BOTTOM_RIGHT
import pt.tiago.nightfleet.core.engine.Fx.TOP_LEFT
import pt.tiago.nightfleet.core.engine.Fx.dest
import pt.tiago.nightfleet.core.engine.Fx.fire
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon

/** Observation: what each side knows, and the no-leak guarantee. */
class ObservationTest {

    private fun state(p0: List<Placement>, p1: List<Placement>, rules: RuleSet = Fx.rules()): BattleState =
        Fx.start(p0, p1, rules)

    private fun known(obs: Observation, label: String): KnownCell =
        obs.enemySea[Cell.parse(label)!!.let { it.row * obs.width + it.col }]

    @Test
    fun `the known-cell enum declares every value of PLAN section 4-10`() {
        val names = KnownCell.entries.map { it.name }
        assertTrue(names.containsAll(listOf("UNKNOWN", "WATER", "HIT", "SUNK", "CONTACT", "MINE", "DECOY_WRECK", "REPAIRED")))
    }

    @Test
    fun `a fresh sea is entirely unknown`() {
        val obs = Observation.of(Fx.duel(), 0)
        assertTrue(obs.enemySea.all { it == KnownCell.UNKNOWN })
        assertEquals(64, obs.enemySea.size)
    }

    @Test
    fun `a miss shows as water`() {
        val obs = Observation.of(fire(Fx.duel(), "C3").state, 0)
        assertEquals(KnownCell.WATER, known(obs, "C3"))
        assertEquals(KnownCell.UNKNOWN, known(obs, "C4"))
    }

    @Test
    fun `a hit shows as hit and the shooter sees it only in its own view`() {
        val s = fire(Fx.duel(), "A1").state
        assertEquals(KnownCell.HIT, known(Observation.of(s, 0), "A1"))
        assertEquals(KnownCell.UNKNOWN, known(Observation.of(s, 1), "A1"))
    }

    @Test
    fun `a sunk ship shows its shape and its squares are sunk`() {
        var s = fire(Fx.duel(), "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        val obs = Observation.of(s, 0)
        assertEquals(KnownCell.SUNK, known(obs, "A1"))
        assertEquals(KnownCell.SUNK, known(obs, "B1"))
        assertEquals(listOf(SunkShape(ShipClass.DESTROYER, listOf(Cell(0, 0), Cell(0, 1)))), obs.enemySunk)
    }

    @Test
    fun `automatic water shows as water around a sunk ship`() {
        var s = fire(Fx.duel(), "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        val obs = Observation.of(s, 0)
        assertEquals(KnownCell.WATER, known(obs, "C1"))
        assertEquals(KnownCell.WATER, known(obs, "A2"))
        assertEquals(KnownCell.UNKNOWN, known(obs, "D4"))
    }

    @Test
    fun `islands and reefs are visible to both sides`() {
        val terrain = List(64) { if (it == 8) Terrain.ISLAND else if (it == 9) Terrain.REEF else Terrain.WATER }
        val s = state(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), Fx.rules(terrain = terrain))
        assertEquals(KnownCell.ISLAND, known(Observation.of(s, 0), "A2"))
        assertEquals(KnownCell.REEF, known(Observation.of(s, 1), "B2"))
    }

    @Test
    fun `enemy remaining classes count what is still afloat`() {
        val s = Fx.start(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(TOP_LEFT, dest(3, 4)), Fx.rules(fleet = Fx.TWIN))
        assertEquals(listOf(ClassCount(ShipClass.DESTROYER, 2)), Observation.of(s, 0).enemyRemaining)
        var t = fire(s, "A1").state
        t = fire(t, "H1").state
        t = fire(t, "B1").state
        assertEquals(listOf(ClassCount(ShipClass.DESTROYER, 1)), Observation.of(t, 0).enemyRemaining)
    }

    @Test
    fun `enemy remaining classes ignore the classes already sunk`() {
        val s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT))
        var t = fire(s, "A1").state
        t = fire(t, "H1").state
        t = fire(t, "B1").state
        assertTrue(Observation.of(t, 0).enemyRemaining.isEmpty())
    }

    @Test
    fun `own sea shows the full fleet and the incoming shots`() {
        var s = fire(Fx.duel(), "C3").state
        s = fire(s, "H1").state
        val obs = Observation.of(s, 0)
        assertEquals(listOf(BOTTOM_RIGHT), obs.ownSea.ships.map { it.placement })
        assertEquals(listOf(7), obs.ownSea.incoming.entries.map { it.cell })
    }

    @Test
    fun `public state exposes turn round and current side`() {
        var s = fire(Fx.duel(), "C3").state
        s = fire(s, "C4").state
        val public = Observation.of(s, 0).publicState
        assertEquals(2, public.turn)
        assertEquals(1, public.round)
        assertEquals(0, public.current)
        assertEquals(1, public.shotsFired)
    }

    @Test
    fun `public state shows both sides charges and streaks`() {
        val s = fire(Fx.duel(), "A1").state
        val public = Observation.of(s, 0).publicState
        assertEquals(1, public.ownHitStreak)
        assertEquals(0, public.enemyHitStreak)
        assertEquals(Weapon.entries.toList(), public.ownCharges.keys.toList())
        assertEquals(public.ownCharges, public.enemyCharges)
    }

    @Test
    fun `public state carries the mission and the shot budget`() {
        val result = BattleSetup.create(Fx.rules(), MissionType.HUNT, listOf(listOf(BOTTOM_RIGHT), null), 0, 3L, 15)
        val s = (result as SetupResult.Created).state
        val public = Observation.of(s, 0).publicState
        assertEquals(MissionType.HUNT, public.mission)
        assertEquals(15, public.shotBudget)
    }

    @Test
    fun `enemy mines are redacted from the public rules but own mines stay`() {
        val base = Fx.duel()
        val s = base.copy(rules = base.rules.copy(mines = listOf(listOf(5), listOf(9))))
        val public = Observation.of(s, 0).publicState.rules
        assertEquals(listOf(listOf(5), emptyList<Int>()), public.mines)
        val other = Observation.of(s, 1).publicState.rules
        assertEquals(listOf(emptyList<Int>(), listOf(9)), other.mines)
    }

    @Test
    fun `observation is a pure function of the state`() {
        val s = fire(Fx.duel(), "C3").state
        assertEquals(Observation.of(s, 0), Observation.of(s, 0))
    }

    @Test
    fun `observation rejects a viewer that is not a side`() {
        try {
            Observation.of(Fx.duel(), 2)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `observation of a viewer excludes enemy placements by construction`() {
        val obs = Observation.of(Fx.duel(), 0)
        assertFalse(obs.enemySea.contains(KnownCell.HIT))
        assertTrue(obs.enemySunk.isEmpty())
        assertEquals(listOf(ClassCount(ShipClass.DESTROYER, 1)), obs.enemyRemaining)
    }

    /**
     * Non-leak property: two states that share both seas' shot results but have
     * different hidden enemy fleets give equal observations for the viewer.
     */
    @Test
    fun `same shots against different hidden fleets give equal observations`() {
        val rules = RuleSet()
        for (seed in 0 until 60L) {
            val own = place(rules, seed * 3 + 1, 1L)
            val fleetA = place(rules, seed * 3 + 2, 2L)
            val fleetB = place(rules, seed * 3 + 3, 3L)
            val a = BattleSetup.create(rules, MissionType.DUEL, listOf(own, fleetA), 0, seed)
            val b = BattleSetup.create(rules, MissionType.DUEL, listOf(own, fleetB), 0, seed)
            var sa = (a as SetupResult.Created).state
            var sb = (b as SetupResult.Created).state
            if (sa.sides[1].ships == sb.sides[1].ships) continue
            val safe = listOf(safeSquares(sa, sb, 0), safeSquares(sa, sb, 1))
            val rng = SplitMix64(seed + 99)
            repeat(12) {
                val defender = 1 - sa.current
                val squares = safe[defender]
                val square = squares[rng.nextInt(squares.size)]
                if (sa.sides[defender].incoming.latest(sa.current, square) != null) return@repeat
                sa = Engine.apply(sa, Action.Fire(sa.sides[defender].board.cell(square))).state
                sb = Engine.apply(sb, Action.Fire(sb.sides[defender].board.cell(square))).state
                assertEquals(sa.sides[defender].incoming, sb.sides[defender].incoming)
            }
            assertNotEquals(sa.sides[1].ships, sb.sides[1].ships)
            assertEquals(Observation.of(sa, 0), Observation.of(sb, 0))
        }
    }

    @Test
    fun `hidden enemy fleets never appear in the observation as coordinates`() {
        val rules = RuleSet()
        val fleetA = place(rules, 11L, 1L)
        val fleetB = place(rules, 12L, 2L)
        val own = place(rules, 13L, 3L)
        val a = (BattleSetup.create(rules, MissionType.DUEL, listOf(own, fleetA), 0, 1L) as SetupResult.Created).state
        val b = (BattleSetup.create(rules, MissionType.DUEL, listOf(own, fleetB), 0, 1L) as SetupResult.Created).state
        val obsA = Observation.of(a, 0)
        val obsB = Observation.of(b, 0)
        assertEquals(obsA.enemySea, obsB.enemySea)
        assertEquals(obsA.enemyRemaining, obsB.enemyRemaining)
        assertEquals(obsA.enemySunk, obsB.enemySunk)
        assertEquals(obsA.publicState, obsB.publicState)
    }

    @Test
    fun `observation of the other side sees the first side's shots`() {
        val s = fire(Fx.duel(), "C3").state
        assertEquals(listOf(18), Observation.of(s, 1).ownSea.incoming.entries.map { it.cell })
    }

    @Test
    fun `enemy remaining counts a fleet by class`() {
        val midgets = Fleet("TWO_MIDGET", listOf(ShipClass.MIDGET_SUB, ShipClass.MIDGET_SUB))
        val s = Fx.start(
            listOf(Placement(ShipClass.MIDGET_SUB, Cell(7, 7)), Placement(ShipClass.MIDGET_SUB, Cell(5, 0))),
            listOf(Placement(ShipClass.MIDGET_SUB, Cell(0, 0)), Placement(ShipClass.MIDGET_SUB, Cell(3, 3))),
            Fx.rules(fleet = midgets),
        )
        assertEquals(listOf(ClassCount(ShipClass.MIDGET_SUB, 2)), Observation.of(s, 0).enemyRemaining)
    }

    /** Water squares of [side] that hold no ship in either of the two states, so every shot is a miss. */
    private fun safeSquares(a: BattleState, b: BattleState, side: Int): List<Int> {
        val board = a.sides[side].board
        val taken = BooleanArray(board.size)
        for (state in listOf(a, b)) for (ship in state.sides[side].ships) {
            for (c in ship.placement.cells()) taken[board.index(c)] = true
        }
        return (0 until board.size).filter { !taken[it] && board.terrainAt(it) == Terrain.WATER }
    }

    private fun place(rules: RuleSet, seed: Long, stream: Long): List<Placement> {
        val board = rules.boardFor(0)
        val rng = SplitMix64(SeedStreams.seed(seed, SeedStreams.Stream.LAYOUT, stream.toInt()))
        return (RandomPlacer.place(board, rules.fleet, rng) as PlacementResult.Success).placements
    }
}
