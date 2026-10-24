package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.engine.M2.eventsOf
import pt.tiago.nightfleet.core.engine.M2.fire
import pt.tiago.nightfleet.core.engine.M2.freighter
import pt.tiago.nightfleet.core.engine.M2.pass
import pt.tiago.nightfleet.core.engine.M2.passRounds
import pt.tiago.nightfleet.core.engine.M2.reason
import pt.tiago.nightfleet.core.engine.M2.rules
import pt.tiago.nightfleet.core.engine.M2.start
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.ConvoyRule
import pt.tiago.nightfleet.core.rules.RuleSet

/** Moving ships. The side that moves is the side to act: side 1 when first is 1. */
class MovingShipsTest {

    private val pairFleet = Fleet("PAIR2", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))

    private fun move(state: BattleState, shipId: Int, delta: Int) = Engine.apply(state, Action.MoveShip(shipId, delta))

    @Test
    fun `a ship moves one square along its axis and the turn passes`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        val out = move(s, 0, -1)
        assertEquals(Cell(7, 5), out.state.sides[0].ships[0].placement.origin)
        assertEquals(listOf(Event.EnemyShipMoved(0, 1)), eventsOf<Event.EnemyShipMoved>(out))
        assertEquals(1, out.state.current)
    }

    @Test
    fun `the opponent learns the turn of the move but not the position`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        val out = move(s, 0, -1).state
        assertEquals(listOf(0), Observation.of(out, 1).enemyShipMovedAtTurns)
        assertEquals(Observation.of(s, 1).enemySea, Observation.of(out, 1).enemySea)
        assertEquals(emptyList<Int>(), Observation.of(out, 0).enemyShipMovedAtTurns)
    }

    @Test
    fun `a ship moves once per battle per side`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        val moved = move(s, 0, -1).state
        assertEquals("MOVE_ALREADY_USED", reason(move(pass(moved).state, 0, -1)))
    }

    @Test
    fun `moving is refused when the rule is off`() {
        val s = start(rules(), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("MOVE_NOT_ENABLED", reason(move(s, 0, -1)))
    }

    @Test
    fun `a damaged ship cannot move`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        val hit = fire(s, "A1").state
        assertEquals("SHIP_DAMAGED", reason(move(hit, 0, 1)))
    }

    @Test
    fun `a ship cannot move off the board`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")), first = 1)
        assertEquals("MOVE_BLOCKED", reason(move(s, 0, -1)))
    }

    @Test
    fun `a ship cannot move next to another ship`() {
        val s = start(
            rules(movingShips = true, fleet = pairFleet),
            listOf(dest("D8"), dest("G8")),
            listOf(dest("A1"), dest("D1")),
            first = 1,
        )
        assertEquals("MOVE_BLOCKED", reason(move(s, 0, 1)))
    }

    @Test
    fun `a ship cannot move onto land`() {
        val s = start(
            rules(movingShips = true, terrain = M2.terrain(Terrain.ISLAND, "C1")),
            listOf(dest("G8")),
            listOf(dest("A1")),
            first = 1,
        )
        assertEquals("MOVE_BLOCKED", reason(move(s, 0, 1)))
    }

    @Test
    fun `a ship cannot move next to a mine`() {
        val s = start(
            rules(movingShips = true, mines = listOf(emptyList(), listOf(M2.idx("D1")))),
            listOf(dest("G8")),
            listOf(dest("A1")),
            first = 1,
        )
        assertEquals("MOVE_BLOCKED", reason(move(s, 0, 1)))
    }

    @Test
    fun `the delta must be one square either way and the ship must exist`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("BAD_DELTA", reason(move(s, 0, 2)))
        assertEquals("BAD_SHIP", reason(move(s, 3, 1)))
    }

    @Test
    fun `a vertical ship moves along its column`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(Placement(ShipClass.DESTROYER, cell("A1"), 1)), first = 1)
        val out = move(s, 0, 1)
        assertEquals(Cell(1, 0), out.state.sides[1].ships[0].placement.origin)
        assertEquals(listOf(Event.EnemyShipMoved(1, 0)), eventsOf<Event.EnemyShipMoved>(out))
    }

    @Test
    fun `the seaplane moves along the bar of its T`() {
        val sea = Fleet("SEA", listOf(ShipClass.SEAPLANE))
        val s = start(
            rules(fleet = sea, movingShips = true),
            listOf(Placement(ShipClass.SEAPLANE, Cell(6, 5), 0)),
            listOf(Placement(ShipClass.SEAPLANE, cell("A1"), 0)),
            first = 1,
        )
        val out = move(s, 0, 1)
        assertEquals(Cell(0, 1), out.state.sides[1].ships[0].placement.origin)
        assertTrue(eventsOf<Event.IllegalAction>(out).isEmpty())
    }

    @Test
    fun `a moved ship keeps its shape`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")), first = 1)
        val out = move(s, 0, 1).state
        assertEquals(2, out.sides[1].ships[0].placement.cells().size)
    }

    @Test
    fun `moving a ship with the rule off in a finished battle is refused by the battle end`() {
        val s = start(rules(movingShips = true), listOf(dest("G8")), listOf(dest("A1")))
        val ended = s.copy(result = BattleResult(0, EndReason.FLEET_SUNK))
        assertEquals("BATTLE_OVER", reason(move(ended, 0, -1)))
    }
}

