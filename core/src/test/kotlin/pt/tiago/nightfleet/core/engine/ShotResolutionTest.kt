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
import pt.tiago.nightfleet.core.engine.Fx.fireAt
import pt.tiago.nightfleet.core.engine.Fx.illegalReason
import pt.tiago.nightfleet.core.engine.Fx.labels
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain

/** Shot resolution: miss, hit, sunk, automatic water and illegal targets. */
class ShotResolutionTest {

    /** Side 0: destroyers at G8-H8 and A6-B6. Side 1: destroyers at A1-B1 and D4-E4. */
    private val twinP0 = listOf(BOTTOM_RIGHT, dest(5, 0))
    private val twinP1 = listOf(TOP_LEFT, dest(3, 4))

    private fun twinDuel(terrain: List<Terrain> = emptyList()) =
        Fx.start(twinP0, twinP1, Fx.rules(fleet = Fx.TWIN, terrain = terrain))

    private fun water(out: Outcome): List<String> =
        labels(out.events.filterIsInstance<Event.AutoWater>().single().cells)

    private fun midget(row: Int, col: Int) = Placement(ShipClass.MIDGET_SUB, Cell(row, col), 0)

    @Test
    fun `a miss is reported as Miss and passes the turn`() {
        val out = fire(Fx.duel(), "C3")
        assertEquals(listOf(Event.Miss(0, 1, Cell(2, 2)), Event.TurnStarted(1, 0)), out.events)
        assertEquals(1, out.state.current)
    }

    @Test
    fun `a miss is recorded in the defender's incoming log`() {
        val out = fire(Fx.duel(), "C3")
        assertEquals(listOf(ShotRecord(0, 0, 2 * 8 + 2, ShotResult.Miss)), out.state.sides[1].incoming.entries)
    }

    @Test
    fun `a hit is reported as Hit and records the square on the ship`() {
        val out = fire(Fx.duel(), "A1")
        assertEquals(listOf(Event.Hit(0, 1, Cell(0, 0)), Event.TurnStarted(1, 0)), out.events)
        assertEquals(listOf(0), out.state.sides[1].ships[0].hits)
        assertFalse(out.state.sides[1].ships[0].sunk)
    }

    @Test
    fun `a hit does not give an extra turn`() {
        val out = fire(Fx.duel(), "A1")
        assertEquals(1, out.state.current)
        assertEquals(1, out.state.turn)
    }

