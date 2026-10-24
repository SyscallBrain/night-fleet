package pt.tiago.nightfleet.game.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.game.battle.AdmiralOpponent
import pt.tiago.nightfleet.game.battle.BattleFixtures
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleRecords
import pt.tiago.nightfleet.game.battle.ParityBrain
import pt.tiago.nightfleet.game.freeplay.FreePlayFixtures
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.core.random.SplitMix64

/** The replay of a finished battle: stepping through it gives the hash of every step of the original. */
class MatchReplayTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Plays a quick battle (ParityBrain on side 0, Coral on side 1) and records the hash after every step. */
    private class Played(val payload: JsonObject, val hashes: List<Long>, val match: BattleMatch)

    private fun playQuick(seed: Long): Played {
        val placements = BattleFixtures.randomPlacements(seed)
        var match = BattleMatch((BattleMatch.create(placements, seed) as SetupResult.Created).state)
        val coral = AdmiralOpponent.forLevel("coral", OpponentLevel.MEDIUM, seed)
        val hashes = arrayListOf(StateHasher.hash(match.state))
        var steps = 0
        while (!match.finished && steps++ < 600) {
            val action = if (match.state.current == BattleMatch.PLAYER) {
                ParityBrain.decide(pt.tiago.nightfleet.core.observation.Observation.of(match.state, 0), SplitMix64(steps.toLong()))
            } else {
                coral.decide(BattleMatch.admiralObservation(match), BattleMatch.admiralRng(seed, match.state.turn))
            }
            val step = match.apply(action)
            assertTrue("the original battle accepts $action", step.accepted)
            match = step.match
            hashes += StateHasher.hash(match.state)
        }
        return Played(BattleRecords.replayPayload(seed, placements, match.actions, null), hashes, match)
    }

    @Test
    fun steppingThroughAQuickBattleGivesTheHashOfEveryStepOfTheOriginal() {
        val played = playQuick(31L)
        val replay = MatchReplay.fromPayload(played.payload)
        assertNotNull("the payload rebuilds", replay)
        replay!!
        assertEquals(played.match.actions.size, replay.steps)
        for (t in 0..replay.steps) {
            assertEquals("hash at step $t", played.hashes[t], replay.hashAt(t))
        }
    }

    @Test
    fun previousIsTheStateOfTheStepBeforeAndTheEndIsTheResult() {
        val played = playQuick(32L)
        val replay = MatchReplay.fromPayload(played.payload)!!
        for (t in 1..replay.steps) {
            assertEquals(played.hashes[t - 1], StateHasher.hash(replay.stateAt(t - 1)))
        }
        assertEquals(played.match.state.result, replay.last.result)
        assertEquals(played.hashes.last(), StateHasher.hash(replay.last))
    }

    @Test
    fun aPayloadSurvivesTheJsonOfTheLogbook() {
        val played = playQuick(33L)
        val text = json.encodeToString(JsonObject.serializer(), played.payload)
        val back = json.decodeFromString(JsonObject.serializer(), text)
        val replay = MatchReplay.fromPayload(back)
        assertNotNull(replay)
        assertEquals(played.hashes.last(), replay!!.hashAt(replay.steps))
    }

    @Test
    fun aFreePlayBattleReplaysFromItsSetupWithEveryVariant() {
        val config = FreePlayFixtures.allVariants(OpponentLevel.HARD, FreePlayAdmirals.DEFAULT_ID)
        val setup = FreePlayFixtures.setupFor(config, 77L)
        val brain = AdmiralOpponent.forLevel(setup.opponentId!!, OpponentLevel.HARD, setup.attemptSeed)
        var match = FreePlayFixtures.startOf(setup)
        val hashes = arrayListOf(StateHasher.hash(match.state))
        var steps = 0
        while (!match.finished && steps++ < 250) {
            val action = brain.decide(pt.tiago.nightfleet.core.observation.Observation.of(match.state, match.state.current), BattleMatch.admiralRng(setup.attemptSeed, match.state.turn))
            val step = match.apply(action)
            assertTrue("accepted $action", step.accepted)
            match = step.match
            hashes += StateHasher.hash(match.state)
        }
        val payload = BattleRecords.replayPayload(setup.attemptSeed, setup.placements[0], match.actions, setup)
        val replay = MatchReplay.fromPayload(payload)
        assertNotNull(replay)
        for (t in 0..replay!!.steps) assertEquals(hashes[t], replay.hashAt(t))
    }

    @Test
    fun aDamagedPayloadDoesNotReplay() {
        val played = playQuick(34L)
        val broken = JsonObject(played.payload + ("actions" to kotlinx.serialization.json.JsonArray(emptyList())) + ("seeds" to kotlinx.serialization.json.JsonArray(emptyList())))
        assertNull(MatchReplay.fromPayload(broken))
    }

    @Test
    fun theReplayOfAnEndedBattleStaysEnded() {
        val played = playQuick(35L)
        val replay = MatchReplay.fromPayload(played.payload)!!
        val afterEnd = replay.last.result
        assertEquals(played.match.state.result, afterEnd)
        assertTrue(replay.steps > 0)
        val extra = Action.Timeout
        assertTrue(!BattleMatch(replay.last).apply(extra).accepted)
    }
}
