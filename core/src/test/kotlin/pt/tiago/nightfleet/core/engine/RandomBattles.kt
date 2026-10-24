package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.RuleSet

/** Helpers that play whole battles with the public API only. */
internal object RandomBattles {

    /**
     * A DUEL with hidden fleets generated from [seed]. Each side fires at a uniformly random square
     * it does not know yet, chosen from its own [Observation]. Returns the final state.
     */
    fun duel(seed: Long, rules: RuleSet = RuleSet()): BattleState {
        val created = BattleSetup.create(
            rules = rules,
            mission = MissionType.DUEL,
            placements = listOf(null, null),
            firstPlayer = SeedStreams.firstPlayer(seed),
            attemptSeed = seed,
        )
        var state = (created as? SetupResult.Created)?.state ?: throw AssertionError("setup rejected: $created")
        val rng = SeedStreams.rng(seed, SeedStreams.Stream.AI)
        var guard = 0
        while (state.result == null) {
            val unknown = unknownSquares(Observation.of(state, state.current))
            val pick = unknown[rng.nextInt(unknown.size)]
            val out = Engine.apply(state, Action.Fire(state.sides[1 - state.current].board.cell(pick)))
            assertNull(Fx.illegalReason(out))
            state = out.state
            if (++guard > 1_000) throw AssertionError("battle did not end")
        }
        return state
    }

    /** Squares of the enemy sea the viewer has not shot at, ascending. */
    fun unknownSquares(observation: Observation): List<Int> =
        observation.enemySea.indices.filter { observation.enemySea[it] == KnownCell.UNKNOWN }

    fun assertWaterOnly(state: BattleState) {
        for (side in state.sides) {
            for (index in side.board.terrain.indices) {
                if (side.board.terrainAt(index) != Terrain.WATER) {
                    assertTrue(side.incoming.entries.none { it.cell == index })
                }
            }
        }
        assertEquals(2, state.sides.size)
        assertTrue(state.result != null)
    }
}
