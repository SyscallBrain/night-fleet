package pt.tiago.nightfleet.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.HEATMAP_CELLS
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind

class HeatmapTest {

    @Test
    fun emptyGridNormalisesToZerosAndShowsTheEmptyState() {
        val empty = List(HEATMAP_CELLS) { 0 }
        assertTrue(Heatmap.isEmpty(empty))
        assertTrue(Heatmap.normalize(empty).all { it == 0f })
    }

    @Test
    fun busiestCellBecomesOneAndOthersAreProportional() {
        val counts = List(HEATMAP_CELLS) { 0 }.toMutableList().apply { this[0] = 4; this[1] = 2 }
        val normalized = Heatmap.normalize(counts)
        assertEquals(1f, normalized[0], 0.0001f)
        assertEquals(0.5f, normalized[1], 0.0001f)
        assertEquals(0f, normalized[2], 0.0001f)
        assertEquals(false, Heatmap.isEmpty(counts))
    }

    @Test
    fun shortAndNegativeInputsAreCleanedUp() {
        val cells = Heatmap.cells(listOf(-3, 5))
        assertEquals(HEATMAP_CELLS, cells.size)
        assertEquals(listOf(0, 5), cells.take(2))
        assertEquals(1f, Heatmap.normalize(listOf(-3, 5))[1], 0.0001f)
    }
}

class LogbookFilterTest {

    private fun entry(id: String, kind: MatchKind, result: LogResult, pinned: Boolean = false, at: Long) = LogEntry(
        id = id, epochMillis = at, kind = kind, result = result, pinned = pinned,
    )

    private val entries = listOf(
        entry("a", MatchKind.CAMPAIGN, LogResult.WIN, at = 1),
        entry("b", MatchKind.CAMPAIGN, LogResult.LOSS, pinned = true, at = 3),
        entry("c", MatchKind.DUO, LogResult.WIN, at = 2),
        entry("d", MatchKind.ENDLESS, LogResult.SOLVED, at = 4),
        entry("e", MatchKind.PATROL, LogResult.ABANDONED, at = 5),
    )

    @Test
    fun allShowsEverythingNewestFirst() {
        assertEquals(listOf("e", "d", "b", "c", "a"), LogbookFilter.apply(entries, LogFilter.ALL).map { it.id })
    }

    @Test
    fun campaignAndModesSplitByKind() {
        assertEquals(listOf("b", "a"), LogbookFilter.apply(entries, LogFilter.CAMPAIGN).map { it.id })
        assertEquals(listOf("e", "d", "c"), LogbookFilter.apply(entries, LogFilter.MODES).map { it.id })
    }

    @Test
    fun winsCountVictoriesAndSolvedPuzzles() {
        assertEquals(listOf("d", "c", "a"), LogbookFilter.apply(entries, LogFilter.WINS).map { it.id })
    }

    @Test
    fun pinnedKeepsOnlyPinnedEntries() {
        assertEquals(listOf("b"), LogbookFilter.apply(entries, LogFilter.PINNED).map { it.id })
    }
}

class CompactMapTest {

    @Test
    fun decodeNeedsExactlyWidthTimesHeightCharacters() {
        assertEquals(null, CompactMap.decode("oo", 2, 2))
        assertEquals(4, CompactMap.decode("ooXX", 2, 2)?.size)
        assertEquals(null, CompactMap.decode("", 0, 0))
    }

    @Test
    fun glyphsMapToMarks() {
        val marks = CompactMap.decode(".oX#SsHI-", 3, 3)!!
        assertEquals(
            listOf(
                CellMark.UNKNOWN, CellMark.WATER, CellMark.HIT,
                CellMark.SUNK, CellMark.SHIP, CellMark.SHIP,
                CellMark.HIT, CellMark.ISLAND, CellMark.WATER,
            ),
            marks,
        )
    }
}
