package pt.tiago.nightfleet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProfileStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = newTempDirectory("profile")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun starsProfile(stars: Int) = Profile(
        variants = mapOf(variantKey("s01m001", Difficulty.EASY) to VariantProgress(stars = stars, wins = 1)),
    )

    @Test
    fun updatePersistsAndAFreshStoreReadsItBack() = runBlocking {
        ProfileStore(dir).update { starsProfile(2) }
        val reloaded = ProfileStore(dir).load()
        assertEquals(starsProfile(2), reloaded)
    }

    @Test
    fun updateStoresTheClampedResult() = runBlocking {
        val stored = ProfileStore(dir).update { starsProfile(9) }
        assertEquals(3, stored.variants.getValue(variantKey("s01m001", Difficulty.EASY)).stars)
    }

    @Test
    fun truncatedMainProfileIsRestoredFromTheBackupAndTheUserIsNotified() = runBlocking {
        val store = ProfileStore(dir)
        store.update { starsProfile(3) }
        File(dir, "profile.json").writeText("{\"variants\": {\"s01m0")

        val fresh = ProfileStore(dir)
        assertEquals(starsProfile(3), fresh.load())
        val notice = fresh.notice.value
        requireNotNull(notice)
        assertTrue(notice.restoredFromBackup)
        assertFalse(notice.startedFresh)
        // The restored copy is written back to the main file.
        assertEquals(starsProfile(3), ProfileCodec().decode(File(dir, "profile.json").readText()))
    }

    @Test
    fun unreadableProfileStartsFreshButTheDamagedFilesAreKept() = runBlocking {
        File(dir, "profile.json").writeText("broken")
        File(dir, "profile.bak.json").writeText("also broken")

        val store = ProfileStore(dir)
        assertEquals(Profile(), store.load())
        val notice = store.notice.value
        requireNotNull(notice)
        assertTrue(notice.startedFresh)
        assertEquals(2, notice.movedAside)
        assertEquals("broken", File(dir, "profile.corrupt-1.json").readText())
    }

    @Test
    fun concurrentUpdatesAreSerialisedAndNoneIsLost() = runBlocking {
        val store = ProfileStore(dir)
        coroutineScope {
            repeat(50) {
                launch(Dispatchers.Default) {
                    store.update { p -> p.copy(patrolBest = mapOf(Difficulty.EASY to (p.patrolBest[Difficulty.EASY] ?: 0) + 1)) }
                }
            }
        }
        assertEquals(50, store.load().patrolBest[Difficulty.EASY])
        assertEquals(50, ProfileStore(dir).load().patrolBest[Difficulty.EASY])
    }

    @Test
    fun resetCampaignAlsoClearsTheCampaignSlotOnly() = runBlocking {
        val matches = SavedMatchStore(dir)
        val store = ProfileStore(dir, matches = matches)
        store.update { starsProfile(3) }
        matches.save(match(MatchKind.CAMPAIGN, "c1"))
        matches.save(match(MatchKind.FREE_PLAY, "f1"))

        val reset = store.resetCampaign()

        assertTrue(reset.variants.isEmpty())
        assertNull(matches.load(MatchKind.CAMPAIGN))
        assertEquals("f1", matches.load(MatchKind.FREE_PLAY)?.matchId)
    }

    @Test
    fun replaceAllKeepsThePreviousProfileAsBeforeImport() = runBlocking {
        val store = ProfileStore(dir)
        store.update { starsProfile(1) }
        store.replaceAll(starsProfile(2))

        assertEquals(starsProfile(2), ProfileStore(dir).load())
        val previous = File(dir, "${ProfileStore.BEFORE_IMPORT_NAME}.json")
        assertEquals(starsProfile(1), ProfileCodec().decode(previous.readText()))
    }

    private fun match(kind: MatchKind, id: String) = SavedMatch(
        matchId = id,
        kind = kind,
        engineVersion = 1,
        seeds = listOf(1L),
        actions = listOf("fire:A1"),
        expectedHash = 42L,
    )
}
