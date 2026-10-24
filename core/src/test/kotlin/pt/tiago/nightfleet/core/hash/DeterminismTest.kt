package pt.tiago.nightfleet.core.hash

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Fx
import pt.tiago.nightfleet.core.engine.RandomBattles
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/**
 * Determinism: the same seed and the same actions give the same hash; golden hashes
 * pin the rules. A golden changes only when [ENGINE_VERSION] changes.
 */
class DeterminismTest {

    /** Golden: a scripted classic duel (seed 2024, first-unknown-square shooting, one timeout). */
    private val goldenScripted = 5737748764626553192L

    /** Golden: aggregate hash of 200 seeded random-vs-random classic duels. */
    private val goldenAggregate = -324386755541603840L

    /** The scripted duel of [ScriptedDuel] (shared with the Android diagnostics). */
    private fun scripted(seed: Long): BattleState = ScriptedDuel.play(seed)

    @Test
    fun `scripted battle hash matches the golden value`() {
        assertEquals(goldenScripted, StateHasher.hash(scripted(2024L)))
        assertEquals(goldenScripted, ScriptedDuel.GOLDEN_HASH)
    }

    @Test
    fun `scripted battle hash is the same when computed again`() {
        assertEquals(StateHasher.hash(scripted(2024L)), StateHasher.hash(scripted(2024L)))
    }

    @Test
    fun `aggregate hash of 200 random battles matches the golden value`() {
        var acc = 0L
        var finished = 0
        for (seed in 0 until 200L) {
            val final = RandomBattles.duel(seed)
            if (final.result != null) finished++
            acc = SplitMix64.mix(acc xor StateHasher.hash(final))
        }
        assertEquals(200, finished)
        assertEquals(goldenAggregate, acc)
    }

    @Test
    fun `different seeds give different final hashes`() {
        assertNotEquals(StateHasher.hash(RandomBattles.duel(1L)), StateHasher.hash(RandomBattles.duel(2L)))
    }

    @Test
    fun `hash changes when a shot is added`() {
        val s = Fx.duel()
        val after = Engine.apply(s, Action.Fire(Cell(2, 2))).state
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(after))
    }

    @Test
    fun `hash changes with the attempt seed`() {
        val a = scripted(3L)
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(a.copy(attemptSeed = a.attemptSeed + 1)))
    }

    @Test
    fun `hash changes with the engine version`() {
        val s = Fx.duel()
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(s.copy(engineVersion = s.engineVersion + 1)))
    }

    @Test
    fun `hash changes with the rules`() {
        val s = Fx.duel()
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(s.copy(rules = s.rules.copy(maxTurns = 399))))
    }

    @Test
    fun `hash changes with the turn limit and hit streak`() {
        val s = Fx.duel()
        val streak = s.copy(sides = s.sides.mapIndexed { i, side -> if (i == 0) side.copy(hitStreak = 1) else side })
        assertNotEquals(StateHasher.hash(s), StateHasher.hash(streak))
    }

    @Test
    fun `hash of the same state is stable`() {
        val s = scripted(7L)
        assertEquals(StateHasher.hash(s), StateHasher.hash(s.copy()))
    }

    @Test
    fun `state round trips through JSON with the same hash`() {
        val s = scripted(11L)
        val back = BattleCodec.decodeState(BattleCodec.encodeState(s))
        assertEquals(s, back)
        assertEquals(StateHasher.hash(s), StateHasher.hash(back))
    }

    @Test
    fun `round trip keeps the hash for a battle in progress`() {
        var s = Fx.duel()
        s = Engine.apply(s, Action.Fire(Cell(2, 2))).state
        s = Engine.apply(s, Action.Fire(Cell(0, 0))).state
        val back = BattleCodec.decodeState(BattleCodec.encodeState(s))
        assertEquals(StateHasher.hash(s), StateHasher.hash(back))
    }

    @Test
    fun `round trip of a hunt keeps the budget and the hidden fleet`() {
        val created = BattleSetup.create(Fx.rules(), MissionType.HUNT, listOf(listOf(Fx.BOTTOM_RIGHT), null), 0, 5L, 9)
        val s = (created as SetupResult.Created).state
        val back = BattleCodec.decodeState(BattleCodec.encodeState(s))
        assertEquals(s, back)
        assertEquals(9, back.shotBudget)
    }

    @Test
    fun `every action type round trips through JSON`() {
        val actions = listOf(
            Action.Fire(Cell(3, 4)),
            Action.Salvo(listOf(Cell(0, 0), Cell(7, 7))),
            Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(Cell(1, 1))),
            Action.MoveShip(2, -1),
            Action.Repair(Cell(0, 0)),
            Action.Timeout,
        )
        for (action in actions) {
            assertEquals(action, BattleCodec.decodeAction(BattleCodec.encodeAction(action)))
        }
    }

    @Test
    fun `placements round trip through JSON`() {
        val placements = listOf(
            Placement(ShipClass.SEAPLANE, Cell(2, 3), 3),
            Placement(ShipClass.MIDGET_SUB, Cell(0, 0)),
        )
        val text = Json.encodeToString(ListSerializer(Placement.serializer()), placements)
        assertEquals(placements, Json.decodeFromString(ListSerializer(Placement.serializer()), text))
    }

    @Test
    fun `rule set round trips through JSON`() {
        val rules = RuleSet(width = 12, height = 12, fleet = Fleet.LARGE, shipsMayTouch = true, maxTurns = 250)
        val text = Json.encodeToString(RuleSet.serializer(), rules)
        assertEquals(rules, Json.decodeFromString(RuleSet.serializer(), text))
    }

    @Test
    fun `engine version constant is 1 for now`() {
        assertEquals(1, ENGINE_VERSION)
        assertTrue(scripted(1L).engineVersion == ENGINE_VERSION)
    }
}
