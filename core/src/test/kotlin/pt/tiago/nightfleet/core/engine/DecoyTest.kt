package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.cellTarget
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.engine.M2.eventsOf
import pt.tiago.nightfleet.core.engine.M2.fire
import pt.tiago.nightfleet.core.engine.M2.pass
import pt.tiago.nightfleet.core.engine.M2.reason
import pt.tiago.nightfleet.core.engine.M2.rules
import pt.tiago.nightfleet.core.engine.M2.start
import pt.tiago.nightfleet.core.engine.M2.weapon
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/** Decoys (Iscos): placement, the hit that reads as a hit, the reveal and the star count. */
class DecoyTest {

    private val decoyAt = cell("D5")

    /** Side 0 at G8-H8; side 1 at A1-B1 with a decoy on D5. */
    private fun battle(extra: Map<Weapon, WeaponConfig> = emptyMap()): BattleState = start(
        rules(weapons = extra),
        listOf(dest("G8")),
        listOf(dest("A1")),
        decoys1 = listOf(decoyAt),
    )

    @Test
    fun `decoys are placed at setup on their own sea`() {
        val s = battle()
        assertEquals(listOf(M2.idx("D5")), s.sides[1].decoys.map { it.cell })
        assertTrue(s.sides[0].decoys.isEmpty())
    }

    @Test
    fun `a decoy touching a ship is refused at setup`() {
        val reasons = M2.setupReasons(rules(), listOf(dest("G8")), listOf(dest("A1")), listOf(emptyList(), listOf(cell("B2"))))
        assertTrue(reasons.any { it.startsWith("DECOY side 1") })
    }

    @Test
    fun `a decoy on a mine or twice on the same square is refused at setup`() {
        val onMine = M2.setupReasons(rules(mines = listOf(emptyList(), listOf(M2.idx("D5")))), listOf(dest("G8")), listOf(dest("A1")), listOf(emptyList(), listOf(decoyAt)))
        assertTrue(onMine.any { it.contains("MINE") })
        val twice = M2.setupReasons(rules(), listOf(dest("G8")), listOf(dest("A1")), listOf(emptyList(), listOf(decoyAt, decoyAt)))
        assertTrue(twice.any { it.contains("DUPLICATE") })
    }

    @Test
    fun `a shot on a decoy reports a hit to the shooter and sinks nothing`() {
        val out = fire(battle(), "D5")
        assertEquals(listOf(Event.Hit(0, 1, decoyAt)), eventsOf<Event.Hit>(out))
        assertTrue(out.state.sides[1].ships.all { it.hits.isEmpty() })
        assertEquals(KnownCell.HIT, Observation.of(out.state, 0).enemySea[M2.idx("D5")])
    }

    @Test
    fun `the decoy is revealed at the end of the shooter's next turn`() {
        val hit = fire(battle(), "D5").state
        val afterOpponent = pass(hit)
        assertTrue(eventsOf<Event.DecoyRevealed>(afterOpponent).isEmpty())
        assertEquals(KnownCell.HIT, Observation.of(afterOpponent.state, 0).enemySea[M2.idx("D5")])

        val revealed = pass(afterOpponent.state)
        assertEquals(listOf(Event.DecoyRevealed(0, 1, decoyAt)), eventsOf<Event.DecoyRevealed>(revealed))
        assertEquals(KnownCell.DECOY_WRECK, Observation.of(revealed.state, 0).enemySea[M2.idx("D5")])
    }

    @Test
    fun `the owner sees its revealed decoy, the shooter sees the wreck and the opponent sees nothing`() {
        val revealed = passUntilRevealed(fire(battle(), "D5").state)
        assertEquals(KnownCell.UNKNOWN, Observation.of(revealed, 1).enemySea[M2.idx("D5")])
        assertTrue(Observation.of(revealed, 1).ownSea.decoys.single().revealed)
    }

    @Test
    fun `a decoy is never part of the fleet count`() {
        val out = fire(battle(), "D5").state
        assertNull(out.result)
        assertEquals(listOf(ShipClass.DESTROYER), Observation.of(out, 0).enemyRemaining.map { it.shipClass })
    }

    @Test
    fun `a decoy hit counts as a hit for the streak and for the star count until revealed`() {
        val out = fire(battle(), "D5").state
        assertEquals(1, out.sides[0].hitStreak)
        assertEquals(1, shotsForStars(out, 0))
        assertEquals(1, hitsForPrecision(out, 0))
    }

    @Test
    fun `once revealed the decoy hit counts as a miss for precision and still as a shot`() {
        val revealed = passUntilRevealed(fire(battle(), "D5").state)
        assertEquals(1, shotsForStars(revealed, 0))
        assertEquals(0, hitsForPrecision(revealed, 0))
    }

    @Test
    fun `the same decoy cannot be shot twice`() {
        val hit = fire(battle(), "D5").state
        assertEquals("ALREADY_FIRED", reason(fire(pass(hit).state, "D5")))
    }

    @Test
    fun `a cross that covers a decoy reads it as a hit`() {
        val s = start(rules(weapons = mapOf(Weapon.CROSS_SHELL to WeaponConfig(1, 1))), listOf(dest("G8")), listOf(dest("A1")), decoys1 = listOf(cell("C3")))
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("C3"))
        assertTrue(eventsOf<Event.Hit>(out).any { it.cell == cell("C3") })
        assertEquals(5, shotsForStars(out.state, 0))
    }

    @Test
    fun `the decoy state changes the hash of the battle`() {
        val a = battle()
        val b = start(rules(), listOf(dest("G8")), listOf(dest("A1")))
        assertTrue(StateHasher.hash(a) != StateHasher.hash(b))
    }

    private fun passUntilRevealed(state: BattleState): BattleState = pass(pass(state).state).state

}
