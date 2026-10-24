package pt.tiago.nightfleet.meta

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.rank

/**
 * How an achievement is reached. Every rule is pure and reads only the summary of the event or the profile.
 *
 * - [Once]: unlocked by the event that matches the test. Nothing is counted.
 * - [Count]: each event adds an amount to a counter kept per difficulty.
 * - [Record]: read from the profile after the event (records, distinct bosses, collections, ranks).
 */
sealed interface AchievementRule {
    class Once(val test: (GameEvent) -> Boolean) : AchievementRule
    class Count(val amount: (GameEvent) -> Int) : AchievementRule
    class Record(val value: (Profile) -> Int) : AchievementRule
}

/**
 * One achievement.
 *
 * [perDifficulty]: for a [AchievementRule.Count], the target must be reached within one difficulty
 * (the largest counter) instead of across the three (the sum). Modes without a difficulty count under MEDIUM.
 * [secret]: its title and description stay hidden until it is unlocked.
 */
data class AchievementDef(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val rule: AchievementRule,
    val target: Int = 1,
    val perDifficulty: Boolean = false,
    val secret: Boolean = false,
)

/**
 * The achievements, in a fixed order. The list has 36
 * distinct achievements (the header says "45"); the difference is still open.
 */
object Achievements {
    private fun win(event: GameEvent) = event.result == LogResult.WIN

