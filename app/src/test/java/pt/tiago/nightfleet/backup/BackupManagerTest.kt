package pt.tiago.nightfleet.backup

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.FakeSettings
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.LogbookStore
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.ProfileStore
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.SavedMatchStore
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.ThemeChoice
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.assertThrowsType
import pt.tiago.nightfleet.data.newTempDirectory
import pt.tiago.nightfleet.data.totalStars
import pt.tiago.nightfleet.data.variantKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BackupManagerTest {
    private lateinit var dirA: File
    private lateinit var dirB: File

    /** One installation: its stores, wired as AppContainer does. */
    private class Install(dir: File, val settings: FakeSettings = FakeSettings()) {
        val matches = SavedMatchStore(dir)
        val profile = ProfileStore(dir, matches = matches)
        val logbook = LogbookStore(dir)
        val backup = BackupManager(profile, logbook, matches, settings)
    }

    @Before
    fun setUp() {
        dirA = newTempDirectory("backup-a")
        dirB = newTempDirectory("backup-b")
    }

    @After
    fun tearDown() {
        dirA.deleteRecursively()
        dirB.deleteRecursively()
    }

    private fun logEntry(id: String) = LogEntry(
        id = id,
        epochMillis = 1L,
        kind = MatchKind.CAMPAIGN,
        missionId = "s01m001",
        difficulty = Difficulty.EASY,
        result = LogResult.WIN,
        stars = 3,
        shots = 20,
        hits = 10,
    )

    @Test
    fun exportedProgressImportsAndAppliesOnAnotherInstall() = runBlocking {
        val source = Install(dirA, FakeSettings(Settings(theme = ThemeChoice.SONAR)))
        source.profile.update { it.copy(variants = mapOf(variantKey("s01m001", Difficulty.EASY) to VariantProgress(stars = 3))) }
        source.logbook.add(logEntry("l1"))
        val exported = ByteArrayOutputStream().also { source.backup.exportTo(it) }.toByteArray()

        val target = Install(dirB)
        val preview = target.backup.importFrom(ByteArrayInputStream(exported))
        assertEquals(3, preview.totalStars)
        assertEquals(1, preview.logbookEntries)
        // Nothing is written until the import is applied.
        assertTrue(target.profile.load().variants.isEmpty())

        target.backup.applyImport(preview)
        assertEquals(3, ProfileStore(dirB).load().totalStars())
        assertEquals(listOf("l1"), LogbookStore(dirB).load().map { it.id })
    }

    @Test
    fun settingsAreKeptUnlessTheUserAsksToReplaceThem() = runBlocking {
        val source = Install(dirA, FakeSettings(Settings(theme = ThemeChoice.SONAR)))
        val exported = source.backup.exportBytes()

        val kept = Install(dirB, FakeSettings(Settings(theme = ThemeChoice.HOLO)))
        kept.backup.applyImport(kept.backup.importBytes(exported))
        assertEquals(ThemeChoice.HOLO, kept.settings.current().theme)

        val replaced = Install(dirB, FakeSettings(Settings(theme = ThemeChoice.HOLO)))
        replaced.backup.applyImport(replaced.backup.importBytes(exported), importSettings = true)
        assertEquals(ThemeChoice.SONAR, replaced.settings.current().theme)
    }

    @Test
    fun importReplacesTheExistingProfileAndKeepsTheOldOneAsBeforeImport() = runBlocking {
        val source = Install(dirA)
        source.profile.update { it.copy(variants = mapOf("s01m002:EASY" to VariantProgress(stars = 2))) }
        val exported = source.backup.exportBytes()

        val target = Install(dirB)
        target.profile.update { it.copy(variants = mapOf("s09m009:HARD" to VariantProgress(stars = 1))) }
        target.backup.applyImport(target.backup.importBytes(exported))

        assertNull(ProfileStore(dirB).load().variants["s09m009:HARD"])
        assertTrue(File(dirB, "profile.before-import.json").isFile)
    }

    @Test
    fun aDamagedFileIsRejectedAndNothingIsOverwritten() = runBlocking {
        val source = Install(dirA)
        source.profile.update { it.copy(variants = mapOf("s01m001:EASY" to VariantProgress(stars = 3))) }
        val exported = source.backup.exportBytes()
        val text = String(exported, Charsets.UTF_8)
        // The payload is an escaped string inside the envelope, so the needle is escaped too.
        val needle = "\\\"stars\\\":3"
        assertTrue("needle must be present", text.contains(needle))
        val damaged = text.replaceFirst(needle, "\\\"stars\\\":2").toByteArray(Charsets.UTF_8)

        val target = Install(dirB)
        target.profile.update { it.copy(patrolBest = mapOf(Difficulty.EASY to 4)) }
        assertThrowsType<Throwable>("damaged backup") {
            kotlinx.coroutines.runBlocking { target.backup.importFrom(ByteArrayInputStream(damaged)) }
        }
        assertEquals(4, ProfileStore(dirB).load().patrolBest[Difficulty.EASY])
    }

    @Test
    fun unfinishedMatchesAreExportedOnlyWhenNotYetCommitted() = runBlocking {
        val source = Install(dirA)
        val active = SavedMatch(matchId = "live", kind = MatchKind.DUO, engineVersion = 1, actions = listOf("fire:A1"), expectedHash = 1L)
        val finished = SavedMatch(matchId = "done", kind = MatchKind.CAMPAIGN, engineVersion = 1, expectedHash = 2L)
        source.matches.save(active)
        source.matches.save(finished)
        source.profile.update { it.copy(committedMatchIds = listOf("done")) }

        val preview = source.backup.importBytes(source.backup.exportBytes())
        assertEquals(listOf("live"), preview.payload.savedMatches.map { it.matchId })
    }
}
