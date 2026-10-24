package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** Whole battles through the engine, the hints and the action mapping, plus the non-leak property. */
class BattleRunnerTest {

    private fun side(tier: AiTier, profile: AdmiralProfile = Admirals.FROST) = BattleRunner.Side(AdmiralBrain, profile, tier)

    @Test
    fun `a duel is deterministic for the same seed`() {
        val sides = listOf(side(AiTier.HARD, Admirals.FROST), side(AiTier.MEDIUM, Admirals.HAZE))
        assertEquals(BattleRunner.duel(RuleSet(), 5L, sides), BattleRunner.duel(RuleSet(), 5L, sides))
    }

    @Test
    fun `different seeds give different battles`() {
        val sides = listOf(side(AiTier.HARD), side(AiTier.HARD))
        val a = BattleRunner.duel(RuleSet(), 5L, sides).finalHash
        val b = BattleRunner.duel(RuleSet(), 6L, sides).finalHash
        assertNotEquals(a, b)
    }

    @Test
    fun `a duel ends with a winner or a draw and reports the shots of both sides`() {
        for (seed in 1L..5L) {
            val outcome = BattleRunner.duel(RuleSet(), seed, listOf(side(AiTier.HARD), side(AiTier.EASY)))
            assertTrue(outcome.reason == EndReason.FLEET_SUNK || outcome.reason == EndReason.TURN_LIMIT)
            assertTrue(outcome.shots.all { it > 0 })
        }
    }

    @Test
    fun `a hunt sinks the classic fleet within the budget`() {
        for (seed in 1L..5L) {
            val outcome = BattleRunner.hunt(RuleSet(), seed, side(AiTier.HARD, Admirals.NEUTRAL))
            assertEquals(EndReason.FLEET_SUNK, outcome.reason)
            assertEquals(0, outcome.winner)
            assertTrue(outcome.shots[0] in 17..100)
        }
    }

    @Test
    fun `a duel with admiral layouts starts from the profile fleets`() {
        val outcome = BattleRunner.duel(RuleSet(), 8L, listOf(side(AiTier.HARD, Admirals.LEVIATHAN), side(AiTier.HARD, Admirals.ATOLL)), admiralLayouts = true)
        assertTrue(outcome.turns > 0)
    }

    @Test
    fun `the final hash of a hunt is stable`() {
        val a = BattleRunner.hunt(RuleSet(), 13L, side(AiTier.MEDIUM, Admirals.NEUTRAL))
        val b = BattleRunner.hunt(RuleSet(), 13L, side(AiTier.MEDIUM, Admirals.NEUTRAL))
        assertEquals(a.finalHash, b.finalHash)
    }

    @Test
    fun `intents map to engine actions where the M1 types allow it`() {
        assertEquals(Action.Fire(Cell(1, 2)), AiIntent.Fire(Cell(1, 2)).toAction())
        assertEquals(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(Cell(3, 3))), AiIntent.Sonar(Cell(3, 3)).toAction())
        assertEquals(Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(Cell(4, 4))), AiIntent.Cross(Cell(4, 4)).toAction())
        assertEquals(Action.Repair(Cell(0, 1)), AiIntent.Repair(Cell(0, 1)).toAction())
        assertEquals(Action.MoveShip(2, -1), AiIntent.Move(2, -1).toAction())
    }

    @Test
    fun `line weapons map to line targets`() {
        assertEquals(
            Action.UseWeapon(Weapon.TORPEDO, WeaponTarget.LineTarget(isRow = true, index = 0, fromStart = true)),
            AiIntent.Torpedo(isRow = true, index = 0, fromStart = true).toAction(),
        )
        assertEquals(
            Action.UseWeapon(Weapon.RECON, WeaponTarget.LineTarget(isRow = false, index = 3)),
            AiIntent.Recon(isRow = false, index = 3).toAction(),
        )
    }

    @Test
    fun `the officer hint returns the three best unknown squares`() {
        val view = SyntheticViews.blank(8, 8)
        val hint = OfficerHint.topCells(view)
        assertEquals(3, hint.size)
        assertTrue(hint.all { view.kind(it) == KnownCell.UNKNOWN })
        assertEquals(hint, OfficerHint.topCells(view))
    }

    @Test
    fun `the officer hint points next to an open hit`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT))
        val best = OfficerHint.topCells(view, n = 1).single()
        val cell = view.cell(best)
        assertEquals(1, Math.abs(cell.row - 3) + Math.abs(cell.col - 3))
    }

    @Test
    fun `shot analysis gives the centre of an empty three by three a third of the ships`() {
        val view = SyntheticViews.blank(3, 3)
        val quality = ShotAnalysis.evaluate(view, chosenCell = 4)
        assertEquals(333, quality.probabilityPermille)
        assertEquals(4, quality.bestCell)
        assertEquals(333, quality.bestPermille)
        assertFalse(quality.wasted)
    }

    @Test
    fun `shot analysis calls a diagonal of a hit wasted`() {
        val view = SyntheticViews.blank(3, 3, kinds = mapOf("B2" to KnownCell.HIT))
        assertTrue(ShotAnalysis.evaluate(view, chosenCell = 0).wasted)
        assertTrue(ShotAnalysis.evaluate(view, chosenCell = 4).wasted)
    }

    /**
     * Two battles whose hidden fleets differ but whose observation of side 0 is identical: side 0 fires
     * only at squares that are water in both enemy fleets. Every admiral must then decide the same.
     */
    @Test
    fun `hidden enemy fleets never change a decision`() {
        val rules = RuleSet()
        val own = placed(1L)
        val fleetA = placed(2L)
        val fleetB = placed(3L)
        val hidden = (fleetA + fleetB).flatMap { it.cells() }.map { it.row * 10 + it.col }.toSet()
        val safe = (0 until 100).filter { it !in hidden }
        var a = start(rules, listOf(own, fleetA))
        var b = start(rules, listOf(own, fleetB))
        for (index in safe.take(25)) {
            val cell = Cell(index / 10, index % 10)
            a = Engine.apply(a, Action.Fire(cell)).state
            b = Engine.apply(b, Action.Fire(cell)).state
        }
        val viewA = AiView.from(Observation.of(a, 0))
        val viewB = AiView.from(Observation.of(b, 0))
        assertEquals(viewA.known, viewB.known)
        for (tier in AiTier.entries) for (admiral in Admirals.ALL.take(4)) for (seed in 1L..8L) {
            val ia = AdmiralBrain.decide(viewA, admiral, tier, SplitMix64(seed))
            val ib = AdmiralBrain.decide(viewB, admiral, tier, SplitMix64(seed))
            assertEquals("$tier ${admiral.id} seed $seed", ia, ib)
        }
    }

    private fun placed(seed: Long): List<Placement> =
        (RandomPlacer.place(RuleSet().boardFor(0), Fleet.CLASSIC, SplitMix64(seed)) as PlacementResult.Success).placements

    private fun start(rules: RuleSet, placements: List<List<Placement>>): pt.tiago.nightfleet.core.engine.BattleState {
        val created = BattleSetup.create(rules, MissionType.HUNT, placements, 0, attemptSeed = 77L, shotBudget = 100)
        return (created as SetupResult.Created).state
    }

}
