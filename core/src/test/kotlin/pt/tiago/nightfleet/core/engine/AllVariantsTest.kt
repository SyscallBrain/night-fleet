package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** Full battles with every variant enabled at once. */
class AllVariantsTest {

    /** Golden: aggregate hash of 20 seeded random battles with every variant on. */
    private val goldenAllVariants = 2693146800401081047L

    /** Classic 10 by 10 sea with salvo, diving, fog, moving ships, mines and all the weapons. */
    private fun everything(seed: Long): RuleSet {
        val rng = SplitMix64(seed xor 0x77L)
        val mines = List(2) { List(3) { rng.nextInt(100) }.distinct().sorted() }
        return RuleSet(
            salvo = true,
            diving = DivingRule(surfaceRounds = 2, submergedRounds = 2),
            fog = FogRule(missFadeTurns = 4),
            movingShips = true,
            mines = mines,
            weapons = mapOf(
                Weapon.SONAR to WeaponConfig(initial = 1, max = 2, earn = listOf(EarnRule(EarnOn.EVERY, 3))),
                Weapon.TORPEDO to WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK))),
                Weapon.RECON to WeaponConfig(initial = 1, max = 1, earn = listOf(EarnRule(EarnOn.STREAK, 3))),
                Weapon.CROSS_SHELL to WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK))),
                Weapon.REPAIR to WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.EVERY, 5))),
            ),
        )
    }

    /** Creates the battle with hidden fleets, then places decoys on squares that are valid for the chosen fleets. */
    private fun create(seed: Long, rules: RuleSet): BattleState {
        val first = SeedStreams.firstPlayer(seed)
        val hidden = (BattleSetup.create(rules, MissionType.DUEL, listOf(null, null), first, seed) as SetupResult.Created).state
        val rng = SplitMix64(seed xor 0x99L)
        val decoys = List(2) { side ->
            val board = hidden.sides[side].board
            val ships: List<Placement> = hidden.sides[side].ships.map { it.placement }
            val valid = (0 until board.size).filter { PlacementValidator.validateDecoy(board, ships, board.cell(it)).isEmpty() }
            List(2) { board.cell(valid[rng.nextInt(valid.size)]) }.distinct()
        }
        val placements = hidden.sides.map { side -> side.ships.map { it.placement } }
        return (BattleSetup.create(rules, MissionType.DUEL, placements, first, seed, decoys = decoys) as SetupResult.Created).state
    }

    /** Plays one battle with a random agent that only reads its own observation. Returns the final state. */
    private fun play(seed: Long, maxActions: Int = 6000): BattleState {
        var state = create(seed, everything(seed))
        val rng = SplitMix64(seed * 31 + 7)
        var actions = 0
        while (state.result == null && actions++ < maxActions) {
            val actor = state.current
            val obs = Observation.of(state, actor)
            val unknown = obs.enemySea.indices.filter { obs.enemySea[it] == KnownCell.UNKNOWN }
            val board = state.sides[1 - actor].board
            fun randomCell() = board.cell(rng.nextInt(board.size))
            val roll = rng.nextInt(100)
            val action: Action = when {
                roll < 10 && unknown.size >= 2 -> Action.Salvo(
                    unknown.shuffled(java.util.Random(rng.nextLong())).take(minOf(state.sides[actor].ships.count { !it.sunk }, unknown.size))
                        .map { board.cell(it) },
                )
                roll < 25 -> weaponAction(state, actor, rng)
                roll < 30 -> Action.MoveShip(rng.nextInt(state.sides[actor].ships.size), if (rng.nextInt(2) == 0) 1 else -1)
                roll < 35 -> Action.Timeout
                unknown.isNotEmpty() -> Action.Fire(board.cell(unknown[rng.nextInt(unknown.size)]))
                else -> Action.Fire(randomCell())
            }
            var out = Engine.apply(state, action)
            if (Fx.illegalReason(out) != null) {
                out = Engine.apply(state, if (unknown.isNotEmpty()) Action.Fire(board.cell(unknown[0])) else Action.Timeout)
                if (Fx.illegalReason(out) != null) out = Engine.apply(state, Action.Timeout)
            }
            state = out.state
        }
        return state
    }

    private fun weaponAction(state: BattleState, actor: Int, rng: SplitMix64): Action {
        val charged = Weapon.entries.filter { (state.sides[actor].charges[it] ?: 0) > 0 && it != Weapon.DECOY }
        if (charged.isEmpty()) return Action.Timeout
        val weapon = charged[rng.nextInt(charged.size)]
        val board = state.sides[1 - actor].board
        val target: WeaponTarget = when (weapon) {
            Weapon.TORPEDO, Weapon.RECON -> WeaponTarget.LineTarget(rng.nextInt(2) == 0, rng.nextInt(10), rng.nextInt(2) == 0)
            Weapon.REPAIR -> WeaponTarget.CellTarget(state.sides[actor].board.cell(rng.nextInt(state.sides[actor].board.size)))
            else -> WeaponTarget.CellTarget(board.cell(rng.nextInt(board.size)))
        }
        return Action.UseWeapon(weapon, target)
    }

    @Test
    fun `random battles with every variant on always terminate`() {
        for (seed in 0 until 20L) {
            val final = play(seed)
            assertNotNull("seed $seed did not finish", final.result)
            assertTrue(final.turn <= everything(seed).maxTurns)
        }
    }

    @Test
    fun `every variant is exercised in at least one of the random battles`() {
        val events = (0 until 20L).flatMap { seed -> replayEvents(seed) }
        assertTrue(events.any { it is Event.MineHit })
        assertTrue(events.any { it is Event.DecoyRevealed })
        assertTrue(events.any { it is Event.SubmergedContact })
        assertTrue(events.any { it is Event.MissFaded })
        assertTrue(events.any { it is Event.ChargeEarned })
        assertTrue(events.any { it is Event.EnemyShipMoved })
    }

    private fun replayEvents(seed: Long): List<Event> {
        var state = create(seed, everything(seed))
        val all = ArrayList<Event>()
        val rng = SplitMix64(seed * 31 + 7)
        var n = 0
        while (state.result == null && n++ < 6000) {
            val actor = state.current
            val obs = Observation.of(state, actor)
            val unknown = obs.enemySea.indices.filter { obs.enemySea[it] == KnownCell.UNKNOWN }
            val board = state.sides[1 - actor].board
            val roll = rng.nextInt(100)
            val action: Action = when {
                roll < 25 -> weaponAction(state, actor, rng)
                roll < 30 -> Action.MoveShip(rng.nextInt(state.sides[actor].ships.size), if (rng.nextInt(2) == 0) 1 else -1)
                roll < 35 -> Action.Timeout
                unknown.isNotEmpty() -> Action.Fire(board.cell(unknown[rng.nextInt(unknown.size)]))
                else -> Action.Timeout
            }
            var out = Engine.apply(state, action)
            if (Fx.illegalReason(out) != null) out = Engine.apply(state, Action.Timeout)
            all += out.events
            state = out.state
        }
        return all
    }

    @Test
    fun `the all-variant golden hash matches`() {
        var acc = 0L
        for (seed in 0 until 20L) {
            acc = pt.tiago.nightfleet.core.random.SplitMix64.mix(acc xor StateHasher.hash(play(seed)))
        }
        assertEquals(goldenAllVariants, acc)
    }

    @Test
    fun `a battle with every variant survives a round trip in the middle`() {
        val seed = 5L
        var state = create(seed, everything(seed))
        val rng = SplitMix64(3)
        repeat(40) {
            if (state.result != null) return@repeat
            val board = state.sides[1 - state.current].board
            val action: Action = if (rng.nextInt(3) == 0) Action.Fire(board.cell(rng.nextInt(board.size))) else Action.Timeout
            state = Engine.apply(state, action).state
        }
        val back = BattleCodec.decodeState(BattleCodec.encodeState(state))
        assertEquals(state, back)
        assertEquals(StateHasher.hash(state), StateHasher.hash(back))
    }

    @Test
    fun `every new action type and weapon target survives JSON`() {
        val actions = listOf(
            Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(Cell(2, 3))),
            Action.UseWeapon(Weapon.TORPEDO, WeaponTarget.LineTarget(isRow = true, index = 4, fromStart = false)),
            Action.UseWeapon(Weapon.RECON, WeaponTarget.LineTarget(isRow = false, index = 0)),
            Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(Cell(9, 9))),
            Action.UseWeapon(Weapon.REPAIR, WeaponTarget.CellTarget(Cell(1, 1))),
            Action.Salvo(listOf(Cell(0, 0), Cell(0, 1))),
            Action.MoveShip(1, -1),
            Action.Repair(Cell(5, 5)),
        )
        for (action in actions) {
            assertEquals(action, BattleCodec.decodeAction(BattleCodec.encodeAction(action)))
        }
    }

    @Test
    fun `the rule set with every variant round trips with the same hash`() {
        val rules = everything(9L)
        val s = create(9L, rules)
        val back = BattleCodec.decodeState(BattleCodec.encodeState(s))
        assertEquals(rules, back.rules)
        assertEquals(StateHasher.hash(s), StateHasher.hash(back))
    }
}