    @Test
    fun `sinking the only ship ends the duel with the shooter as winner`() {
        var s = Fx.duel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        val out = fire(s, "B1")
        assertEquals(Event.Hit(0, 1, Cell(0, 1)), out.events[0])
        assertEquals(Event.Sunk(0, 1, ShipClass.DESTROYER, listOf(Cell(0, 0), Cell(0, 1))), out.events[1])
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), out.state.result)
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), (out.events.last() as Event.BattleEnded).result)
    }

    @Test
    fun `sinking emits Hit then Sunk then AutoWater then TurnStarted`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        val out = fire(s, "B1")
        assertEquals(listOf("Hit", "Sunk", "AutoWater", "TurnStarted"), out.events.map { it::class.simpleName })
    }

    @Test
    fun `automatic water surrounds a sunk destroyer and ignores its own squares`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        assertEquals(listOf("C1", "A2", "B2", "C2"), water(fire(s, "B1")))
    }

    @Test
    fun `automatic water is logged as AutoWater entries of the attacker`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        val autoWater = s.sides[1].incoming.entries.filter { it.result == ShotResult.AutoWater }
        assertEquals(4, autoWater.size)
        assertTrue(autoWater.all { it.attacker == 0 })
    }

    @Test
    fun `a sinking shot is logged as Sunk with the public shape`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        val sunk = s.sides[1].incoming.entries.single { it.result is ShotResult.Sunk }
        assertEquals(ShotResult.Sunk(ShipClass.DESTROYER, listOf(0, 1)), sunk.result)
    }

    @Test
    fun `vertical destroyer in the top-left corner marks the four squares that exist`() {
        val s = Fx.start(listOf(BOTTOM_RIGHT), listOf(dest(0, 0, rot = 1)))
        var t = fire(s, "A1").state
        t = fire(t, "H1").state
        assertEquals(listOf("B1", "B2", "A3", "B3"), water(fire(t, "A2")))
    }

    @Test
    fun `midget submarine in the top-left corner has three water neighbours`() {
        val s = Fx.start(listOf(midget(7, 7)), listOf(midget(0, 0)), Fx.rules(fleet = Fx.MIDGET))
        val out = fire(s, "A1")
        assertEquals(listOf("B1", "A2", "B2"), water(out))
        assertEquals(BattleResult(0, EndReason.FLEET_SUNK), out.state.result)
    }

    @Test
    fun `midget submarine in the bottom-right corner marks its three neighbours`() {
        val s = Fx.start(listOf(midget(0, 0)), listOf(midget(7, 7)), Fx.rules(fleet = Fx.MIDGET))
        assertEquals(listOf("G7", "H7", "G8"), water(fire(s, "H8")))
    }

    @Test
    fun `automatic water never marks an island`() {
        val terrain = List(64) { if (it == 8) Terrain.ISLAND else Terrain.WATER }
        var s = Fx.duel(Fx.rules(terrain = terrain))
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        val out = fire(s, "B1")
        assertEquals(listOf("C1", "B2", "C2"), water(out))
        assertFalse(out.state.sides[1].incoming.entries.any { it.cell == 8 })
    }

    @Test
    fun `automatic water never marks a reef`() {
        val terrain = List(64) { if (it == 9) Terrain.REEF else Terrain.WATER }
        var s = Fx.duel(Fx.rules(terrain = terrain))
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        assertEquals(listOf("C1", "A2", "C2"), water(fire(s, "B1")))
    }

    @Test
    fun `automatic water skips squares the attacker already knows`() {
        var s = twinDuel()
        s = fire(s, "C1").state
        s = fire(s, "H1").state
        s = fire(s, "A1").state
        s = fire(s, "H2").state
        assertEquals(listOf("A2", "B2", "C2"), water(fire(s, "B1")))
    }

    @Test
    fun `a square marked as automatic water cannot be fired at again`() {
        var s = twinDuel()
        s = fire(s, "C1").state
        s = fire(s, "H1").state
        s = fire(s, "A1").state
        s = fire(s, "H2").state
        s = fire(s, "B1").state
        s = fire(s, "H3").state
        val out = fire(s, "A2")
        assertEquals("ALREADY_FIRED", illegalReason(out))
        assertEquals(s, out.state)
    }

    @Test
    fun `automatic water never marks a square of another ship when ships may touch`() {
        val rules = Fx.rules(fleet = Fx.TWIN, shipsMayTouch = true)
        // Side 1 has a second destroyer at C1-D1, touching the sunk one.
        val s = Fx.start(listOf(BOTTOM_RIGHT, dest(5, 0)), listOf(dest(0, 0), dest(0, 2)), rules)
        var t = fire(s, "A1").state
        t = fire(t, "H1").state
        assertEquals(listOf("A2", "B2", "C2"), water(fire(t, "B1")))
    }

    @Test
    fun `firing at an island is rejected and the state is unchanged`() {
        val terrain = List(64) { if (it == 8) Terrain.ISLAND else Terrain.WATER }
        val s = Fx.duel(Fx.rules(terrain = terrain))
        val out = fire(s, "A2")
        assertEquals("NOT_A_TARGET", illegalReason(out))
        assertEquals(s, out.state)
    }

    @Test
    fun `firing at a reef is rejected`() {
        val terrain = List(64) { if (it == 9) Terrain.REEF else Terrain.WATER }
        val s = Fx.duel(Fx.rules(terrain = terrain))
        val out = fire(s, "B2")
        assertEquals("NOT_A_TARGET", illegalReason(out))
        assertEquals(s, out.state)
    }

    @Test
    fun `firing outside the board is rejected`() {
        val s = Fx.duel()
        assertEquals("OUT_OF_BOUNDS", illegalReason(fireAt(s, 8, 0)))
        assertEquals("OUT_OF_BOUNDS", illegalReason(fireAt(s, 0, 8)))
        assertEquals("OUT_OF_BOUNDS", illegalReason(fireAt(s, -1, 3)))
    }

    @Test
    fun `firing twice at the same square is rejected and keeps the turn`() {
        var s = Fx.duel()
        s = fire(s, "C3").state
        s = fire(s, "H1").state
        val out = fire(s, "C3")
        assertEquals("ALREADY_FIRED", illegalReason(out))
        assertEquals(s, out.state)
        assertEquals(0, out.state.current)
    }

    @Test
    fun `firing at a square already hit is rejected`() {
        var s = Fx.duel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        assertEquals("ALREADY_FIRED", illegalReason(fire(s, "A1")))
    }

    @Test
    fun `the two sides keep separate knowledge of the same coordinates`() {
        var s = Fx.duel()
        s = fire(s, "C3").state
        val out = fire(s, "C3")
        assertNull(illegalReason(out))
        assertEquals(Event.Miss(1, 0, Cell(2, 2)), out.events[0])
    }

    @Test
    fun `hit streak counts consecutive turns with a hit`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "E4").state
        assertEquals(2, s.sides[0].hitStreak)
        assertEquals(0, s.sides[1].hitStreak)
    }

    @Test
    fun `a miss resets the hit streak`() {
        var s = twinDuel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "C3").state
        assertEquals(0, s.sides[0].hitStreak)
    }

    @Test
    fun `sinking a ship counts as a hit for the streak`() {
        var s = Fx.duel()
        s = fire(s, "A1").state
        s = fire(s, "H1").state
        s = fire(s, "B1").state
        assertEquals(2, s.sides[0].hitStreak)
    }

    @Test
    fun `the round counter stays zero after only the first side acted`() {
        val out = fire(Fx.duel(), "C3")
        assertEquals(0, out.state.round)
        assertEquals(1, out.state.turn)
    }

    @Test
    fun `the round counter increments after both sides have acted`() {
        var s = Fx.duel()
        s = fire(s, "C3").state
        s = fire(s, "C4").state
        assertEquals(1, s.round)
        assertEquals(2, s.turn)
        assertEquals(0, s.current)
    }

    @Test
    fun `turns alternate between the sides on every shot`() {
        var s = twinDuel()
        val actors = mutableListOf<Int>()
        for (label in listOf("C3", "C4", "C5", "C6", "D3", "D4")) {
            actors += s.current
            s = fire(s, label).state
        }
        assertEquals(listOf(0, 1, 0, 1, 0, 1), actors)
    }

    @Test
    fun `the shot log records the turn number of each shot`() {
        var s = Fx.duel()
        s = fire(s, "C3").state
        s = fire(s, "C4").state
        assertEquals(listOf(0), s.sides[1].incoming.entries.map { it.turn })
        assertEquals(1, s.sides[0].incoming.entries.single().turn)
    }

    @Test
    fun `a rejected shot never changes the state`() {
        val out = fire(Fx.duel(), "A1").state
        val again = Engine.apply(out, Action.Fire(Cell(9, 9)))
        assertEquals(out, again.state)
        assertEquals(listOf(Event.IllegalAction("OUT_OF_BOUNDS")), again.events)
    }

    @Test
    fun `a fresh sea has an empty incoming log`() {
        assertTrue(Fx.duel().sides[1].incoming.entries.isEmpty())
    }

    @Test
    fun `sinking the second destroyer leaves the first afloat`() {
        var s = twinDuel()
        s = fire(s, "E4").state
        s = fire(s, "H1").state
        val out = fire(s, "F4")
        assertEquals(1, out.events.count { it is Event.Sunk })
        assertFalse(out.state.sides[1].ships[0].sunk)
        assertTrue(out.state.sides[1].ships[1].sunk)
        assertNull(out.state.result)
    }
}
