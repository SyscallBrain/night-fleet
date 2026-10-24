package pt.tiago.nightfleet.game.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.Difficulty as ContentDifficulty
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.withDailyResult
import java.time.LocalDate

/** Daily selection: by date and difficulty, stable, and no repeats inside a window. */
class DailyChallengeTest {

    /** [perKind] entries of each kind and difficulty, as the published pool has them. */
    private fun pool(version: Int = 1, perKind: Int = 20): DailyPoolDto = DailyPoolDto(
        version = version,
        entries = ContentDifficulty.entries.flatMap { level ->
            DailyKind.entries.flatMap { kind ->
                (0 until perKind).map { i ->
                    DailyEntryDto("v$version-${kind.name}-${level.name}-$i", kind, level, "ref-${kind.name}-${level.name}-$i")
                }
            }
        },
    )

    @Test
    fun theSameDateAndDifficultyAlwaysPickTheSameEntry() {
        val date = LocalDate.of(2026, 10, 22)
        val first = DailyChallenge.pick(pool(), date, DailyKind.PUZZLE, Difficulty.MEDIUM)
        val again = DailyChallenge.pick(pool(), date, DailyKind.PUZZLE, Difficulty.MEDIUM)
        assertEquals(first, again)
        assertTrue(first != null)
    }

    @Test
    fun aPickStaysInsideItsKindAndDifficulty() {
        val date = LocalDate.of(2026, 10, 22)
        Difficulty.entries.forEach { level ->
            DailyKind.entries.forEach { kind ->
                val entry = DailyChallenge.pick(pool(), date, kind, level)
                assertEquals(kind, entry?.kind)
                assertEquals(level.name, entry?.difficulty?.name)
            }
        }
    }

    @Test
    fun theDifficultiesDoNotShareOneDayPick() {
        val date = LocalDate.of(2026, 10, 22)
        val easy = DailyChallenge.pick(pool(), date, DailyKind.HUNT, Difficulty.EASY)
        val hard = DailyChallenge.pick(pool(), date, DailyKind.HUNT, Difficulty.HARD)
        assertNotEquals(easy?.ref, hard?.ref)
    }

    @Test
    fun noEntryRepeatsInsideAWindowOfPoolSizeDays() {
        val start = LocalDate.of(2026, 3, 1)
        val picks = (0 until 20).map { day ->
            DailyChallenge.pick(pool(), start.plusDays(day.toLong()), DailyKind.PUZZLE, Difficulty.EASY)!!.id
        }
        assertEquals(20, picks.toSet().size)
    }

    @Test
    fun aNewPoolVersionShufflesTheDays() {
        val date = LocalDate.of(2026, 10, 22)
        val days = (0 until 20).map { date.plusDays(it.toLong()) }
        val v1 = days.map { DailyChallenge.pick(pool(version = 1), it, DailyKind.HUNT, Difficulty.MEDIUM)!!.id }
        val v2 = days.map { DailyChallenge.pick(pool(version = 2), it, DailyKind.HUNT, Difficulty.MEDIUM)!!.id }
        assertNotEquals(v1, v2)
    }

    @Test
    fun aMissingKindOrDifficultyHasNoPick() {
        val empty = DailyPoolDto(1, emptyList())
        assertNull(DailyChallenge.pick(empty, LocalDate.of(2026, 10, 22), DailyKind.PUZZLE, Difficulty.EASY))
    }

    @Test
    fun theMatchIdNamesTheDayAndTheKind() {
        val id = DailyChallenge.matchId(LocalDate.of(2026, 10, 22), Difficulty.HARD, ChallengeKind.HUNT)
        assertEquals("daily-2026-10-22-HARD-HUNT", id)
    }

    @Test
    fun theStripHasTheLastFourteenDaysOldestFirst() {
        val today = LocalDate.of(2026, 10, 22)
        val strip = DailyChallenge.strip(Profile(), today, Difficulty.MEDIUM)
        assertEquals(14, strip.size)
        assertEquals(today.minusDays(13), strip.first().date)
        assertEquals(today, strip.last().date)
    }
}
