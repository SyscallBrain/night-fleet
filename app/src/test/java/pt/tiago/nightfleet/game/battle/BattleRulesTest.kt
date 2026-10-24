package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.data.AdmiralSpeed
import pt.tiago.nightfleet.data.DifficultyStats
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.ui.board.CellVisual

class BattleRulesTest {

    private val unknown = List(100) { CellVisual.UNKNOWN }

    @Test
    fun knownSquaresCannotBeFiredAt() {
        val visuals = unknown.toMutableList().also { it[5] = CellVisual.WATER }
        assertEquals(TapDecision.Ignored, BattleTaps.decide(visuals, null, 5, confirmShot = false, playerTurn = true))
    }

    @Test
    fun contactAndRepairedSquaresCanStillBeFiredAt() {
        val visuals = unknown.toMutableList().also { it[5] = CellVisual.CONTACT; it[6] = CellVisual.REPAIRED }
        assertEquals(TapDecision.Fire(5), BattleTaps.decide(visuals, null, 5, confirmShot = false, playerTurn = true))
        assertEquals(TapDecision.Fire(6), BattleTaps.decide(visuals, null, 6, confirmShot = false, playerTurn = true))
    }

    @Test
    fun withoutConfirmTheFirstTapFires() {
        assertEquals(TapDecision.Fire(7), BattleTaps.decide(unknown, null, 7, confirmShot = false, playerTurn = true))
    }

    @Test
    fun withConfirmTheFirstTapSelectsAndTheSecondFires() {
        assertEquals(TapDecision.Select(7), BattleTaps.decide(unknown, null, 7, confirmShot = true, playerTurn = true))
        assertEquals(TapDecision.Fire(7), BattleTaps.decide(unknown, 7, 7, confirmShot = true, playerTurn = true))
    }

    @Test
    fun withConfirmMovingTheSelectionSelectsTheOtherSquare() {
        assertEquals(TapDecision.Select(8), BattleTaps.decide(unknown, 7, 8, confirmShot = true, playerTurn = true))
    }

    @Test
    fun noTapCountsOnTheAdmiralsTurnOrOutsideTheBoard() {
        assertEquals(TapDecision.Ignored, BattleTaps.decide(unknown, null, 3, confirmShot = false, playerTurn = false))
        assertEquals(TapDecision.Ignored, BattleTaps.decide(unknown, null, 100, confirmShot = false, playerTurn = true))
    }

    @Test
    fun admiralPaceIsFastOrBetween700And900Milliseconds() {
        assertEquals(250L, AdmiralPace.thinkMillis(AdmiralSpeed.FAST, 0.5f))
        assertEquals(700L, AdmiralPace.thinkMillis(AdmiralSpeed.NORMAL, 0f))
        assertEquals(900L, AdmiralPace.thinkMillis(AdmiralSpeed.NORMAL, 1f))
        assertEquals(800L, AdmiralPace.thinkMillis(AdmiralSpeed.NORMAL, 0.5f))
    }

    @Test
    fun theLogLineIsTheLastShotOfTheAction() {
        val miss = BattleLog.lineOf(listOf(Event.Miss(1, 0, Cell(3, 1))))!!
        assertEquals(false, miss.byPlayer)
        assertEquals(LogKind.MISS, miss.kind)
        val sunk = BattleLog.lineOf(
            listOf(
                Event.Hit(0, 1, Cell(0, 0)),
                Event.Sunk(0, 1, ShipClass.DESTROYER, listOf(Cell(0, 0), Cell(0, 1))),
            ),
        )!!
        assertTrue(sunk.byPlayer)
        assertEquals(LogKind.SUNK, sunk.kind)
        assertNull(BattleLog.lineOf(listOf(Event.TurnStarted(0, 1))))
    }

    @Test
    fun starsFollowTheShotThresholds() {
        assertEquals(0, QuickBattle.stars(won = false, shots = 20))
        assertEquals(3, QuickBattle.stars(won = true, shots = QuickBattle.THREE_STAR_MAX_SHOTS))
        assertEquals(2, QuickBattle.stars(won = true, shots = QuickBattle.THREE_STAR_MAX_SHOTS + 1))
        assertEquals(2, QuickBattle.stars(won = true, shots = QuickBattle.TWO_STAR_MAX_SHOTS))
        assertEquals(1, QuickBattle.stars(won = true, shots = QuickBattle.TWO_STAR_MAX_SHOTS + 1))
    }

    @Test
    fun nextStarIsOutOfReachAtThreeStars() {
        assertEquals(QuickBattle.TWO_STAR_MAX_SHOTS, QuickBattle.nextStarShots(1))
        assertEquals(QuickBattle.THREE_STAR_MAX_SHOTS, QuickBattle.nextStarShots(2))
        assertNull(QuickBattle.nextStarShots(3))
    }

    @Test
    fun precisionRoundsHalfUpAndHandlesNoShots() {
        assertEquals(0, QuickBattle.precision(0, 0))
        assertEquals(50, QuickBattle.precision(5, 10))
        assertEquals(33, QuickBattle.precision(1, 3))
        assertEquals(67, QuickBattle.precision(2, 3))
        assertEquals(100, QuickBattle.precision(37, 37))
    }

    @Test
    fun theAttemptSeedMovesOnWithEveryRecordedBattle() {
        val first = QuickBattle.attemptSeed(Profile())
        val second = QuickBattle.attemptSeed(
            Profile(stats = mapOf(QuickBattle.DIFFICULTY to DifficultyStats(battles = 1))),
        )
        assertTrue(first != second)
        assertEquals(0, QuickBattle.attemptIndex(Profile()))
    }

    @Test
    fun heatmapIndexKeepsTheTenByTenSide() {
        assertEquals(0, QuickBattle.heatmapIndex(0, 10))
        assertEquals(99, QuickBattle.heatmapIndex(99, 10))
    }
}