    val all: List<AchievementDef> = listOf(
        AchievementDef("first_win", R.string.ach_first_win, R.string.ach_first_win_desc,
            AchievementRule.Once { win(it) }),
        AchievementDef("first_sink", R.string.ach_first_sink, R.string.ach_first_sink_desc,
            AchievementRule.Once { it.shipsSunk >= 1 }),
        AchievementDef("torpedo_sink", R.string.ach_torpedo_sink, R.string.ach_torpedo_sink_desc,
            AchievementRule.Once { it.torpedoSinks >= 1 }),
        AchievementDef("sonar_sharp", R.string.ach_sonar_sharp, R.string.ach_sonar_sharp_desc,
            AchievementRule.Once { it.sonarHits >= 3 }),
        AchievementDef("salvo_sink", R.string.ach_salvo_sink, R.string.ach_salvo_sink_desc,
            AchievementRule.Once { it.salvoSinks >= 1 }),
        AchievementDef("cross_three", R.string.ach_cross_three, R.string.ach_cross_three_desc,
            AchievementRule.Once { it.crossHits >= 3 }),
        AchievementDef("decoy_win", R.string.ach_decoy_win, R.string.ach_decoy_win_desc,
            AchievementRule.Once { win(it) && it.decoyFooled }),
        AchievementDef("submerged_found", R.string.ach_submerged_found, R.string.ach_submerged_found_desc,
            AchievementRule.Once { it.submergedFound }),
        AchievementDef("repair_win", R.string.ach_repair_win, R.string.ach_repair_win_desc,
            AchievementRule.Once { win(it) && it.repairedAndWon }),
        AchievementDef("move_escape", R.string.ach_move_escape, R.string.ach_move_escape_desc,
            AchievementRule.Once { it.movedAndEscaped }),
        AchievementDef("mine_win", R.string.ach_mine_win, R.string.ach_mine_win_desc,
            AchievementRule.Once { win(it) && it.survivedMine }),
        AchievementDef("no_miss_five", R.string.ach_no_miss_five, R.string.ach_no_miss_five_desc,
            AchievementRule.Once { win(it) && it.missStreakMax < 5 }),
        AchievementDef("precision_70", R.string.ach_precision_70, R.string.ach_precision_70_desc,
            AchievementRule.Once { !it.isPuzzle && it.boardSize == 10 && it.shots > 0 && it.precision >= 0.7f }),
        AchievementDef("win_30_shots", R.string.ach_win_30_shots, R.string.ach_win_30_shots_desc,
            AchievementRule.Once { win(it) && it.boardSize == 10 && it.shots in 1..30 }),
        AchievementDef("escort_perfect", R.string.ach_escort_perfect, R.string.ach_escort_perfect_desc,
            AchievementRule.Once { win(it) && it.escortPerfect }),
        AchievementDef("hunt_half_budget", R.string.ach_hunt_half_budget, R.string.ach_hunt_half_budget_desc,
            AchievementRule.Once { win(it) && it.huntHalfBudget }),
        AchievementDef("bosses_12", R.string.ach_bosses_12, R.string.ach_bosses_12_desc,
            AchievementRule.Record { profile -> profile.defeatedAdmirals.values.flatten().toSet().size }, target = 12),
        AchievementDef("seas_hard", R.string.ach_seas_hard, R.string.ach_seas_hard_desc,
            AchievementRule.Record { profile -> profile.defeatedAdmirals[Difficulty.HARD]?.size ?: 0 }, target = 12),
        AchievementDef("threestar_50", R.string.ach_threestar_50, R.string.ach_threestar_50_desc,
            AchievementRule.Record { threeStarMissions(it) }, target = 50),
        AchievementDef("threestar_100", R.string.ach_threestar_100, R.string.ach_threestar_100_desc,
            AchievementRule.Record { threeStarMissions(it) }, target = 100),
        AchievementDef("threestar_300", R.string.ach_threestar_300, R.string.ach_threestar_300_desc,
            AchievementRule.Record { threeStarMissions(it) }, target = 300),
        AchievementDef("puzzles_nohint_100", R.string.ach_puzzles_nohint_100, R.string.ach_puzzles_nohint_100_desc,
            AchievementRule.Count { if (it.isPuzzle && it.result == LogResult.SOLVED && !it.hintUsed) 1 else 0 },
            target = 100),
        AchievementDef("puzzle_hard_10_nohint", R.string.ach_puzzle_hard_10_nohint, R.string.ach_puzzle_hard_10_nohint_desc,
            AchievementRule.Once {
                it.isPuzzle && it.result == LogResult.SOLVED && it.boardSize == 10 &&
                    it.difficulty == Difficulty.HARD && !it.hintUsed
            }),
        AchievementDef("endless_10", R.string.ach_endless_10, R.string.ach_endless_10_desc,
            AchievementRule.Once { it.kind == MatchKind.ENDLESS && it.endlessStreak >= 10 }),
        AchievementDef("patrol_5", R.string.ach_patrol_5, R.string.ach_patrol_5_desc,
            AchievementRule.Once { it.kind == MatchKind.PATROL && it.patrolSeas >= 5 }, target = 5, perDifficulty = true),
        AchievementDef("patrol_10", R.string.ach_patrol_10, R.string.ach_patrol_10_desc,
            AchievementRule.Once { it.kind == MatchKind.PATROL && it.patrolSeas >= 10 }, target = 10, perDifficulty = true),
        AchievementDef("tournament_win", R.string.ach_tournament_win, R.string.ach_tournament_win_desc,
            AchievementRule.Once { it.tournamentWon }, perDifficulty = true),
        AchievementDef("relentless_win", R.string.ach_relentless_win, R.string.ach_relentless_win_desc,
            AchievementRule.Once { win(it) && it.relentless }),
        AchievementDef("turn_limit_draw", R.string.ach_turn_limit_draw, R.string.ach_turn_limit_draw_desc,
            AchievementRule.Once { it.turnLimitDraw }, secret = true),
        AchievementDef("daily_7", R.string.ach_daily_7, R.string.ach_daily_7_desc,
            AchievementRule.Record { it.dailyHistory.size }, target = 7),
        AchievementDef("daily_30", R.string.ach_daily_30, R.string.ach_daily_30_desc,
            AchievementRule.Record { it.dailyHistory.size }, target = 30),
        AchievementDef("duo_10", R.string.ach_duo_10, R.string.ach_duo_10_desc,
            AchievementRule.Count { if (it.kind == MatchKind.DUO) 1 else 0 }, target = 10),
        AchievementDef("all_paints", R.string.ach_all_paints, R.string.ach_all_paints_desc,
            AchievementRule.Record { PaintCatalog.unlockedIds(it).size }, target = PaintCatalog.unlockable + 1),
        AchievementDef("rank_commander", R.string.ach_rank_commander, R.string.ach_rank_commander_desc,
            AchievementRule.Record { if (it.rank() >= Rank.COMMANDER) 1 else 0 }),
        AchievementDef("rank_admiral", R.string.ach_rank_admiral, R.string.ach_rank_admiral_desc,
            AchievementRule.Record { if (it.rank() >= Rank.ADMIRAL) 1 else 0 }),
        AchievementDef("grand_admiral", R.string.ach_grand_admiral, R.string.ach_grand_admiral_desc,
            AchievementRule.Record { if (it.rank() == Rank.GRAND_ADMIRAL) 1 else 0 }),
    )

    fun byId(id: String): AchievementDef? = all.firstOrNull { it.id == id }

    /** Distinct campaign missions won with three stars, on any difficulty. */
    private fun threeStarMissions(profile: Profile): Int = profile.variants.values.count { it.stars >= 3 }
}
