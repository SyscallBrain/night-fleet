package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.data.DifficultyStats
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.SavedMatch

class BattleSaveTest {

    private val seed = BattleFixtures.SEED

    /** A match with a few player and admiral moves played through the engine. */
    private fun playedMatch(): BattleMatch {
        var match = BattleFixtures.matchWith(BattleFixtures.randomPlacements(), seed)
        for (index in listOf(0, 1, 2, 55, 56, 57, 99, 98)) {
            if (match.finished) break
            if (match.state.current == 0) {
                match = match.playerFire(index).match
            } else {
                match = match.apply(Action.Timeout).match
            }
        }
        return match
    }

    @Test
    fun aSavedMatchRestoresToTheSameState() {
        val match = playedMatch()
        val saved = BattleSave.toSaved(match, seed, BattleFixtures.randomPlacements(), nowMillis = 1L)
        val restored = BattleSave.restore(saved)
        assertTrue("restored: $restored", restored is RestoreResult.Restored)
        restored as RestoreResult.Restored
        assertEquals(match.state, restored.match.state)
        assertEquals(seed, restored.attemptSeed)
        assertEquals(StateHasher.hash(match.state), StateHasher.hash(restored.match.state))
    }

    @Test
    fun theSaveCarriesTheSeedPlacementsActionsAndHash() {
        val match = playedMatch()
        val saved = BattleSave.toSaved(match, seed, BattleFixtures.randomPlacements(), nowMillis = 1L)
        assertEquals(MatchKind.FREE_PLAY, saved.kind)
        assertEquals(ENGINE_VERSION, saved.engineVersion)
        assertEquals(listOf(seed), saved.seeds)
        assertEquals(5, saved.placements.size)
        assertEquals(match.actions.size, saved.actions.size)
        assertEquals(StateHasher.hash(match.state), saved.expectedHash)
        assertEquals(BattleSave.matchIdOf(seed), saved.matchId)
    }

    @Test
    fun aChangedActionIsRejected() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        val first = BattleCodec.decodeAction(saved.actions.first()) as Action.Fire
        val other = Cell((first.cell.row + 5) % 10, first.cell.col)
        val tampered = saved.copy(actions = listOf(BattleCodec.encodeAction(Action.Fire(other))) + saved.actions.drop(1))
        val result = BattleSave.restore(tampered)
        assertTrue("tampered save must be discarded, got $result", result is RestoreResult.Discarded)
    }

    @Test
    fun aTruncatedActionListIsRejectedByTheHash() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        val result = BattleSave.restore(saved.copy(actions = saved.actions.dropLast(1)))
        assertEquals(RestoreResult.Discarded("HASH_MISMATCH"), result)
    }

    @Test
    fun aWrongHashIsRejected() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        assertEquals(RestoreResult.Discarded("HASH_MISMATCH"), BattleSave.restore(saved.copy(expectedHash = saved.expectedHash + 1)))
    }

    @Test
    fun anotherEngineVersionIsDiscarded() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        assertEquals(RestoreResult.Discarded("ENGINE_VERSION"), BattleSave.restore(saved.copy(engineVersion = ENGINE_VERSION + 1)))
    }

    @Test
    fun aCampaignSlotIsNotAQuickBattle() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        assertEquals(RestoreResult.Discarded("KIND"), BattleSave.restore(saved.copy(kind = MatchKind.CAMPAIGN)))
    }

    @Test
    fun aMalformedPlacementLineIsDiscarded() {
        val saved = BattleSave.toSaved(playedMatch(), seed, BattleFixtures.randomPlacements(), 1L)
        assertEquals(RestoreResult.Discarded("PLACEMENTS"), BattleSave.restore(saved.copy(placements = listOf("NOT A SHIP"))))
    }

    @Test
    fun placementCodecRoundTrips() {
        val placements = listOf(
            Placement(ShipClass.CARRIER, Cell(0, 0), 1),
            Placement(ShipClass.MIDGET_SUB, Cell(9, 9), 0),
        )
        assertEquals(placements, PlacementCodec.decodeAll(PlacementCodec.encodeAll(placements)))
        assertEquals(null, PlacementCodec.decodeAll(listOf("CARRIER x 1 0")))
    }

    @Test
    fun aFinishedBattleCountsOnceInTheStatistics() {
        val match = playedMatch()
        val summary = QuickBattle.summarize(match.state)
        assertEquals(null, summary) // not finished yet: no summary
        val fake = QuickBattle.Summary(
            won = true, reason = pt.tiago.nightfleet.core.engine.EndReason.FLEET_SUNK, shots = 40, hits = 20,
            precision = 50, stars = 2, enemyShipsSunk = 5, enemyShipsTotal = 5, ownShipsSunk = 2,
            longestHitStreak = 4, sunkByClass = mapOf("DESTROYER" to 1),
        )
        val shots = listOf(0, 1, 2, 11)
        val updated = BattleRecords.withQuickBattle(Profile(), fake, shots, width = 10)
        val stats = updated.stats[QuickBattle.DIFFICULTY]!!
        assertEquals(1, stats.battles)
        assertEquals(1, stats.wins)
        assertEquals(40, stats.shots)
        assertEquals(4, stats.longestHitStreak)
        assertEquals(1, stats.shotHeatmap[0])
        assertEquals(1, stats.shotHeatmap[11])
        assertEquals(1, stats.shipsSunkByClass["DESTROYER"])
        assertEquals(DifficultyStats().shotHeatmap.size, stats.shotHeatmap.size)
    }

    @Test
    fun theLogbookEntryKeepsTheReplayPayload() {
        val match = playedMatch()
        val summary = fakeSummary()
        val entry = BattleRecords.logEntry(
            matchId = BattleSave.matchIdOf(seed),
            attemptSeed = seed,
            placements = BattleFixtures.randomPlacements(),
            match = match,
            summary = summary,
            ownMap = ObservationMapper.ownSea(pt.tiago.nightfleet.core.observation.Observation.of(match.state, 0), null),
            enemyMap = ObservationMapper.enemySea(pt.tiago.nightfleet.core.observation.Observation.of(match.state, 0), emptySet(), null, null),
            nowMillis = 5L,
        )
        assertEquals(LogResult.LOSS, entry.result)
        assertEquals(100, entry.finalMapPlayer.length)
        assertEquals(100, entry.finalMapEnemy.length)
        val replay = entry.replay!!.toString()
        assertTrue(replay.contains("seeds"))
        assertTrue(replay.contains("actions"))
    }

    private fun fakeSummary() = QuickBattle.Summary(
        won = false, reason = pt.tiago.nightfleet.core.engine.EndReason.TURN_LIMIT, shots = 8, hits = 3,
        precision = 38, stars = 0, enemyShipsSunk = 1, enemyShipsTotal = 5, ownShipsSunk = 0,
        longestHitStreak = 1, sunkByClass = emptyMap(),
    )
}
