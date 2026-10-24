package pt.tiago.nightfleet.core.content

import org.junit.Assert.assertEquals
import org.junit.Test

/** The daily index works for the real pool size, which is smaller than the designed 400 in the dev content. */
class DailyIndexSizeTest {

    @Test
    fun aPoolOfTwentyUsesEveryEntryInTwentyConsecutiveDays() {
        for (start in listOf(0L, 19_000L, 20_541L)) {
            val seen = (start until start + 20).map {
                DailyIndex.indexOf(it, 1, DailyKind.PUZZLE, Difficulty.EASY, size = 20)
            }
            assertEquals(20, seen.toSet().size)
        }
    }

    @Test
    fun theDefaultSizeIsStillTheDesignedPool() {
        val withDefault = DailyIndex.indexOf(123L, 2, DailyKind.HUNT, Difficulty.HARD)
        val explicit = DailyIndex.indexOf(123L, 2, DailyKind.HUNT, Difficulty.HARD, size = DailyIndex.DAILY_POOL_SIZE)
        assertEquals(explicit, withDefault)
    }

    @Test
    fun aPoolOfOneAlwaysPicksZero() {
        assertEquals(0, DailyIndex.indexOf(99L, 1, DailyKind.HUNT, Difficulty.EASY, size = 1))
    }
}
