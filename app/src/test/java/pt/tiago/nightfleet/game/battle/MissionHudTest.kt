package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.mission.BossSeries
import pt.tiago.nightfleet.core.mission.DuelOutcome
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeriesStatus
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.ConvoyRule
import pt.tiago.nightfleet.core.rules.RuleSet

class MissionHudTest {

    /** A convoy: side 0 carries three freighters on top of the classic fleet. */
    private fun convoyMatch(): BattleMatch {
        val base = RuleSet(
            extraShips = listOf(listOf(ShipClass.FREIGHTER, ShipClass.FREIGHTER, ShipClass.FREIGHTER), emptyList()),
            convoy = ConvoyRule(surviveRounds = 10, minFreighters = 1),
        )
        val own = (RandomPlacer.place(base.boardFor(0), base.fleetFor(0), SplitMix64(11)) as PlacementResult.Success).placements
        val enemy = (RandomPlacer.place(base.boardFor(1), base.fleetFor(1), SplitMix64(12)) as PlacementResult.Success).placements
        val setup = BattleSetup.create(base, MissionType.CONVOY, listOf(own, enemy), firstPlayer = 0, attemptSeed = 99L)
        return BattleMatch((setup as SetupResult.Created).state)
    }

    @Test
    fun theConvoyHudShowsRoundsAndFreightersAfloat() {
        val hud = ConvoyHud.of(convoyMatch().state)!!
        assertEquals(0, hud.roundsSurvived)
        assertEquals(10, hud.roundsRequired)
        assertEquals(3, hud.freightersAfloat)
        assertEquals(3, hud.freightersTotal)
        assertEquals(1, hud.minFreighters)
    }

    @Test
    fun aBattleThatIsNotAConvoyHasNoConvoyHud() {
        assertNull(ConvoyHud.of(WeaponFixtures.match(WeaponFixtures.rules()).state))
    }

    @Test
    fun convoyStarsFollowThePlanRule() {
        // Survive: one star. Every freighter afloat: two. Also the enemy fleet sunk: three.
        assertEquals(1, ConvoyStars.of(BattleResult(0, EndReason.CONVOY_SURVIVED), freightersAfloat = 2, freightersTotal = 3, enemyFleetSunk = false))
        assertEquals(2, ConvoyStars.of(BattleResult(0, EndReason.CONVOY_SURVIVED), freightersAfloat = 3, freightersTotal = 3, enemyFleetSunk = false))
        assertEquals(3, ConvoyStars.of(BattleResult(0, EndReason.FLEET_SUNK), freightersAfloat = 3, freightersTotal = 3, enemyFleetSunk = true))
    }

    @Test
    fun aConvoyLostGivesNoStars() {
        assertEquals(0, ConvoyStars.of(BattleResult(1, EndReason.CONVOY_LOST), 0, 3, false))
        assertEquals(0, ConvoyStars.of(null, 3, 3, false))
    }

    @Test
    fun theBossInterstitialShowsTheScoreAndTheNextStarter() {
        val series = BossSeries(seriesSeed = 5L).record(DuelOutcome.PLAYER_WON)
        val next = BossInterstitial.of(series)
        assertEquals(2, next.duelNumber)
        assertEquals("1 – 0", next.scoreText)
        // The admiral opens the second duel of the series.
        assertEquals(false, next.playerFirst)
        assertEquals(false, next.replay)
        assertEquals(SeriesStatus.IN_PROGRESS, next.status)
    }

    @Test
    fun aDrawShowsTheReplayAndTheStarterOfTheReplay() {
        val series = BossSeries(seriesSeed = 5L).record(DuelOutcome.DRAW)
        val replay = BossInterstitial.of(series)
        assertTrue(replay.replay)
        assertEquals(1, replay.duelNumber)
        assertTrue(replay.playerFirst)
        assertEquals("0 – 0", replay.scoreText)
    }

    @Test
    fun aDecidedSeriesShowsItsStatus() {
        val won = BossSeries(seriesSeed = 5L)
            .record(DuelOutcome.PLAYER_WON)
            .record(DuelOutcome.PLAYER_WON)
        assertEquals(SeriesStatus.WON, BossInterstitial.of(won).status)
        assertEquals("2 – 0", BossInterstitial.of(won).scoreText)
    }
}
