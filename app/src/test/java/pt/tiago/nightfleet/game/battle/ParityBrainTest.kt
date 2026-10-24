package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64

class ParityBrainTest {

    @Test
    fun neverFiresAtASquareThatIsNotUnknown() {
        // Both sides are played by the brain: every decision must be a new, legal shot.
        var match = BattleFixtures.newMatch()
        var rng = SplitMix64(99)
        var turns = 0
        while (!match.finished && turns < 400) {
            val obs = Observation.of(match.state, match.state.current)
            val action = ParityBrain.decide(obs, rng) as Action.Fire
            val index = action.cell.row * obs.width + action.cell.col
            assertEquals("must fire at an unknown square", KnownCell.UNKNOWN, obs.enemySea[index])
            val step = match.apply(action)
            assertTrue("accepted", step.accepted)
            match = step.match
            turns++
            rng = SplitMix64(rng.nextLong())
        }
        assertTrue("the battle must end", match.finished)
    }

    @Test
    fun targetsASquareNextToAnOpenHit() {
        // The admiral hits the player's carrier (row 0) at column 2.
        val target = targetOf(admiralShotsOn(BattleFixtures.fixedPlacements(), listOf(2)), seed = 1)
        assertTrue("expected a neighbour of (0,2), got $target", target in setOf(1, 3, 12))
    }

    @Test
    fun extendsALineOfTwoHits() {
        // Hits at (0,1) and (0,2): the line continues at (0,0) or (0,3).
        val target = targetOf(admiralShotsOn(BattleFixtures.fixedPlacements(), listOf(1, 2)), seed = 3)
        assertTrue("expected a line end, got $target", target == 0 || target == 3)
    }

    @Test
    fun decisionDependsOnlyOnTheObservation() {
        // Two different hidden fleets, the same shots on squares empty in both: identical observation.
        val shots = listOf(99, 79, 59)
        val a = admiralShotsOn(BattleFixtures.fixedPlacements(0), shots)
        val b = admiralShotsOn(BattleFixtures.fixedPlacements(1), shots)
        val obsA = Observation.of(a, 1)
        val obsB = Observation.of(b, 1)
        assertEquals(obsA.enemySea, obsB.enemySea)
        assertEquals(ParityBrain.decide(obsA, SplitMix64(5)), ParityBrain.decide(obsB, SplitMix64(5)))
    }

    @Test
    fun sameSeedGivesSameDecision() {
        val obs = Observation.of(admiralShotsOn(BattleFixtures.fixedPlacements(), listOf(2)), 1)
        assertEquals(ParityBrain.decide(obs, SplitMix64(8)), ParityBrain.decide(obs, SplitMix64(8)))
    }

    @Test
    fun firstShotOfAnEmptySeaIsOnTheBoard() {
        val obs = Observation.of(BattleFixtures.newMatch().state, 1)
        val cell = (ParityBrain.decide(obs, SplitMix64(4)) as Action.Fire).cell
        assertTrue(cell.row in 0..9 && cell.col in 0..9)
    }

    /** The admiral (side 1) fires at the given squares of the player's sea; the player passes in between. */
    private fun admiralShotsOn(placements: List<Placement>, indices: List<Int>): BattleState {
        var match = BattleFixtures.matchWith(placements)
        for (index in indices) {
            if (match.state.current == 0) match = match.apply(Action.Timeout).match
            match = match.apply(Action.Fire(BattleFixtures.cell(index))).match
        }
        return match.state
    }

    private fun targetOf(state: BattleState, seed: Long): Int {
        val action = ParityBrain.decide(Observation.of(state, 1), SplitMix64(seed)) as Action.Fire
        return action.cell.row * 10 + action.cell.col
    }
}
