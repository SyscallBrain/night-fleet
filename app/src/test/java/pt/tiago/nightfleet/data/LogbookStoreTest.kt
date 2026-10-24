package pt.tiago.nightfleet.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogbookStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = newTempDirectory("logbook")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(i: Int, pinned: Boolean = false) = LogEntry(
        id = "e$i",
        epochMillis = i.toLong(),
        kind = MatchKind.CAMPAIGN,
        missionId = "s01m%03d".format(i % 50 + 1),
        difficulty = Difficulty.EASY,
        result = LogResult.WIN,
        stars = 2,
        shots = 30,
        hits = 17,
        finalMapPlayer = "",
        finalMapEnemy = "",
        pinned = pinned,
    )

    @Test
    fun ringBufferKeepsTheLastTwoHundredEntries() {
        var entries = emptyList<LogEntry>()
        for (i in 0 until 205) entries = LogbookRules.append(entries, entry(i))
        assertEquals(LogbookRules.CAPACITY, entries.size)
        assertEquals("e5", entries.first().id)
        assertEquals("e204", entries.last().id)
    }

    @Test
    fun pinnedEntriesAreNeverEvicted() {
        var entries = listOf(entry(0, pinned = true))
        for (i in 1 until 260) entries = LogbookRules.append(entries, entry(i))
        assertEquals(LogbookRules.CAPACITY, entries.size)
        assertEquals("e0", entries.first().id)
        assertTrue(entries.first().pinned)
        assertFalse(entries.any { it.id == "e1" })
    }

    @Test
    fun atMostTwentyEntriesCanBePinned() {
        var entries = (0 until 25).map { entry(it) }
        for (i in 0 until 20) entries = LogbookRules.withPinned(entries, "e$i", true)!!
        assertEquals(20, entries.count { it.pinned })
        assertNull(LogbookRules.withPinned(entries, "e20", true))
    }

    @Test
    fun unpinningFreesASlotAndTheEntryCanBeEvictedAgain() {
        var entries = listOf(entry(0))
        entries = LogbookRules.withPinned(entries, "e0", true)!!
        entries = LogbookRules.withPinned(entries, "e0", false)!!
        for (i in 1..LogbookRules.CAPACITY) entries = LogbookRules.append(entries, entry(i))
        assertFalse(entries.any { it.id == "e0" })
    }

    @Test
    fun clampEmptiesMapsThatDoNotMatchTheBoardAndClampsStars() {
        val broken = entry(1).copy(
            boardWidth = 3,
            boardHeight = 3,
            finalMapPlayer = "abc",
            finalMapEnemy = "xyz",
            stars = 9,
            shots = 4,
            hits = 40,
        )
        val repaired = LogbookRules.clamp(listOf(broken)).single()
        // A 3x3 board needs 9 characters per map; "abc" and "xyz" are wrong and are dropped.
        assertEquals("", repaired.finalMapPlayer)
        assertEquals("", repaired.finalMapEnemy)
        assertEquals(3, repaired.stars)
        assertEquals(4, repaired.hits)

        val correct = broken.copy(finalMapPlayer = "abcdefghi")
        assertEquals("abcdefghi", LogbookRules.clamp(listOf(correct)).single().finalMapPlayer)
    }

    @Test
    fun clampDropsPinsBeyondTheLimitAndDuplicateIds() {
        val pinnedAll = (0 until 30).map { entry(it, pinned = true) } + entry(0)
        val clamped = LogbookRules.clamp(pinnedAll)
        assertEquals(LogbookRules.MAX_PINNED, clamped.count { it.pinned })
        assertEquals(30, clamped.size)
    }

    @Test
    fun storePersistsEntriesAndPinsAndRejectsDuplicates() = runBlocking {
        val store = LogbookStore(dir)
        assertTrue(store.add(entry(1)))
        assertTrue(store.add(entry(2)))
        assertFalse(store.add(entry(2)))
        assertTrue(store.setPinned("e1", true))

        val reloaded = LogbookStore(dir).load()
        assertEquals(listOf("e1", "e2"), reloaded.map { it.id })
        assertTrue(reloaded.first().pinned)
        assertFalse(reloaded.last().pinned)
    }
}
