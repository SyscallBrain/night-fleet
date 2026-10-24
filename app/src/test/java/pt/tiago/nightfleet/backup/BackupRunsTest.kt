package pt.tiago.nightfleet.backup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.FakeSettings
import pt.tiago.nightfleet.data.LogbookStore
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.data.ProfileStore
import pt.tiago.nightfleet.data.SavedMatchStore
import pt.tiago.nightfleet.data.newTempDirectory
import pt.tiago.nightfleet.game.modes.ModeRunStore
import pt.tiago.nightfleet.game.modes.PatrolRun
import pt.tiago.nightfleet.game.modes.TournamentRun
import java.io.File

/** Patrol and tournament runs in the backup: they come back, continue, and a broken run is dropped. */
class BackupRunsTest {
    private lateinit var dirA: File
    private lateinit var dirB: File

    private class Install(dir: File) {
        val matches = SavedMatchStore(dir)
        val profile = ProfileStore(dir, matches = matches)
        val logbook = LogbookStore(dir)
        val runs = ModeRunStore(dir)
        val backup = BackupManager(profile, logbook, matches, FakeSettings(), runs = runs)
    }

    private val ladder = listOf("iris", "haze", "coral", "frost", "iron", "atoll", "wolf", "sargasso")

    @Before
    fun setUp() {
        dirA = newTempDirectory("runs-a")
        dirB = newTempDirectory("runs-b")
    }

    @After
    fun tearDown() {
        dirA.deleteRecursively()
        dirB.deleteRecursively()
    }

    private val patrol = PatrolRun(runSeed = 77L, difficulty = Difficulty.HARD, sea = 4, reserve = 31, seasCleared = 3)
    private val tournament = TournamentRun.start(Difficulty.MEDIUM, seed = 12L, chosen = ladder).afterDuel(won = true)

    /** The payload text of a backup, with [edit] applied to its JSON object (the checksum is recomputed). */
    private fun rewrite(bytes: ByteArray, edit: (JsonObject) -> JsonObject): ByteArray {
        val envelope = ProfileCodec.documentJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val payload = ProfileCodec.documentJson.parseToJsonElement(envelope.getValue("payload").jsonPrimitive.content).jsonObject
        val text = ProfileCodec.documentJson.encodeToString(JsonObject.serializer(), edit(payload))
        val rewritten = buildJsonObject {
            put("format", BackupDocument.FORMAT)
            put("version", BackupDocument.VERSION)
            put("sha256", BackupDocument.sha256Hex(text))
            put("payload", text)
        }
        return ProfileCodec.documentJson.encodeToString(JsonObject.serializer(), rewritten).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun aPatrolRunComesBackOnAnotherInstallAndCanContinue() = runBlocking {
        val a = Install(dirA)
        a.runs.savePatrol(patrol)
        val bytes = a.backup.exportBytes()
        val b = Install(dirB)
        b.runs.savePatrol(PatrolRun.start(Difficulty.EASY, runSeed = 1L))
        b.backup.applyImport(b.backup.importBytes(bytes))
        assertEquals(patrol, b.runs.patrol())
        assertEquals(patrol.matchId, b.runs.patrol()?.matchId)
    }

    @Test
    fun aTournamentRunComesBackAndTheRunsLeaveWithTheImport() = runBlocking {
        val a = Install(dirA)
        a.runs.saveTournament(tournament)
        val bytes = a.backup.exportBytes()
        val b = Install(dirB)
        b.runs.saveTournament(TournamentRun.start(Difficulty.HARD, seed = 9L, chosen = ladder))
        b.backup.applyImport(b.backup.importBytes(bytes))
        assertEquals(tournament, b.runs.tournament())
        assertEquals(1, b.runs.tournament()?.wins)
    }

    @Test
    fun aBackupWithoutRunsClearsTheRunsOfTheImportingInstall() = runBlocking {
        val a = Install(dirA)
        val bytes = a.backup.exportBytes()
        val b = Install(dirB)
        b.runs.savePatrol(patrol)
        b.runs.saveTournament(tournament)
        b.backup.applyImport(b.backup.importBytes(bytes))
        assertNull(b.runs.patrol())
        assertNull(b.runs.tournament())
    }

    @Test
    fun aBackupWrittenBeforeM12ImportsWithoutRuns() = runBlocking {
        val a = Install(dirA)
        val old = rewrite(a.backup.exportBytes()) { payload ->
            buildJsonObject { payload.forEach { (k, v) -> if (k != "patrolRun" && k != "tournamentRun") put(k, v) } }
        }
        val preview = Install(dirB).backup.importBytes(old)
        assertNull(preview.payload.patrolRun)
        assertNull(preview.payload.tournamentRun)
    }

    @Test
    fun aBrokenPatrolRunIsDroppedAndTheRestIsRestored() = runBlocking {
        val a = Install(dirA)
        a.runs.savePatrol(patrol)
        val tampered = rewrite(a.backup.exportBytes()) { payload ->
            buildJsonObject {
                payload.forEach { (k, v) -> if (k != "patrolRun") put(k, v) }
                put("patrolRun", buildJsonObject { put("runSeed", JsonPrimitive("not a number")) })
            }
        }
        val preview = Install(dirB).backup.importBytes(tampered)
        assertNull(preview.payload.patrolRun)
        assertNotNull(preview.payload.profile)
    }

    @Test
    fun aRunOutsideTheLimitsIsDroppedOnImport() = runBlocking {
        val a = Install(dirA)
        a.runs.savePatrol(patrol)
        val huge = rewrite(a.backup.exportBytes()) { payload ->
            buildJsonObject {
                payload.forEach { (k, v) -> if (k != "patrolRun") put(k, v) }
                put("patrolRun", ProfileCodec.documentJson.parseToJsonElement(
                    """{"runSeed":77,"difficulty":"HARD","sea":4,"reserve":5000,"seasCleared":3}""",
                ))
            }
        }
        assertNull(Install(dirB).backup.importBytes(huge).payload.patrolRun)
    }

    @Test
    fun aTournamentWithAnUnknownAdmiralIsDroppedOnImport() = runBlocking {
        val a = Install(dirA)
        a.runs.saveTournament(tournament)
        val bad = rewrite(a.backup.exportBytes()) { payload ->
            buildJsonObject {
                payload.forEach { (k, v) -> if (k != "tournamentRun") put(k, v) }
                put("tournamentRun", ProfileCodec.documentJson.parseToJsonElement(
                    """{"seed":12,"difficulty":"MEDIUM","ladder":["iris","haze","coral","frost","iron","atoll","wolf","nobody"],"rules":["","","","","","","",""],"round":1,"wins":1}""",
                ))
            }
        }
        assertNull(Install(dirB).backup.importBytes(bad).payload.tournamentRun)
        assertTrue(a.runs.tournament() != null)
    }

    @Test
    fun aDamagedBackupWithRunsIsStillRejectedAndNothingIsWritten() = runBlocking {
        val a = Install(dirA)
        a.runs.savePatrol(patrol)
        val bytes = a.backup.exportBytes()
        val b = Install(dirB)
        b.runs.savePatrol(PatrolRun.start(Difficulty.EASY, runSeed = 1L))
        val changed = bytes.toString(Charsets.UTF_8).replace("31", "32").toByteArray(Charsets.UTF_8)
        var rejected = false
        try {
            b.backup.importBytes(changed)
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(1L, b.runs.patrol()?.runSeed)
    }
}
