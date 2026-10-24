package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Fx.BOTTOM_RIGHT
import pt.tiago.nightfleet.core.engine.Fx.TOP_LEFT
import pt.tiago.nightfleet.core.engine.Fx.dest
import pt.tiago.nightfleet.core.engine.Fx.fire
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet

/** Battle endings, turn limit, timeouts, HUNT budgets and unsupported rules or actions. */
class MissionEndingTest {

    private fun hunt(budget: Int, rules: RuleSet = Fx.rules(), hidden: Boolean = true): BattleState {
        val p1: List<Placement>? = if (hidden) null else listOf(TOP_LEFT)
        val result = BattleSetup.create(rules, MissionType.HUNT, listOf(listOf(BOTTOM_RIGHT), p1), 0, 7L, budget)
        return (result as? SetupResult.Created)?.state ?: throw AssertionError("setup rejected: $result")
    }

    @Test
    fun `the side that sinks the last ship wins a duel`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT))
        s = fire(s, "C3").state
        s = fire(s, "G8").state
        s = fire(s, "C4").state
        val out = fire(s, "H8")
        assertEquals(BattleResult(1, EndReason.FLEET_SUNK), out.state.result)
    }

    @Test
    fun `after the battle ends every action is refused with unchanged state`() {
        var s = Fx.duel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        val out = fire(s, "C3")
        assertEquals(listOf(Event.IllegalAction("BATTLE_OVER")), out.events)
        assertEquals(s, out.state)
    }

    @Test
    fun `timeout after the battle ends is also refused`() {
        var s = Fx.duel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        assertEquals(listOf(Event.IllegalAction("BATTLE_OVER")), Engine.apply(s, Action.Timeout).events)
    }

    @Test
    fun `timeout passes the turn without a shot`() {
        val out = Engine.apply(Fx.duel(), Action.Timeout)
        assertEquals(listOf(Event.TurnSkipped(0), Event.TurnStarted(1, 0)), out.events)
        assertEquals(1, out.state.current)
        assertEquals(1, out.state.turn)
        assertTrue(out.state.sides.all { it.incoming.entries.isEmpty() })
    }

    @Test
    fun `timeout resets the hit streak of the side that lost its time`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(TOP_LEFT, dest(3, 4)), Fx.rules(fleet = Fx.TWIN))
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        assertEquals(1, s.sides[0].hitStreak)
        s = Engine.apply(s, Action.Timeout).state
        assertEquals(0, s.sides[0].hitStreak)
    }

    @Test
    fun `timeouts count towards the turn limit`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), Fx.rules(maxTurns = 2))
        s = Engine.apply(s, Action.Timeout).state
        val out = Engine.apply(s, Action.Timeout)
        assertEquals(BattleResult(null, EndReason.TURN_LIMIT), out.state.result)
        assertEquals(BattleResult(null, EndReason.TURN_LIMIT), (out.events.last() as Event.BattleEnded).result)
    }

    @Test
    fun `reaching the turn limit is a draw with no winner`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), Fx.rules(maxTurns = 3))
        s = fire(s, "C3").state
        s = fire(s, "C4").state
        assertNull(s.result)
        val out = fire(s, "C5")
        assertEquals(BattleResult(null, EndReason.TURN_LIMIT), out.state.result)
        assertEquals(3, out.state.turn)
    }

    @Test
    fun `the turn limit does not end a battle that is still short of it`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), Fx.rules(maxTurns = 4))
        s = fire(s, "C3").state
        s = fire(s, "C4").state
        assertNull(s.result)
    }

    @Test
    fun `a sinking shot on the last allowed turn is a win, not a draw`() {
        var s = Fx.start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), Fx.rules(maxTurns = 3))
        s = fire(s, "A1").state
        s = fire(s, "C4").state
        val out = fire(s, "B1")
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), out.state.result)
    }

    @Test
    fun `hunt lets only side zero fire`() {
        val s = hunt(budget = 5)
        val out = fire(s, "C3")
        assertEquals(0, out.state.current)
        assertEquals(listOf(Event.Miss(0, 1, Cell(2, 2)), Event.TurnStarted(0, 1)), out.events)
    }

    @Test
    fun `hunt round counter advances on every shot`() {
        var s = hunt(budget = 5)
        s = fire(s, "C3").state
        s = fire(s, "C4").state
        assertEquals(2, s.round)
        assertEquals(2, s.turn)
    }

    @Test
    fun `hunt is lost when the budget runs out with the fleet afloat`() {
        var s = hunt(budget = 2, hidden = false)
        s = fire(s, "C3").state
        val out = fire(s, "C4")
        assertEquals(BattleResult(null, EndReason.BUDGET_EXHAUSTED), out.state.result)
        assertEquals(2, out.state.sides[0].starShots)
    }

    @Test
    fun `hunt is won when the fleet sinks within the budget`() {
        val s = hunt(budget = 10, hidden = false)
        var t = fire(s, "A1").state
        t = fire(t, "B1").state
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), t.result)
    }

    @Test
    fun `sinking on the last shot of the budget is a win`() {
        var s = hunt(budget = 2, hidden = false)
        s = fire(s, "A1").state
        val out = fire(s, "B1")
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), out.state.result)
    }

    @Test
    fun `timeouts in hunt do not use the budget`() {
        var s = hunt(budget = 1)
        s = Engine.apply(s, Action.Timeout).state
        assertNull(s.result)
        assertEquals(0, s.sides[0].starShots)
        val out = fire(s, "C3")
        assertEquals(BattleResult(null, EndReason.BUDGET_EXHAUSTED), out.state.result)
    }

    @Test
    fun `hunt reaches the turn limit as a draw when the budget is larger`() {
        var s = hunt(budget = 9, rules = Fx.rules(maxTurns = 2), hidden = false)
        s = fire(s, "C3").state
        val out = fire(s, "C4")
        assertEquals(BattleResult(null, EndReason.TURN_LIMIT), out.state.result)
    }

    @Test
    fun `hunt automatic water does not count against the budget`() {
        var s = hunt(budget = 3, hidden = false)
        s = fire(s, "A1").state
        s = fire(s, "C3").state
        val out = fire(s, "B1")
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), out.state.result)
        assertEquals(3, out.state.sides[0].starShots)
        assertTrue(out.state.sides[1].incoming.entries.any { it.result == ShotResult.AutoWater })
    }

    @Test
    fun `hunt hidden fleet is sunk by aiming at its real squares`() {
        val s = hunt(budget = 20)
        val squares = s.sides[1].ships.single().placement.cells()
        var t = s
        for (cell in squares) {
            val out = Engine.apply(t, Action.Fire(cell))
            assertNull(Fx.illegalReason(out))
            t = out.state
        }
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), t.result)
    }

    @Test
    fun `a state from another engine version is refused`() {
        val s = Fx.duel()
        val other = s.copy(engineVersion = ENGINE_VERSION + 1)
        assertEquals("ENGINE_VERSION_MISMATCH", Fx.illegalReason(Engine.apply(other, Action.Fire(Cell(2, 2)))))
    }

    @Test
    fun `apply is deterministic for the same state and action`() {
        val s = Fx.duel()
        assertEquals(Engine.apply(s, Action.Fire(Cell(0, 0))), Engine.apply(s, Action.Fire(Cell(0, 0))))
    }

    @Test
    fun `a fresh duel is not over and has no winner`() {
        val s = Fx.duel()
        assertNull(s.result)
        assertFalse(s.sides.any { ship -> ship.ships.all { it.sunk } })
    }
}
