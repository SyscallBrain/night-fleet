package pt.tiago.nightfleet.core.mission

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.EndReason

/** BOSS series: best of three duels, draws replayed once, alternating first player. */
class BossSeriesTest {

    private val seed = 0x5EEDL

    private fun series(vararg outcomes: DuelOutcome): BossSeries =
        outcomes.fold(BossSeries(seed)) { s, outcome -> s.record(outcome) }

    @Test
    fun `a new series is in progress at duel zero with no score`() {
        val s = BossSeries(seed)
        assertEquals(SeriesStatus.IN_PROGRESS, s.status)
        assertEquals(0, s.duelIndex)
        assertEquals(1, s.attempt)
        assertEquals(BossScore(0, 0, 0), s.score)
    }

    @Test
    fun `two player wins decide the series`() {
        val s = series(DuelOutcome.PLAYER_WON, DuelOutcome.PLAYER_WON)
        assertEquals(SeriesStatus.WON, s.status)
        assertEquals(BossScore(2, 0, 0), s.score)
    }

    @Test
    fun `two admiral wins lose the series`() {
        val s = series(DuelOutcome.ADMIRAL_WON, DuelOutcome.ADMIRAL_WON)
        assertEquals(SeriesStatus.LOST, s.status)
    }

    @Test
    fun `a split series goes to a third duel`() {
        val s = series(DuelOutcome.PLAYER_WON, DuelOutcome.ADMIRAL_WON)
        assertEquals(SeriesStatus.IN_PROGRESS, s.status)
        assertEquals(2, s.duelIndex)
    }

    @Test
    fun `the third duel decides a split series`() {
        assertEquals(SeriesStatus.WON, series(DuelOutcome.PLAYER_WON, DuelOutcome.ADMIRAL_WON, DuelOutcome.PLAYER_WON).status)
        assertEquals(SeriesStatus.LOST, series(DuelOutcome.ADMIRAL_WON, DuelOutcome.PLAYER_WON, DuelOutcome.ADMIRAL_WON).status)
    }

    @Test
    fun `the starting player alternates from duel to duel`() {
        assertEquals(0, BossSeries(seed).startingPlayer)
        assertEquals(1, series(DuelOutcome.PLAYER_WON).startingPlayer)
        assertEquals(0, series(DuelOutcome.PLAYER_WON, DuelOutcome.ADMIRAL_WON).startingPlayer)
    }

    @Test
    fun `a draw replays the duel once without a verdict`() {
        val s = series(DuelOutcome.DRAW)
        assertEquals(0, s.duelIndex)
        assertEquals(2, s.attempt)
        assertEquals(SeriesStatus.IN_PROGRESS, s.status)
        assertEquals(1, s.score.draws)
    }

    @Test
    fun `a replay that is won gives the player the duel`() {
        val s = series(DuelOutcome.DRAW, DuelOutcome.PLAYER_WON)
        assertEquals(1, s.duelIndex)
        assertEquals(1, s.attempt)
        assertEquals(1, s.score.playerWins)
    }

    @Test
    fun `a second draw in the same duel counts as a loss`() {
        val s = series(DuelOutcome.DRAW, DuelOutcome.DRAW)
        assertEquals(1, s.duelIndex)
        assertEquals(1, s.score.admiralWins)
        assertEquals(0, s.score.playerWins)
    }

    @Test
    fun `a draw in the replay of a duel followed by a win for the admiral is still one loss`() {
        val s = series(DuelOutcome.DRAW, DuelOutcome.ADMIRAL_WON)
        assertEquals(1, s.score.admiralWins)
        assertEquals(1, s.duelIndex)
    }

    @Test
    fun `every duel can draw once and its replay has its own seed`() {
        val first = BossSeries(seed)
        val replay = first.record(DuelOutcome.DRAW)
        assertNotEquals(first.nextSetupSeed(), replay.nextSetupSeed())
        assertEquals(replay.nextSetupSeed(), replay.nextSetupSeed())
    }

    @Test
    fun `setup seeds differ per duel and follow the series seed`() {
        val a = series(DuelOutcome.PLAYER_WON)
        val b = series(DuelOutcome.ADMIRAL_WON)
        assertNotEquals(BossSeries(seed).nextSetupSeed(), a.nextSetupSeed())
        assertNotEquals(a.nextSetupSeed(), BossSeries(seed + 1).record(DuelOutcome.PLAYER_WON).nextSetupSeed())
        assertEquals(a.nextSetupSeed(), b.nextSetupSeed())
    }

    @Test
    fun `recording on a decided series is refused`() {
        val decided = series(DuelOutcome.PLAYER_WON, DuelOutcome.PLAYER_WON)
        assertThrows(IllegalStateException::class.java) { decided.record(DuelOutcome.ADMIRAL_WON) }
    }

    @Test
    fun `a duel result maps to the outcome of the series`() {
        assertEquals(DuelOutcome.PLAYER_WON, DuelOutcome.of(BattleResult(0, EndReason.FLEET_SUNK)))
        assertEquals(DuelOutcome.ADMIRAL_WON, DuelOutcome.of(BattleResult(1, EndReason.FLEET_SUNK)))
        assertEquals(DuelOutcome.DRAW, DuelOutcome.of(BattleResult(null, EndReason.TURN_LIMIT)))
    }

    @Test
    fun `a series round trips through JSON`() {
        val s = series(DuelOutcome.DRAW, DuelOutcome.PLAYER_WON, DuelOutcome.ADMIRAL_WON)
        val json = Json.encodeToString(BossSeries.serializer(), s)
        assertEquals(s, Json.decodeFromString(BossSeries.serializer(), json))
    }

    @Test
    fun `the mission types of the boss and the convoy alternate turns like a duel`() {
        assertEquals(true, MissionType.BOSS.alternates)
        assertEquals(true, MissionType.CONVOY.alternates)
        assertEquals(false, MissionType.HUNT.alternates)
    }
}
