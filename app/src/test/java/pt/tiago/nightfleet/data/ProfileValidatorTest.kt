package pt.tiago.nightfleet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileValidatorTest {
    private val key = "s01m001:EASY"

    @Test
    fun starsAreClampedToZeroThroughThree() {
        val clamped = ProfileValidator.clamp(Profile(variants = mapOf(key to VariantProgress(stars = 7))))
        assertEquals(3, clamped.variants.getValue(key).stars)
        val negative = ProfileValidator.clamp(Profile(variants = mapOf(key to VariantProgress(stars = -2))))
        assertEquals(0, negative.variants.getValue(key).stars)
    }

    @Test
    fun negativeCountersBecomeZero() {
        val raw = VariantProgress(stars = 1, bestShots = -3, wins = -1, losses = -4, attemptIndex = -5)
        val variant = ProfileValidator.clamp(Profile(variants = mapOf(key to raw))).variants.getValue(key)
        assertEquals(0, variant.bestShots)
        assertEquals(0, variant.wins)
        assertEquals(0, variant.losses)
        assertEquals(0L, variant.attemptIndex)
    }

    @Test
    fun heatmapIsAlwaysOneHundredCells() {
        val short = ProfileValidator.clamp(
            Profile(stats = mapOf(Difficulty.EASY to DifficultyStats(shotHeatmap = listOf(4, -1, 2)))),
        ).stats.getValue(Difficulty.EASY).shotHeatmap
        assertEquals(HEATMAP_CELLS, short.size)
        assertEquals(listOf(4, 0, 2), short.take(3))
        assertEquals(0, short[99])

        val long = ProfileValidator.clamp(
            Profile(stats = mapOf(Difficulty.EASY to DifficultyStats(shotHeatmap = List(150) { 1 }))),
        ).stats.getValue(Difficulty.EASY).shotHeatmap
        assertEquals(HEATMAP_CELLS, long.size)
    }

    @Test
    fun formationsKeepOnlyTheFiveNewestPerKey() {
        val formations = (0 until 7).map { Formation(listOf(Placement("f$it", 0, 0, 0))) }
        val clamped = ProfileValidator.clamp(Profile(formations = mapOf("10x10:classic:h" to formations)))
        val kept = clamped.formations.getValue("10x10:classic:h")
        assertEquals(MAX_FORMATIONS_PER_KEY, kept.size)
        assertEquals("f2", kept.first().placements.single().shipClass)
        assertEquals("f6", kept.last().placements.single().shipClass)
    }

    @Test
    fun unknownSelectedPaintFallsBackToDefault() {
        val unknown = ProfileValidator.clamp(Profile(selectedPaint = "forged"))
        assertEquals(DEFAULT_PAINT, unknown.selectedPaint)
        val owned = ProfileValidator.clamp(Profile(selectedPaint = "gold", unlockedPaints = setOf("gold")))
        assertEquals("gold", owned.selectedPaint)
    }

    @Test
    fun seasAreClampedAndEveryDifficultyIsPresent() {
        val clamped = ProfileValidator.clamp(Profile(unlockedSeas = mapOf(Difficulty.EASY to 99, Difficulty.HARD to 0)))
        assertEquals(SEAS_TOTAL, clamped.unlockedSeas.getValue(Difficulty.EASY))
        assertEquals(1, clamped.unlockedSeas.getValue(Difficulty.HARD))
        assertEquals(1, clamped.unlockedSeas.getValue(Difficulty.MEDIUM))
    }

    @Test
    fun dailyKeysThatAreNotRealDatesAreDropped() {
        val history = mapOf(
            "2026-03-14" to DailyDay(),
            "2026-02-30" to DailyDay(),
            "yesterday" to DailyDay(),
        )
        val clamped = ProfileValidator.clamp(Profile(dailyHistory = history))
        assertEquals(setOf("2026-03-14"), clamped.dailyHistory.keys)
    }

    @Test
    fun statsCannotHaveMoreWinsOrHitsThanTheyHadBattlesOrShots() {
        val stats = DifficultyStats(battles = 2, wins = 5, shots = 10, hits = 14)
        val clamped = ProfileValidator.clamp(Profile(stats = mapOf(Difficulty.MEDIUM to stats))).stats.getValue(Difficulty.MEDIUM)
        assertEquals(2, clamped.wins)
        assertEquals(10, clamped.hits)
    }

    @Test
    fun committedHistoryAndDuoHistoryAreBounded() {
        val ids = (0 until 100).map { "match$it" }
        val duos = (0 until 30).map { DuoSession("a$it", "b$it") }
        val clamped = ProfileValidator.clamp(Profile(committedMatchIds = ids, duoSessions = duos))
        assertEquals(COMMITTED_MATCH_HISTORY, clamped.committedMatchIds.size)
        assertEquals("match99", clamped.committedMatchIds.last())
        assertEquals(DUO_HISTORY, clamped.duoSessions.size)
    }
}
