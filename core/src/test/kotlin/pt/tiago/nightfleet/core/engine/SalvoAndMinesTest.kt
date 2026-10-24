package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.engine.M2.eventsOf
import pt.tiago.nightfleet.core.engine.M2.fire
import pt.tiago.nightfleet.core.engine.M2.pass
import pt.tiago.nightfleet.core.engine.M2.reason
import pt.tiago.nightfleet.core.engine.M2.rules
import pt.tiago.nightfleet.core.engine.M2.start
import pt.tiago.nightfleet.core.engine.M2.terrain
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation

/** Salvo and mines. */
class SalvoAndMinesTest {

    private val twin = Fleet("TWIN", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))

    /** Side 0 has two destroyers (D8, G8) and side 1 two as well (A1, A4), so a salvo of two is legal. */
    private fun salvoBattle(
        mines: List<List<Int>> = listOf(emptyList(), emptyList()),
        terrainLabels: Pair<Terrain, List<String>>? = null,
    ) = start(
        rules(
            fleet = twin,
            salvo = true,
            mines = mines,
            terrain = terrainLabels?.let { terrain(it.first, *it.second.toTypedArray()) } ?: emptyList(),
        ),
        listOf(dest("D8"), dest("G8")),
        listOf(dest("A1"), dest("A4")),
    )

    private fun salvo(state: BattleState, vararg labels: String) =
        Engine.apply(state, Action.Salvo(labels.map { cell(it) }))

    // ---- salvo -------------------------------------------------------------------------------

    @Test
    fun `a salvo fires one shot per own afloat ship and passes the turn`() {
        val out = salvo(salvoBattle(), "C3", "C5")
        assertEquals(2, eventsOf<Event.Miss>(out).size)
        assertEquals(2, shotsForStars(out.state, 0))
        assertEquals(1, out.state.current)
    }

    @Test
    fun `a salvo with fewer or more cells than afloat ships is refused`() {
        assertEquals("SALVO_SIZE", reason(salvo(salvoBattle(), "C3")))
        assertEquals("SALVO_SIZE", reason(salvo(salvoBattle(), "C3", "C5", "C7")))
    }

    @Test
    fun `a salvo with a repeated square is refused`() {
        assertEquals("SALVO_DUPLICATE", reason(salvo(salvoBattle(), "C3", "C3")))
    }

    @Test
    fun `a salvo is refused when the rule is off`() {
        val s = start(rules(fleet = twin), listOf(dest("D8"), dest("G8")), listOf(dest("A1"), dest("A4")))
        assertEquals("SALVO_NOT_ENABLED", reason(salvo(s, "C3", "C5")))
    }

    @Test
    fun `the size follows the own ships still afloat`() {
        val s = salvoBattle()
        val oneSunk = s.copy(sides = s.sides.mapIndexed { i, side ->
            if (i == 0) side.copy(ships = side.ships.mapIndexed { k, ship -> if (k == 0) ship.copy(sunk = true) else ship }) else side
        })
        assertEquals("SALVO_SIZE", reason(salvo(oneSunk, "C3", "C5")))
        assertEquals(1, eventsOf<Event.Miss>(salvo(oneSunk, "C3")).size)
    }

    @Test
    fun `the shots of a salvo are resolved in the order given`() {
        val out = salvo(salvoBattle(), "C3", "A1")
        assertEquals(listOf(Event.Miss(0, 1, cell("C3"))), eventsOf<Event.Miss>(out).take(1))
        assertEquals(Event.Hit(0, 1, cell("A1")), eventsOf<Event.Hit>(out).single())
        assertTrue(out.events.indexOf(eventsOf<Event.Miss>(out).single()) < out.events.indexOf(eventsOf<Event.Hit>(out).single()))
    }

    @Test
    fun `a salvo that sinks a ship mid-way still resolves every shot`() {
        val out = salvo(salvoBattle(), "A1", "B1")
        assertEquals(1, eventsOf<Event.Sunk>(out).size)
        assertEquals(2, shotsForStars(out.state, 0))
    }

    @Test
    fun `a salvo cannot target islands or squares already fired at`() {
        assertEquals("NOT_A_TARGET", reason(salvo(salvoBattle(terrainLabels = Terrain.ISLAND to listOf("C3")), "C3", "C5")))
        val afterFire = pass(fire(salvoBattle(), "C3").state).state
        assertEquals("ALREADY_FIRED", reason(salvo(afterFire, "C3", "C5")))
    }

    @Test
    fun `a salvo with off-board squares is refused`() {
        val out = Engine.apply(salvoBattle(), Action.Salvo(listOf(cell("C3"), pt.tiago.nightfleet.core.grid.Cell(9, 9))))
        assertEquals("OUT_OF_BOUNDS", reason(out))
    }

    @Test
    fun `a salvo that sinks the last ship ends the battle for the shooter`() {
        val s = start(rules(fleet = Fleet("ONE", listOf(ShipClass.DESTROYER)), salvo = true), listOf(dest("G8")), listOf(dest("A1")))
        // B1 was already hit: the single shot on A1 sinks the last ship.
        val damaged = s.copy(sides = s.sides.mapIndexed { i, side ->
            if (i == 1) side.copy(ships = listOf(side.ships[0].copy(hits = listOf(1)))) else side
        })
        val out = salvo(damaged, "A1")
        assertEquals(0, out.state.result?.winner)
    }

    @Test
    fun `salvo mines cost one turn each`() {
        val s = salvoBattle(mines = listOf(emptyList(), listOf(M2.idx("E3"), M2.idx("E5"))))
        val out = salvo(s, "E3", "E5")
        assertEquals(2, eventsOf<Event.MineHit>(out).size)
        assertEquals(listOf(2, 0), out.state.pendingSkips)
    }

    // ---- mines -------------------------------------------------------------------------------

    private fun mineBattle(mine: String = "C1") = start(
        rules(mines = listOf(emptyList(), listOf(M2.idx(mine)))),
        listOf(dest("G8")),
        listOf(dest("A4")),
    )

    @Test
    fun `a mine explodes under the shot and the shooter loses its next turn`() {
        val out = fire(mineBattle(), "C1")
        assertEquals(listOf(Event.MineHit(0, 1, cell("C1"))), eventsOf<Event.MineHit>(out))
        assertEquals(listOf(1, 0), out.state.pendingSkips)
        assertTrue(eventsOf<Event.Hit>(out).isEmpty())
        assertEquals(0, shotsForStars(out.state, 0) - 1)
    }

    @Test
    fun `the skipped turn is passed back to the mine owner`() {
        val out = pass(fire(mineBattle(), "C1").state)
        assertTrue(Event.TurnSkipped(0) in eventsOf<Event.TurnSkipped>(out))
        assertEquals(1, out.state.current)
        assertEquals(listOf(0, 0), out.state.pendingSkips)
    }

    @Test
    fun `a detonated mine cannot be shot again`() {
        var state = fire(mineBattle(), "C1").state
        state = pass(state).state
        state = pass(state).state
        assertEquals("ALREADY_FIRED", reason(fire(state, "C1")))
    }

    @Test
    fun `an unseen mine is unknown to the shooter until it explodes`() {
        val s = mineBattle()
        assertEquals(KnownCell.UNKNOWN, Observation.of(s, 0).enemySea[M2.idx("C1")])
        assertEquals(KnownCell.MINE, Observation.of(fire(s, "C1").state, 0).enemySea[M2.idx("C1")])
    }

    @Test
    fun `the mine owner sees its own mines on its sea`() {
        val s = mineBattle()
        assertEquals(listOf(M2.idx("C1")), Observation.of(s, 1).ownSea.board.mines)
    }

    @Test
    fun `a mine next to a ship is refused at setup`() {
        val reasons = M2.setupReasons(rules(mines = listOf(emptyList(), listOf(M2.idx("B2")))), listOf(dest("G8")), listOf(dest("A1")))
        assertTrue(reasons.any { it.contains("MINE_NEAR_SHIP") })
    }

    @Test
    fun `a mine on land is a rule error`() {
        val bad = rules(mines = listOf(emptyList(), listOf(M2.idx("C3"))), terrain = terrain(Terrain.ISLAND, "C3"))
        assertTrue(bad.problems().any { it.startsWith("MINE_ON_LAND") })
    }

    @Test
    fun `a mine on the board of a ship is refused at setup`() {
        val reasons = M2.setupReasons(rules(mines = listOf(emptyList(), listOf(M2.idx("A1")))), listOf(dest("G8")), listOf(dest("A1")))
        assertTrue(reasons.any { it.contains("MINE") })
    }

    @Test
    fun `a mine does not count as a hit for the streak`() {
        val out = fire(mineBattle(), "C1")
        assertEquals(0, out.state.sides[0].hitStreak)
    }

    @Test
    fun `hidden fleets are placed away from mines`() {
        val s = start(
            rules(mines = listOf(listOf(M2.idx("E4"), M2.idx("E5")), listOf(M2.idx("B6"), M2.idx("B7")))),
            null,
            null,
            first = 0,
        )
        for (side in 0..1) {
            val taken = s.sides[side].ships.flatMap { it.placement.cells() }
            for (mine in s.sides[side].board.mines) {
                val m = s.sides[side].board.cell(mine)
                assertTrue(taken.none { (it.row - m.row) in -1..1 && (it.col - m.col) in -1..1 })
            }
        }
    }
}
