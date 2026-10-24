package pt.tiago.nightfleet.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.DailyDay
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.VariantProgress
import java.time.LocalDate

class AchievementEvaluatorTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    private fun evaluate(profile: Profile, event: GameEvent): AchievementEvaluator.Result =
        AchievementEvaluator.evaluate(profile, event, today)

    private fun battle(result: LogResult = LogResult.LOSS, difficulty: Difficulty? = Difficulty.MEDIUM, block: GameEventBuilder.() -> Unit = {}): GameEvent =
        GameEventBuilder(MatchKind.CAMPAIGN, result, difficulty).apply(block).build()

    private fun unlockedBy(event: GameEvent, id: String, profile: Profile = Profile()): Boolean =
        id in evaluate(profile, event).newlyUnlocked

    private fun profileWith(vararg unlocked: String): Profile {
        val map = unlocked.associateWith { id ->
            pt.tiago.nightfleet.data.AchievementProgress(unlockedAtEpochDay = 1L)
        }
        return Profile(achievements = map)
    }

    // --- Once rules: each achievement is reached by its own event.

    @Test
    fun firstWinIsUnlockedByAnyWin() {
        assertTrue(unlockedBy(battle(LogResult.WIN), "first_win"))
        assertFalse(unlockedBy(battle(LogResult.LOSS), "first_win"))
    }

    @Test
    fun firstSinkTorpedoSalvoAndSonarNeedTheirCounter() {
        assertTrue(unlockedBy(battle { shipsSunk = 1 }, "first_sink"))
        assertTrue(unlockedBy(battle { torpedoSinks = 1 }, "torpedo_sink"))
        assertTrue(unlockedBy(battle { salvoSinks = 1 }, "salvo_sink"))
        assertFalse(unlockedBy(battle { sonarHits = 2 }, "sonar_sharp"))
        assertTrue(unlockedBy(battle { sonarHits = 3 }, "sonar_sharp"))
    }

    @Test
    fun crossNeedsThreeHitsAndDecoyRepairMineNeedTheWin() {
        assertFalse(unlockedBy(battle { crossHits = 2 }, "cross_three"))
        assertTrue(unlockedBy(battle { crossHits = 3 }, "cross_three"))
        assertFalse(unlockedBy(battle(LogResult.LOSS) { decoyFooled = true }, "decoy_win"))
        assertTrue(unlockedBy(battle(LogResult.WIN) { decoyFooled = true }, "decoy_win"))
        assertFalse(unlockedBy(battle(LogResult.LOSS) { repairedAndWon = true }, "repair_win"))
        assertTrue(unlockedBy(battle(LogResult.WIN) { repairedAndWon = true }, "repair_win"))
        assertFalse(unlockedBy(battle(LogResult.LOSS) { survivedMine = true }, "mine_win"))
        assertTrue(unlockedBy(battle(LogResult.WIN) { survivedMine = true }, "mine_win"))
    }

    @Test
    fun submergedAndMoveAndEscapeDoNotNeedTheWin() {
        assertTrue(unlockedBy(battle { submergedFound = true }, "submerged_found"))
        assertTrue(unlockedBy(battle { movedAndEscaped = true }, "move_escape"))
    }

    @Test
    fun noMissFiveNeedsAWinWithFewerThanFiveMissesInARow() {
        assertTrue(unlockedBy(battle(LogResult.WIN) { missStreakMax = 4 }, "no_miss_five"))
        assertFalse(unlockedBy(battle(LogResult.WIN) { missStreakMax = 5 }, "no_miss_five"))
        assertFalse(unlockedBy(battle(LogResult.LOSS) { missStreakMax = 0 }, "no_miss_five"))
    }

    @Test
    fun precisionAndThirtyShotsOnlyCountOnTheTenByTenBoard() {
        assertTrue(unlockedBy(battle { boardSize = 10; shots = 20; hits = 14 }, "precision_70"))
        assertFalse(unlockedBy(battle { boardSize = 8; shots = 20; hits = 20 }, "precision_70"))
        assertFalse(unlockedBy(battle { boardSize = 10; shots = 20; hits = 13 }, "precision_70"))
        assertTrue(unlockedBy(battle(LogResult.WIN) { boardSize = 10; shots = 30 }, "win_30_shots"))
        assertFalse(unlockedBy(battle(LogResult.WIN) { boardSize = 10; shots = 31 }, "win_30_shots"))
        assertFalse(unlockedBy(battle(LogResult.WIN) { boardSize = 8; shots = 25 }, "win_30_shots"))
    }

    @Test
    fun escortAndHuntNeedTheWinAndTheirFlag() {
        assertTrue(unlockedBy(battle(LogResult.WIN) { escortPerfect = true }, "escort_perfect"))
        assertFalse(unlockedBy(battle(LogResult.LOSS) { escortPerfect = true }, "escort_perfect"))
        assertTrue(unlockedBy(battle(LogResult.WIN) { huntHalfBudget = true }, "hunt_half_budget"))
        assertFalse(unlockedBy(battle(LogResult.WIN), "hunt_half_budget"))
    }

    @Test
    fun puzzleHardTenByTenWithoutHint() {
        val solved = battle(LogResult.SOLVED, Difficulty.HARD) { isPuzzle = true; boardSize = 10 }
        assertTrue(unlockedBy(solved, "puzzle_hard_10_nohint"))
        assertFalse(unlockedBy(solved.copy(hintUsed = true), "puzzle_hard_10_nohint"))
        assertFalse(unlockedBy(solved.copy(difficulty = Difficulty.MEDIUM), "puzzle_hard_10_nohint"))
    }

    @Test
    fun endlessTenInARowAndPatrolAndTournamentAndRelentless() {
        assertFalse(unlockedBy(GameEventBuilder(MatchKind.ENDLESS, LogResult.SOLVED, null).apply { endlessStreak = 9 }.build(), "endless_10"))
        assertTrue(unlockedBy(GameEventBuilder(MatchKind.ENDLESS, LogResult.SOLVED, null).apply { endlessStreak = 10 }.build(), "endless_10"))
        assertTrue(unlockedBy(GameEventBuilder(MatchKind.PATROL, LogResult.LOSS, Difficulty.EASY).apply { patrolSeas = 5 }.build(), "patrol_5"))
        assertFalse(unlockedBy(GameEventBuilder(MatchKind.PATROL, LogResult.LOSS, Difficulty.EASY).apply { patrolSeas = 9 }.build(), "patrol_10"))
        assertTrue(unlockedBy(GameEventBuilder(MatchKind.TOURNAMENT, LogResult.WIN, Difficulty.HARD).apply { tournamentWon = true }.build(), "tournament_win"))
        assertTrue(unlockedBy(GameEventBuilder(MatchKind.FREE_PLAY, LogResult.WIN, null).apply { relentless = true }.build(), "relentless_win"))
        assertFalse(unlockedBy(GameEventBuilder(MatchKind.FREE_PLAY, LogResult.LOSS, null).apply { relentless = true }.build(), "relentless_win"))
    }

    @Test
    fun turnLimitDrawIsASecretAchievement() {
        val def = Achievements.byId("turn_limit_draw")!!
        assertTrue(def.secret)
        assertTrue(unlockedBy(battle(LogResult.DRAW) { turnLimitDraw = true }, "turn_limit_draw"))
        val view = AchievementEvaluator.view(def, profileWith("turn_limit_draw"))
        assertTrue(view.unlocked)
    }

    // --- Count rules: counters per difficulty, sum or per-difficulty maximum.

    @Test
    fun hintFreePuzzlesCountUpToOneHundredAndUnlockOnce() {
        var profile = Profile()
        var newly = 0
        repeat(100) { i ->
            val event = GameEventBuilder(MatchKind.CAMPAIGN, LogResult.SOLVED, Difficulty.EASY).apply { isPuzzle = true }.build()
            val result = evaluate(profile, event)
            profile = profile.copy(achievements = result.achievements)
            if ("puzzles_nohint_100" in result.newlyUnlocked) {
                newly++
                assertEquals(99, i)
            }
        }
        assertEquals(1, newly)
        assertEquals(100, AchievementEvaluator.view(Achievements.byId("puzzles_nohint_100")!!, profile).value)
    }

    @Test
    fun countsAreSummedAcrossDifficultiesUnlessPerDifficulty() {
        val summed = AchievementDef(
            "test_sum", R_STRING, R_STRING, AchievementRule.Count { 1 }, target = 3,
        )
        val perDifficulty = summed.copy(id = "test_per", perDifficulty = true)
        var profile = Profile()
        for (d in listOf(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD)) {
            val r = AchievementEvaluator.evaluate(profile, battle(difficulty = d), today, listOf(summed, perDifficulty))
            profile = profile.copy(achievements = r.achievements)
        }
        assertTrue(profile.achievements["test_sum"]?.unlockedAtEpochDay != null)
        assertNull(profile.achievements["test_per"]?.unlockedAtEpochDay)
        assertEquals(1, profile.achievements["test_per"]!!.counters[Difficulty.EASY])
    }

    @Test
    fun duelsCountUnderMediumWithoutDifficultyAndUnlockAtTen() {
        var profile = Profile()
        repeat(10) {
            val r = evaluate(profile, GameEventBuilder(MatchKind.DUO, LogResult.WIN, null).build())
            profile = profile.copy(achievements = r.achievements)
        }
        assertTrue(profile.achievements["duo_10"]?.unlockedAtEpochDay != null)
        assertEquals(10, profile.achievements["duo_10"]!!.counters[Difficulty.MEDIUM])
    }

    // --- Record rules: read from the profile, so they unlock when the profile reaches the target.

    @Test
    fun threeStarMissionsAreCountedAcrossDifficultiesWithoutDoubleCounting() {
        val variants = (1..50).associate { n -> "s01m%03d:EASY".format(n) to VariantProgress(stars = 3) }
        val profile = Profile(variants = variants)
        assertEquals(50, AchievementEvaluator.view(Achievements.byId("threestar_50")!!, profile).value)
        assertTrue(evaluate(profile, battle()).newlyUnlocked.contains("threestar_50"))
        val fewer = Profile(variants = variants.entries.drop(1).associate { it.key to it.value })
        assertFalse(evaluate(fewer, battle()).newlyUnlocked.contains("threestar_50"))
    }

    @Test
    fun twelveBossesCountDistinctAdmiralsAcrossDifficulties() {
        val ids = Admirals.all.map { it.id }
        val profile = Profile(defeatedAdmirals = mapOf(
            Difficulty.EASY to ids.take(6).toSet(),
            Difficulty.HARD to ids.drop(6).toSet() + ids.take(2),
        ))
        assertTrue(evaluate(profile, battle()).newlyUnlocked.contains("bosses_12"))
        val oneShort = profile.copy(defeatedAdmirals = mapOf(Difficulty.EASY to ids.dropLast(1).toSet()))
        assertFalse(evaluate(oneShort, battle()).newlyUnlocked.contains("bosses_12"))
    }

    @Test
    fun seasHardNeedsTwelveBossesWonOnHard() {
        val ids = Admirals.all.map { it.id }
        val easyOnly = Profile(defeatedAdmirals = mapOf(Difficulty.EASY to ids.toSet()))
        assertFalse(evaluate(easyOnly, battle()).newlyUnlocked.contains("seas_hard"))
        val hard = Profile(defeatedAdmirals = mapOf(Difficulty.HARD to ids.toSet()))
        assertTrue(evaluate(hard, battle()).newlyUnlocked.contains("seas_hard"))
    }

    @Test
    fun dailyChallengesCountDistinctDays() {
        val days = (1..7).associate { day -> "2026-10-%02d".format(day) to DailyDay() }
        val profile = Profile(dailyHistory = days)
        assertTrue(evaluate(profile, battle()).newlyUnlocked.contains("daily_7"))
        assertFalse(evaluate(profile, battle()).newlyUnlocked.contains("daily_30"))
    }

    @Test
    fun rankAchievementsFollowTheStarsOfAllDifficulties() {
        val variants = (1..2300).associate { n -> "s01m%03d:EASY".format((n - 1) % 600 + 1) + n to VariantProgress(stars = 1) }
        val profile = Profile(variants = variants)
        assertTrue(evaluate(profile, battle()).newlyUnlocked.contains("rank_commander"))
        assertFalse(evaluate(profile, battle()).newlyUnlocked.contains("rank_admiral"))
    }

    @Test
    fun allPaintsUnlockOnlyWhenEveryBossAndRankPaintIsUnlocked() {
        val bosses = Admirals.all.map { it.id }
        val stars = (1..1800).associate { n -> "x$n:EASY" to VariantProgress(stars = 3) } // 5400 stars: Admiral
        val everything = Profile(defeatedAdmirals = mapOf(Difficulty.EASY to bosses.toSet()), variants = stars)
        assertTrue(evaluate(everything, battle()).newlyUnlocked.contains("all_paints"))
        val oneBossShort = everything.copy(defeatedAdmirals = mapOf(Difficulty.EASY to bosses.dropLast(1).toSet()))
        assertFalse(evaluate(oneBossShort, battle()).newlyUnlocked.contains("all_paints"))
    }

    // --- Stored state: unlocks are permanent and each one is reported once.

    @Test
    fun alreadyUnlockedAchievementKeepsItsFirstDayAndIsNotReportedAgain() {
        val earlier = Profile(achievements = mapOf("first_win" to pt.tiago.nightfleet.data.AchievementProgress(unlockedAtEpochDay = 5L)))
        val result = evaluate(earlier, battle(LogResult.WIN))
        assertFalse("first_win" in result.newlyUnlocked)
        assertEquals(5L, result.achievements.getValue("first_win").unlockedAtEpochDay)
    }

    @Test
    fun newUnlockStoresTodayAsEpochDay() {
        val result = evaluate(Profile(), battle(LogResult.WIN))
        assertEquals(today.toEpochDay(), result.achievements.getValue("first_win").unlockedAtEpochDay)
        assertTrue("first_win" in result.newlyUnlocked)
    }

    // --- View and filters.

    @Test
    fun viewCapsProgressAtTheTargetAndFilterSplitsUnlockedFromLocked() {
        val profile = Profile(
            variants = (1..80).associate { n -> "s01m%03d:EASY".format(n) to VariantProgress(stars = 3) },
            achievements = mapOf("first_win" to pt.tiago.nightfleet.data.AchievementProgress(unlockedAtEpochDay = 1L)),
        )
        val views = AchievementEvaluator.list(profile)
        assertEquals(Achievements.all.size, views.size)
        val threeStar = AchievementEvaluator.view(Achievements.byId("threestar_100")!!, profile)
        assertEquals(80, threeStar.value)
        assertEquals(0.8f, threeStar.fraction, 0.0001f)
        val unlocked = AchievementEvaluator.list(profile, AchievementFilter.UNLOCKED)
        assertEquals(listOf("first_win"), unlocked.map { it.def.id })
        assertEquals(Achievements.all.size - 1, AchievementEvaluator.list(profile, AchievementFilter.LOCKED).size)
    }

    private companion object {
        // A resource id only needs to be an Int for the rule under test; the name is never shown.
        const val R_STRING = 0
    }
}

