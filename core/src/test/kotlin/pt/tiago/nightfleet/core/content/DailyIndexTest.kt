package pt.tiago.nightfleet.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyIndexTest {
    @Test
    fun noRepeatsInsideAnyWindowOf400Days() {
        for (kind in DailyKind.entries) for (difficulty in Difficulty.entries) {
            for (start in listOf(0L, 1L, 19_000L, 20_555L)) {
                val seen = (start until start + 400).map { DailyIndex.indexOf(it, 1, kind, difficulty) }
                assertEquals("kind $kind $difficulty start $start", 400, seen.toSet().size)
                assertTrue(seen.all { it in 0 until 400 })
            }
        }
    }

    @Test
    fun versionChangesTheOrder() {
        val v1 = (0L until 50L).map { DailyIndex.indexOf(it, 1, DailyKind.PUZZLE, Difficulty.EASY) }
        val v2 = (0L until 50L).map { DailyIndex.indexOf(it, 2, DailyKind.PUZZLE, Difficulty.EASY) }
        assertTrue(v1 != v2)
    }

    @Test
    fun sameInputsGiveSameIndex() {
        assertEquals(
            DailyIndex.indexOf(20_000L, 3, DailyKind.HUNT, Difficulty.HARD),
            DailyIndex.indexOf(20_000L, 3, DailyKind.HUNT, Difficulty.HARD),
        )
    }
}
