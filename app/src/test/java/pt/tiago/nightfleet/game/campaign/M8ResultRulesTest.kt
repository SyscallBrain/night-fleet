package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.meta.PaintCatalog
import java.time.LocalDate

class M8ResultRulesTest {

    private val thresholds = StarsDto(two = 58, three = 46)
    private val today: LocalDate = LocalDate.of(2026, 10, 22)

    private fun stake(kind: MissionKind = MissionKind.DUEL, difficulty: Difficulty = Difficulty.MEDIUM) = CampaignStake(
        missionId = if (kind == MissionKind.BOSS) "s01m050" else "s01m010",
        difficulty = difficulty,
        kind = kind,
        thresholds = thresholds,
        admiralId = "iris",
    )

    private fun summary(
        won: Boolean,
        shots: Int = 40,
        reason: EndReason = EndReason.FLEET_SUNK,
        usedHint: Boolean = false,
        sunk: Int = 5,
    ) = QuickBattle.Summary(
        won = won,
        reason = reason,
        shots = shots,
        hits = 12,
        precision = 30,
        stars = 0,
        enemyShipsSunk = sunk,
        enemyShipsTotal = 5,
        ownShipsSunk = 0,
        longestHitStreak = 2,
        sunkByClass = emptyMap(),
        usedHint = usedHint,
    )

    private fun boss(profile: Profile, won: Boolean, draw: Boolean = false, shots: Int = 40, hint: Boolean = false) =
        BossProgress.withDuel(profile, stake(MissionKind.BOSS), won = won, shots = shots, draw = draw, usedHint = hint)

    // ---- convoy stars -------------------------------------------------------------------------

    @Test
    fun convoyStarsFollowTheEscortRules() {
        assertEquals(0, CampaignScoring.convoyStars(won = false, allFreightersAfloat = true, enemyFleetSunk = true, usedHint = false))
        assertEquals(1, CampaignScoring.convoyStars(won = true, allFreightersAfloat = false, enemyFleetSunk = true, usedHint = false))
        assertEquals(2, CampaignScoring.convoyStars(won = true, allFreightersAfloat = true, enemyFleetSunk = false, usedHint = false))
        assertEquals(3, CampaignScoring.convoyStars(won = true, allFreightersAfloat = true, enemyFleetSunk = true, usedHint = false))
    }

    @Test
    fun theHintCapsConvoyStarsAtTwo() {
        assertEquals(2, CampaignScoring.convoyStars(won = true, allFreightersAfloat = true, enemyFleetSunk = true, usedHint = true))
    }

    @Test
    fun convoyBattlesAreRatedByFreightersNotByShots() {
        val convoy = ConvoyOutcome(freightersAfloat = 3, freightersTotal = 3, enemyFleetSunk = false)
        // Many shots would give one star under the thresholds, but the convoy rule gives two.
        val stars = BattleResultRules.campaignStars(stake(MissionKind.CONVOY), summary(won = true, shots = 90), convoy)
        assertEquals(2, stars)
        assertEquals(
            NextStar.SinkEnemy,
            BattleResultRules.nextStar(stake(MissionKind.CONVOY), stars, won = true, convoy = convoy),
        )
    }

    @Test
    fun aConvoyWithAFreighterLostNeedsTheFreightersForTheNextStar() {
        val convoy = ConvoyOutcome(freightersAfloat = 2, freightersTotal = 3, enemyFleetSunk = true)
        val stars = BattleResultRules.campaignStars(stake(MissionKind.CONVOY), summary(won = true), convoy)
        assertEquals(1, stars)
        assertEquals(NextStar.Freighters, BattleResultRules.nextStar(stake(MissionKind.CONVOY), stars, won = true, convoy = convoy))
    }

    // ---- next star and campaign stars ---------------------------------------------------------

    @Test
    fun theNextStarOfAShotsMissionIsItsNextThreshold() {
        assertEquals(NextStar.Shots(58), BattleResultRules.nextStar(stake(), 1, won = true, convoy = null))
        assertEquals(NextStar.Shots(46), BattleResultRules.nextStar(stake(), 2, won = true, convoy = null))
        assertEquals(NextStar.None, BattleResultRules.nextStar(stake(), 3, won = true, convoy = null))
        assertEquals(NextStar.None, BattleResultRules.nextStar(stake(), 0, won = false, convoy = null))
    }

    @Test
    fun aDuelIsRatedByItsShotThresholds() {
        assertEquals(3, BattleResultRules.campaignStars(stake(), summary(won = true, shots = 40), null))
        assertEquals(2, BattleResultRules.campaignStars(stake(), summary(won = true, shots = 50), null))
        assertEquals(0, BattleResultRules.campaignStars(stake(), summary(won = false, shots = 10), null))
    }

    // ---- boss series --------------------------------------------------------------------------

    @Test
    fun aFirstDrawReplaysTheDuelWithoutDecidingIt() {
        val afterDraw = boss(Profile(), won = false, draw = true, shots = 90)
        val series = BossProgress.running(afterDraw, "s01m050", Difficulty.MEDIUM)
        assertEquals(0, series?.wins)
        assertEquals(0, series?.losses)
        assertTrue(series?.replayPending == true)
        assertTrue(series?.shotsPerDuel.orEmpty().isEmpty())
    }

    @Test
    fun aReplayThatWinsCountsAsTheDuelWon() {
        val replayed = boss(boss(Profile(), won = false, draw = true), won = true, shots = 40)
        val series = BossProgress.running(replayed, "s01m050", Difficulty.MEDIUM)
        assertEquals(1, series?.wins)
        assertEquals(0, series?.losses)
        assertFalse(series?.replayPending == true)
    }

