package pt.tiago.nightfleet.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class SavedMatchAndCommitTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = newTempDirectory("commit")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun match(kind: MatchKind, id: String, actions: List<String> = listOf("fire:A1", "fire:B2")) = SavedMatch(
        matchId = id,
        kind = kind,
        engineVersion = 7,
        missionId = "s01m001",
        difficulty = Difficulty.HARD,
        seeds = listOf(11L, 22L),
        actions = actions,
        expectedHash = -1234567890123L,
        savedAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun savedMatchRoundTripsItsOpaquePayload() = runBlocking {
        val matches = SavedMatchStore(dir)
        val original = match(MatchKind.CAMPAIGN, "m1")
        matches.save(original)
        assertEquals(original, SavedMatchStore(dir).load(MatchKind.CAMPAIGN))
    }

    @Test
    fun eachKindHasItsOwnSlot() = runBlocking {
        val matches = SavedMatchStore(dir)
        matches.save(match(MatchKind.CAMPAIGN, "campaign"))
        matches.save(match(MatchKind.DUO, "duo"))
        assertEquals("campaign", matches.load(MatchKind.CAMPAIGN)?.matchId)
        assertEquals("duo", matches.load(MatchKind.DUO)?.matchId)
        assertNull(matches.load(MatchKind.DAILY))
    }

    @Test
    fun savingAgainReplacesTheSlotAfterEachTurn() = runBlocking {
        val matches = SavedMatchStore(dir)
        matches.save(match(MatchKind.CAMPAIGN, "m1", listOf("fire:A1")))
        matches.save(match(MatchKind.CAMPAIGN, "m1", listOf("fire:A1", "fire:C3")))
        assertEquals(2, matches.load(MatchKind.CAMPAIGN)?.actions?.size)
    }

    @Test
    fun clearEmptiesOnlyThatSlot() = runBlocking {
        val matches = SavedMatchStore(dir)
        matches.save(match(MatchKind.CAMPAIGN, "a"))
        matches.save(match(MatchKind.PATROL, "b"))
        matches.clear(MatchKind.CAMPAIGN)
        assertNull(matches.load(MatchKind.CAMPAIGN))
        assertEquals("b", matches.load(MatchKind.PATROL)?.matchId)
    }

    @Test
    fun aMatchAlreadyCommittedToTheProfileIsNeverResumed() = runBlocking {
        val matches = SavedMatchStore(dir)
        matches.save(match(MatchKind.CAMPAIGN, "done"))
        val profile = Profile(committedMatchIds = listOf("done"))

        assertNull(matches.activeMatch(MatchKind.CAMPAIGN, profile))
        assertNull(matches.load(MatchKind.CAMPAIGN))
    }

    @Test
    fun dropCommittedKeepsUnfinishedMatches() = runBlocking {
        val matches = SavedMatchStore(dir)
        matches.save(match(MatchKind.CAMPAIGN, "done"))
        matches.save(match(MatchKind.DUO, "still-playing"))

        matches.dropCommitted(Profile(committedMatchIds = listOf("done")))

        assertNull(matches.load(MatchKind.CAMPAIGN))
        assertEquals("still-playing", matches.load(MatchKind.DUO)?.matchId)
    }

    @Test
    fun commitResultAppliesTheResultAndClearsTheSlot() = runBlocking {
        val matches = SavedMatchStore(dir)
        val store = ProfileStore(dir, matches = matches)
        matches.save(match(MatchKind.CAMPAIGN, "m1"))

        val profile = store.commitResult("m1", MatchKind.CAMPAIGN) { p ->
            p.copy(patrolBest = mapOf(Difficulty.HARD to 2))
        }

        assertEquals(2, profile.patrolBest[Difficulty.HARD])
        assertEquals(listOf("m1"), ProfileStore(dir).load().committedMatchIds)
        assertNull(matches.load(MatchKind.CAMPAIGN))
    }

    @Test
    fun committingTheSameMatchTwiceNeverCountsItTwice() = runBlocking {
        val matches = SavedMatchStore(dir)
        val store = ProfileStore(dir, matches = matches)
        val increment: (Profile) -> Profile = { p ->
            p.copy(patrolBest = mapOf(Difficulty.EASY to (p.patrolBest[Difficulty.EASY] ?: 0) + 1))
        }

        store.commitResult("m1", null, increment)
        val again = store.commitResult("m1", null, increment)

        assertEquals(1, again.patrolBest[Difficulty.EASY])
    }

    @Test
    fun crashBetweenProfileWriteAndSlotClearLeavesAConsistentState() = runBlocking {
        // The profile was written with the match id, but the process died before the slot was cleared.
        val matches = SavedMatchStore(dir)
        val store = ProfileStore(dir, matches = matches)
        matches.save(match(MatchKind.CAMPAIGN, "m1"))
        store.update { it.copy(committedMatchIds = listOf("m1")) }

        // On restart the match is treated as finished, and the stale slot is removed.
        val restarted = SavedMatchStore(dir)
        val profile = ProfileStore(dir).load()
        assertNull(restarted.activeMatch(MatchKind.CAMPAIGN, profile))
        assertNull(restarted.load(MatchKind.CAMPAIGN))
    }
}
