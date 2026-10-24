package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.puzzle.statsDifficultyOf

class BattleStatsTest {

    private val summary = QuickBattle.Summary(
        won = false, reason = EndReason.FLEET_SUNK, shots = 30, hits = 9,
        precision = 30, stars = 0, enemyShipsSunk = 2, enemyShipsTotal = 5, ownShipsSunk = 5,
        longestHitStreak = 3, sunkByClass = mapOf("CRUISER" to 2),
    )

    @Test
    fun aBattleCountsInTheTabOfItsDifficultyWithWeaponsAndTime() {
        val once = BattleRecords.withBattle(
            Profile(), Difficulty.HARD, summary, listOf(0), width = 10,
            weaponsUsed = mapOf("TORPEDO" to 1, "SONAR" to 2), playSeconds = 95,
        )
        val twice = BattleRecords.withBattle(once, Difficulty.HARD, summary, listOf(0), width = 10, weaponsUsed = mapOf("TORPEDO" to 1), playSeconds = 5)
        val hard = twice.stats[Difficulty.HARD]!!
        assertEquals(2, hard.battles)
        assertEquals(0, hard.wins)
        assertEquals(60, hard.shots)
        assertEquals(18, hard.hits)
        assertEquals(4, hard.shipsSunkByClass["CRUISER"])
        assertEquals(mapOf("TORPEDO" to 2, "SONAR" to 2), hard.weaponsUsed)
        assertEquals(100L, hard.playTimeSeconds)
        assertEquals(2, hard.shotHeatmap[0])
        assertEquals(null, twice.stats[Difficulty.MEDIUM])
    }

    @Test
    fun theHeatmapScalesEverySeaOntoTenByTen() {
        // 12 x 12: the last square of the first row stays on the first row of the map.
        assertEquals(9, QuickBattle.heatmapIndex(11, 12))
        assertEquals(99, QuickBattle.heatmapIndex(143, 12))
        assertEquals(10, QuickBattle.heatmapIndex(24, 12))
        // 8 x 8: the far corner reaches the far part of the map.
        assertEquals(88, QuickBattle.heatmapIndex(63, 8))
        assertEquals(42, QuickBattle.heatmapIndex(42, 10))
    }

    @Test
    fun aSolvedPuzzleCountsInItsDifficulty() {
        val profile = BattleRecords.withPuzzleSolved(BattleRecords.withPuzzleSolved(Profile(), Difficulty.EASY), Difficulty.EASY)
        assertEquals(2, profile.stats[Difficulty.EASY]!!.puzzlesSolved)
        assertEquals(0, profile.stats[Difficulty.EASY]!!.battles)
    }

    @Test
    fun freePlayAndEndlessPuzzlesMapToATab() {
        assertEquals(Difficulty.HARD, BattleViewModel.statsDifficultyOf(OpponentLevel.RELENTLESS))
        assertEquals(Difficulty.EASY, BattleViewModel.statsDifficultyOf(OpponentLevel.EASY))
        assertEquals(Difficulty.MEDIUM, statsDifficultyOf(PuzzleGrade.MEDIUM))
    }
}
