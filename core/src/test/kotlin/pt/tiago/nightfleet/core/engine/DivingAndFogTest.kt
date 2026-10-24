package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.engine.M2.eventsOf
import pt.tiago.nightfleet.core.engine.M2.fire
import pt.tiago.nightfleet.core.engine.M2.pass
import pt.tiago.nightfleet.core.engine.M2.passRounds
import pt.tiago.nightfleet.core.engine.M2.reason
import pt.tiago.nightfleet.core.engine.M2.rules
import pt.tiago.nightfleet.core.engine.M2.start
import pt.tiago.nightfleet.core.engine.M2.weapon
import pt.tiago.nightfleet.core.engine.M2.sub
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/** Diving submarines and fog of war. */
class DivingAndFogTest {

    private val diving = DivingRule(surfaceRounds = 2, submergedRounds = 2)

    /** Side 0 submarine at E8-G8, side 1 submarine at A1-C1. Both dive with the rule. */
    private fun divers(): BattleState = start(
        rules(fleet = M2.SUB, diving = diving),
        listOf(sub("E8")),
        listOf(sub("A1")),
    )

    // ---- diving ------------------------------------------------------------------------------

    @Test
    fun `submarines start surfaced and the phase changes after two rounds`() {
        val s = divers()
        assertEquals(SubmarinePhase.SURFACED, submarinePhase(s))
        assertEquals(2, roundsUntilPhaseChange(s))
    }

    @Test
    fun `without the diving rule there is no phase`() {
        val s = start(rules(fleet = M2.SUB), listOf(sub("E8")), listOf(sub("A1")))
        assertNull(submarinePhase(s))
        assertNull(roundsUntilPhaseChange(s))
    }

    @Test
    fun `the phase is a pure function of the round`() {
        val phases = (0..5).map { phaseOf(diving, it) }
        assertEquals(
            listOf(
                SubmarinePhase.SURFACED,
                SubmarinePhase.SURFACED,
                SubmarinePhase.SUBMERGED,
                SubmarinePhase.SUBMERGED,
                SubmarinePhase.SURFACED,
                SubmarinePhase.SURFACED,
            ),
            phases,
        )
    }

    @Test
    fun `the rounds left in the phase count down`() {
        val one = passRounds(divers(), 1)
        assertEquals(1, roundsUntilPhaseChange(one))
        assertEquals(SubmarinePhase.SURFACED, submarinePhase(one))
    }

    @Test
    fun `the dive event names both sides on the turn the phase changes`() {
        var s = divers()
        var dives = emptyList<Event>()
        repeat(4) {
            val out = pass(s)
            dives = dives + out.events.filter { it is Event.SubmarinesDived }
            s = out.state
        }
        assertEquals(listOf(Event.SubmarinesDived(0), Event.SubmarinesDived(1)), dives)
    }

    @Test
    fun `the surface event comes two rounds after the dive`() {
        var s = passRounds(divers(), 2)
        var surfaced = emptyList<Event>()
        repeat(4) {
            val out = pass(s)
            surfaced = surfaced + out.events.filter { it is Event.SubmarinesSurfaced }
            s = out.state
        }
        assertEquals(listOf(Event.SubmarinesSurfaced(0), Event.SubmarinesSurfaced(1)), surfaced)
    }

    @Test
    fun `a shot on a submerged submarine is a contact with no damage`() {
        val submerged = passRounds(divers(), 2)
        val out = fire(submerged, "B1")
        assertEquals(listOf(Event.SubmergedContact(0, 1, cell("B1"))), eventsOf<Event.SubmergedContact>(out))
        assertTrue(out.state.sides[1].ships[0].hits.isEmpty())
        assertEquals(KnownCell.CONTACT, Observation.of(out.state, 0).enemySea[M2.idx("B1")])
        assertEquals(0, out.state.sides[0].hitStreak)
    }

    @Test
    fun `a contact on a submarine is shootable again when it surfaces`() {
        val contact = fire(passRounds(divers(), 2), "B1").state
        var s = contact
        repeat(3) { s = pass(s).state }
        assertEquals(SubmarinePhase.SURFACED, submarinePhase(s))
        val out = fire(s, "B1")
        assertEquals(listOf(Event.Hit(0, 1, cell("B1"))), eventsOf<Event.Hit>(out))
    }

    @Test
    fun `a surfaced submarine takes ordinary hits`() {
        val out = fire(divers(), "A1")
        assertEquals(listOf(Event.Hit(0, 1, cell("A1"))), eventsOf<Event.Hit>(out))
    }

    @Test
    fun `only submarines dive, a destroyer takes hits in the submerged phase`() {
        val s = start(rules(diving = diving), listOf(dest("G8")), listOf(dest("A1")))
        val out = fire(passRounds(s, 2), "A1")
        assertEquals(listOf(Event.Hit(0, 1, cell("A1"))), eventsOf<Event.Hit>(out))
        assertTrue(eventsOf<Event.SubmergedContact>(out).isEmpty())
    }