/** CONVOY: freighters in the player fleet and the two ways a convoy battle ends. */
class ConvoyTest {

    private val convoyRule = ConvoyRule(surviveRounds = 2, minFreighters = 2)

    private val threeFreighters = listOf(ShipClass.FREIGHTER, ShipClass.FREIGHTER, ShipClass.FREIGHTER)

    /** Player: a destroyer at G8-H8 and freighters at D4-E4, A6-B6 and E6-F6; admiral: one destroyer at A1-B1. */
    private fun convoy(rule: ConvoyRule = convoyRule, first: Int = 0) = start(
        rules(extraShips = listOf(threeFreighters, emptyList()), convoy = rule),
        listOf(dest("G8"), freighter("D4"), freighter("A6"), freighter("E6")),
        listOf(dest("A1")),
        first = first,
        mission = MissionType.CONVOY,
    )

    @Test
    fun `a convoy battle needs the convoy rule`() {
        val reasons = M2.setupReasons(
            rules(extraShips = listOf(threeFreighters, emptyList())),
            listOf(dest("G8"), freighter("D4"), freighter("A6"), freighter("E6")),
            listOf(dest("A1")),
            mission = MissionType.CONVOY,
        )
        assertTrue(reasons.contains("CONVOY_NEEDS_RULE"))
    }

    @Test
    fun `the player fleet carries the extra freighters`() {
        val s = convoy()
        assertEquals(3, s.sides[0].ships.count { it.shipClass == ShipClass.FREIGHTER })
        assertEquals(1, s.sides[1].ships.size)
    }

    @Test
    fun `a minimum above the freighters carried is a rule error`() {
        val bad = rules(extraShips = listOf(listOf(ShipClass.FREIGHTER), emptyList()), convoy = ConvoyRule(surviveRounds = 2, minFreighters = 3))
        assertTrue(bad.problems().any { it.startsWith("CONVOY_FREIGHTERS") })
    }

    @Test
    fun `surviving the required rounds with enough freighters wins`() {
        assertEquals(BattleResult(0, EndReason.CONVOY_SURVIVED), passRounds(convoy(), 2).result)
    }

    @Test
    fun `the battle goes on before the required rounds`() {
        assertNull(passRounds(convoy(), 1).result)
    }

    @Test
    fun `losing freighters below the minimum loses the battle`() {
        var s = convoy(ConvoyRule(surviveRounds = 10, minFreighters = 3), first = 1)
        s = fire(s, "D4").state
        s = pass(s).state
        val out = fire(s, "E4")
        assertEquals(BattleResult(1, EndReason.CONVOY_LOST), out.state.result)
        assertEquals(1, eventsOf<Event.Sunk>(out).size)
    }

    @Test
    fun `sinking the enemy still wins the convoy battle`() {
        var s = fire(convoy(), "A1").state
        s = pass(s).state
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), fire(s, "B1").state.result)
    }

    @Test
    fun `the enemy count of the observation is the admiral's fleet only`() {
        assertEquals(listOf(ShipClass.DESTROYER), Observation.of(convoy(), 0).enemyRemaining.map { it.shipClass })
    }
}