/** Small builder so each test names only the fields it needs. */
class GameEventBuilder(
    private val kind: MatchKind,
    private val result: LogResult,
    private val difficulty: Difficulty?,
) {
    var isPuzzle = false
    var boardSize = 10
    var shots = 0
    var hits = 0
    var hintUsed = false
    var relentless = false
    var missStreakMax = 0
    var shipsSunk = 0
    var torpedoSinks = 0
    var salvoSinks = 0
    var sonarHits = 0
    var crossHits = 0
    var decoyFooled = false
    var submergedFound = false
    var repairedAndWon = false
    var movedAndEscaped = false
    var survivedMine = false
    var escortPerfect = false
    var huntHalfBudget = false
    var endlessStreak = 0
    var patrolSeas = 0
    var tournamentWon = false
    var turnLimitDraw = false

    fun build(): GameEvent = GameEvent(
        kind = kind, result = result, difficulty = difficulty, isPuzzle = isPuzzle, boardSize = boardSize,
        shots = shots, hits = hits, hintUsed = hintUsed, relentless = relentless, missStreakMax = missStreakMax,
        shipsSunk = shipsSunk, torpedoSinks = torpedoSinks, salvoSinks = salvoSinks, sonarHits = sonarHits,
        crossHits = crossHits, decoyFooled = decoyFooled, submergedFound = submergedFound,
        repairedAndWon = repairedAndWon, movedAndEscaped = movedAndEscaped, survivedMine = survivedMine,
        escortPerfect = escortPerfect, huntHalfBudget = huntHalfBudget, endlessStreak = endlessStreak,
        patrolSeas = patrolSeas, tournamentWon = tournamentWon, turnLimitDraw = turnLimitDraw,
    )
}