    @Test
    fun aSecondDrawInTheSameDuelCountsAsALoss() {
        val afterSecondDraw = boss(boss(Profile(), won = false, draw = true), won = false, draw = true)
        val series = BossProgress.running(afterSecondDraw, "s01m050", Difficulty.MEDIUM)
        assertEquals(0, series?.wins)
        assertEquals(1, series?.losses)
        assertFalse(series?.replayPending == true)
    }

    @Test
    fun aBossSeriesWonTwoNilWithAHintDoesNotGetThreeStars() {
        val first = boss(Profile(), won = true, shots = 40, hint = true)
        val second = boss(first, won = true, shots = 40)
        val stored = second.variants[variantKey("s01m050", Difficulty.MEDIUM)]
        assertEquals(2, stored?.stars)
        assertTrue(stored?.usedHint == true)
    }

    @Test
    fun aBossSeriesWonTwoNilWithThreeStarDuelsIsThreeStars() {
        val first = boss(Profile(), won = true, shots = 40)
        val second = boss(first, won = true, shots = 40)
        assertEquals(3, second.variants[variantKey("s01m050", Difficulty.MEDIUM)]?.stars)
    }

    @Test
    fun theSeriesStarsOfAOneOneSeriesAreOneStar() {
        val first = boss(Profile(), won = true, shots = 40)
        val second = boss(first, won = false, shots = 40)
        val third = boss(second, won = true, shots = 40)
        assertEquals(1, third.variants[variantKey("s01m050", Difficulty.MEDIUM)]?.stars)
    }

    @Test
    fun advanceDecidesTheSeriesOnlyAtTwoDuels() {
        val one = BossProgress.advance(
            pt.tiago.nightfleet.data.BossSeries(),
            won = true,
            draw = false,
            shots = 40,
            usedHint = false,
        )
        assertFalse(BossProgress.isDecided(one))
        assertFalse(BossProgress.isDecided(BossProgress.advance(one, won = false, draw = false, shots = 50, usedHint = false)))
        val two = BossProgress.advance(one, won = true, draw = false, shots = 50, usedHint = false)
        assertTrue(BossProgress.isDecided(two))
    }

    // ---- hints and usage ----------------------------------------------------------------------

    @Test
    fun hintLimitsPerDifficultyAreThreeTwoOne() {
        assertEquals(3, CampaignScoring.hintsFor(Difficulty.EASY))
        assertEquals(2, CampaignScoring.hintsFor(Difficulty.MEDIUM))
        assertEquals(1, CampaignScoring.hintsFor(Difficulty.HARD))
    }

    @Test
    fun aHintUsedInAMissionIsRecordedInTheVariantProgress() {
        val result = CampaignProgress.withResult(
            Profile(),
            CampaignOutcome("s01m007", Difficulty.HARD, won = true, shots = 30, usedHint = true, thresholds = thresholds),
        )
        val progress = result.variants[variantKey("s01m007", Difficulty.HARD)]
        assertTrue(progress?.usedHint == true)
        assertEquals(2, progress?.stars)
    }

    // ---- result facts: achievements, rank, paint ----------------------------------------------

    @Test
    fun aFirstWinUnlocksItsAchievementOnTheCommittedProfile() {
        val event = BattleResultRules.gameEvent(MatchKind.CAMPAIGN, Difficulty.EASY, summary(won = true), null)
        val (after, unlocked) = BattleResultRules.evaluateAchievements(Profile(), event, today)
        assertTrue(unlocked.any { it.id == "first_win" })
        assertTrue(after.achievements["first_win"]?.unlockedAtEpochDay != null)
        // The same event again unlocks nothing new.
        val (_, again) = BattleResultRules.evaluateAchievements(after, event, today)
        assertFalse(again.any { it.id == "first_win" })
    }

    @Test
    fun aDrawIsRecordedAsADrawNotALoss() {
        val event = BattleResultRules.gameEvent(MatchKind.CAMPAIGN, Difficulty.EASY, summary(won = false, reason = EndReason.TURN_LIMIT), null)
        assertEquals(LogResult.DRAW, event.result)
        assertTrue(event.turnLimitDraw)
    }

    @Test
    fun aPerfectEscortIsRecordedInTheEvent() {
        val convoy = ConvoyOutcome(freightersAfloat = 3, freightersTotal = 3, enemyFleetSunk = true)
        val event = BattleResultRules.gameEvent(MatchKind.CAMPAIGN, Difficulty.HARD, summary(won = true), convoy)
        assertTrue(event.escortPerfect)
    }

    @Test
    fun aRankChangeIsReportedWhenTheStarsCrossAThreshold() {
        val before = Profile()
        // Nine missions of three stars make 27 stars: the Seaman rank starts at 25.
        val after = Profile(variants = (1..9).associate { "s01m%03d:EASY".format(it) to VariantProgress(stars = 3) })
        val (rankBefore, rankAfter) = BattleResultRules.rankChange(before, after)
        assertEquals(Rank.CABIN_BOY, rankBefore)
        assertEquals(Rank.SEAMAN, rankAfter)
        assertTrue(ResultExtras(0, NextStar.None, emptyList(), rankBefore, rankAfter, null, false, null).rankChanged)
    }

    @Test
    fun aDefeatedBossUnlocksItsPaintAsTheNewPaint() {
        val before = Profile()
        val after = before.copy(defeatedAdmirals = mapOf(Difficulty.HARD to setOf("iris")))
        val paint = BattleResultRules.newPaint(before, after)
        assertEquals(PaintCatalog.bossPaintId("iris"), paint?.id)
        assertNull(BattleResultRules.newPaint(after, after))
    }
}
