package pt.tiago.nightfleet.game.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.data.AnalysisSummary
import pt.tiago.nightfleet.data.WorstShot
import pt.tiago.nightfleet.game.battle.AdmiralOpponent
import pt.tiago.nightfleet.game.battle.BattleFixtures
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleRecords
import pt.tiago.nightfleet.game.battle.ParityBrain
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.game.replay.MatchReplay
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.game.freeplay.OpponentLevel

/** The post-battle analysis: wasted shots, the worst shots, precision and the officer's shots. */
class MatchAnalysisTest {

    private fun quickStart(seed: Long): BattleState =
        (BattleMatch.create(BattleFixtures.randomPlacements(seed), seed) as SetupResult.Created).state

    /** A quick battle played out with the parity player and Coral, as a replay. */
    private fun playedReplay(seed: Long): Pair<MatchReplay, BattleMatch> {
        val placements = BattleFixtures.randomPlacements(seed)
        var match = BattleMatch((BattleMatch.create(placements, seed) as SetupResult.Created).state)
        val coral = AdmiralOpponent.forLevel("coral", OpponentLevel.MEDIUM, seed)
        var steps = 0
        while (!match.finished && steps++ < 600) {
            val action = if (match.state.current == BattleMatch.PLAYER) {
                ParityBrain.decide(Observation.of(match.state, 0), SplitMix64(steps.toLong()))
            } else {
                coral.decide(BattleMatch.admiralObservation(match), BattleMatch.admiralRng(seed, match.state.turn))
            }
            match = match.apply(action).match
        }
        val payload = BattleRecords.replayPayload(seed, placements, match.actions, null)
        return MatchReplay.fromPayload(payload)!! to match
    }

    @Test
    fun aShotOnTheDiagonalOfAHitIsWasted() {
        val start = quickStart(91L)
        // The admiral's first ship: one of its squares is the hit, the diagonal beyond it cannot hold a ship.
        val ship = start.sides[1].ships.first().placement.cells().first()
        val diagonal = listOf(1 to 1, -1 to -1, 1 to -1, -1 to 1)
            .map { (dr, dc) -> Cell(ship.row + dr, ship.col + dc) }
            .first { cell ->
                cell.row in 0 until 10 && cell.col in 0 until 10 &&
                    start.sides[1].board.terrainAt(cell.row * 10 + cell.col) == Terrain.WATER &&
                    start.sides[1].ships.none { s -> s.placement.cells().contains(cell) }
            }
        // The player fires at the ship (a hit), the admiral passes, the player fires on the diagonal.
        val actions = listOf(Action.Fire(ship), Action.Timeout, Action.Fire(diagonal))
        var match = BattleMatch(start)
        for (action in actions) {
            val step = match.apply(action)
            assertTrue("accepted $action", step.accepted)
            match = step.match
        }
        val payload = BattleRecords.replayPayload(91L, BattleFixtures.randomPlacements(91L), match.actions, null)
        val analysis = MatchAnalyzer.analyze(MatchReplay.fromPayload(payload)!!, withOfficer = false)
        assertEquals(2, analysis.shots.size)
        assertEquals(false, analysis.shots[0].quality.wasted)
        assertTrue("the diagonal shot is wasted", analysis.shots[1].quality.wasted)
        assertEquals(0, analysis.shots[1].quality.probabilityPermille)
        assertEquals(1, analysis.summary.wasted)
        assertEquals(2, analysis.summary.shots)
    }

    @Test
    fun theSummaryHasAtMostThreeWorstShotsWorstFirst() {
        val (replay, _) = playedReplay(92L)
        val analysis = MatchAnalyzer.analyze(replay, withOfficer = false)
        val worst = analysis.summary.worst
        assertTrue(worst.size <= MatchAnalyzer.WORST)
        val losses = worst.map { it.bestPermille - it.permille }
        assertEquals(losses.sortedDescending(), losses)
        assertTrue(worst.all { it.bestPermille > it.permille })
    }

    @Test
    fun precisionIsTheHitsOfTheShots() {
        val (replay, match) = playedReplay(93L)
        val analysis = MatchAnalyzer.analyze(replay, withOfficer = false)
        val shots = analysis.shots.size
        assertEquals(shots, analysis.summary.shots)
        val hits = match.state.sides[1].incoming.entries.count { it.attacker == BattleMatch.PLAYER && (it.result is pt.tiago.nightfleet.core.engine.ShotResult.Hit || it.result is pt.tiago.nightfleet.core.engine.ShotResult.Sunk) }
        assertEquals(QuickBattle.precision(hits, shots), analysis.summary.precision)
    }

    @Test
    fun shotsAreNumberedInPlayOrder() {
        val (replay, _) = playedReplay(94L)
        val analysis = MatchAnalyzer.analyze(replay, withOfficer = false)
        assertEquals((1..analysis.shots.size).toList(), analysis.shots.map { it.number })
    }

    @Test
    fun theOfficerNeedsAFiniteNumberOfShotsAndTheSameEachTime() {
        val (replay, _) = playedReplay(95L)
        val first = MatchAnalyzer.officerShots(replay.stateAt(0))
        assertNotNull(first)
        assertTrue(first!! in 1..100)
        assertEquals(first, MatchAnalyzer.officerShots(replay.stateAt(0)))
    }

    @Test
    fun theSummaryKeepsItsWorstShotsAsTheLogbookStoresThem() {
        val summary = AnalysisSummary(
            shots = 40,
            wasted = 3,
            precision = 50,
            worst = listOf(WorstShot(number = 7, cell = 22, permille = 0, bestCell = 41, bestPermille = 900)),
            officerShots = 44,
        )
        assertEquals(summary, summary.copy())
        assertEquals(1, summary.worst.size)
        assertEquals(44, summary.officerShots)
    }
}