    @Test
    fun `a submerged submarine is hidden from the enemy's observation`() {
        val submerged = passRounds(divers(), 2)
        assertEquals(KnownCell.UNKNOWN, Observation.of(submerged, 0).enemySea[M2.idx("B1")])
        assertEquals(SubmarinePhase.SUBMERGED, Observation.of(submerged, 0).publicState.submarinePhase)
    }

    @Test
    fun `submarine phase is public in the observation with the rounds left`() {
        val public = Observation.of(divers(), 1).publicState
        assertEquals(SubmarinePhase.SURFACED, public.submarinePhase)
        assertEquals(2, public.roundsUntilSubmarinePhaseChange)
    }

    // ---- fog ---------------------------------------------------------------------------------

    private val fog = FogRule(missFadeTurns = 2)

    /** Side 0 at G8-H8, side 1 at A4-B4, fog with a fade after two own turns. */
    private fun foggy(): BattleState = start(rules(fog = fog), listOf(dest("G8")), listOf(dest("A4")))

    @Test
    fun `a miss fades from the shooter's view after the given number of its own turns`() {
        var s = fire(foggy(), "C3").state
        assertEquals(KnownCell.WATER, Observation.of(s, 0).enemySea[M2.idx("C3")])
        s = pass(s).state
        s = pass(s).state
        assertEquals(KnownCell.WATER, Observation.of(s, 0).enemySea[M2.idx("C3")])
        s = pass(s).state
        s = pass(s).state
        assertEquals(KnownCell.UNKNOWN, Observation.of(s, 0).enemySea[M2.idx("C3")])
    }

    @Test
    fun `the fade is announced on the turn it happens`() {
        var s = fire(foggy(), "C3").state
        val faded = mutableListOf<Event.MissFaded>()
        repeat(4) {
            val out = pass(s)
            faded += eventsOf<Event.MissFaded>(out)
            s = out.state
        }
        assertEquals(listOf(Event.MissFaded(0, listOf(cell("C3")))), faded)
    }

    @Test
    fun `a faded square can be fired at again and reads as a miss, counting as a shot`() {
        var s = fire(foggy(), "C3").state
        repeat(5) { s = pass(s).state }
        val again = fire(s, "C3")
        assertEquals(listOf(Event.Miss(0, 1, cell("C3"))), eventsOf<Event.Miss>(again))
        assertEquals(2, shotsForStars(again.state, 0))
        assertEquals(KnownCell.WATER, Observation.of(again.state, 0).enemySea[M2.idx("C3")])
    }

    @Test
    fun `before it fades a miss cannot be fired at again`() {
        val s = fire(foggy(), "C3").state
        assertEquals("ALREADY_FIRED", reason(fire(pass(s).state, "C3")))
    }

    @Test
    fun `hits and sinks stay visible under fog`() {
        var s = fire(foggy(), "A4").state
        repeat(4) { s = pass(s).state }
        assertEquals(KnownCell.HIT, Observation.of(s, 0).enemySea[M2.idx("A4")])
    }

    @Test
    fun `automatic water stays visible under fog`() {
        val s = start(rules(fog = fog), listOf(dest("G8")), listOf(dest("A1")))
        var state = fire(s, "A1").state
        state = fire(pass(state).state, "B1").state
        repeat(4) { state = pass(state).state }
        assertEquals(KnownCell.WATER, Observation.of(state, 0).enemySea[M2.idx("A2")])
        assertEquals(KnownCell.SUNK, Observation.of(state, 0).enemySea[M2.idx("A1")])
    }

    @Test
    fun `the owner keeps every shot it received in its own log`() {
        var s = fire(foggy(), "C3").state
        repeat(4) { s = pass(s).state }
        assertEquals(1, Observation.of(s, 1).ownSea.incoming.entries.count { it.cell == M2.idx("C3") })
    }

    @Test
    fun `a torpedo trail fades like a miss`() {
        val s = start(
            rules(fog = fog, weapons = mapOf(Weapon.TORPEDO to WeaponConfig(1, 1))),
            listOf(dest("G8")),
            listOf(dest("A4")),
        )
        var state = weapon(s, Weapon.TORPEDO, M2.line(true, 0)).state
        assertEquals(KnownCell.WATER, Observation.of(state, 0).enemySea[M2.idx("B1")])
        repeat(4) { state = pass(state).state }
        assertEquals(KnownCell.UNKNOWN, Observation.of(state, 0).enemySea[M2.idx("B1")])
    }

    @Test
    fun `without the fog rule misses never fade`() {
        val s = start(rules(), listOf(dest("G8")), listOf(dest("A4")))
        var state = fire(s, "C3").state
        repeat(6) { state = pass(state).state }
        assertEquals(KnownCell.WATER, Observation.of(state, 0).enemySea[M2.idx("C3")])
        assertTrue(eventsOf<Event.MissFaded>(pass(state)).isEmpty())
    }
}
