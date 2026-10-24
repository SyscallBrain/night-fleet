package pt.tiago.nightfleet.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.DifficultyStats
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.ThemeChoice
import pt.tiago.nightfleet.data.VariantProgress
import pt.tiago.nightfleet.data.assertThrowsType
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.data.withDailyResult
import java.time.LocalDate

class BackupDocumentTest {
    private val codec = ProfileCodec()

    private fun payload() = BackupPayload(
        profile = Profile(
            variants = mapOf(variantKey("s01m001", Difficulty.EASY) to VariantProgress(stars = 3, bestShots = 20, wins = 1)),
            stats = mapOf(
                Difficulty.EASY to DifficultyStats(
                    battles = 1,
                    wins = 1,
                    shots = 20,
                    hits = 9,
                    shipsSunkByClass = mapOf("Destroyer" to 1),
                    shotHeatmap = List(100) { it % 4 },
                ),
            ),
        ).withDailyResult(LocalDate.of(2026, 10, 1), Difficulty.HARD, ChallengeKind.PUZZLE, 2),
        logbook = listOf(
            LogEntry(
                id = "l1",
                epochMillis = 5L,
                kind = MatchKind.CAMPAIGN,
                missionId = "s01m001",
                difficulty = Difficulty.EASY,
                result = LogResult.WIN,
                stars = 3,
                shots = 20,
                hits = 9,
            ),
        ),
        savedMatches = listOf(
            SavedMatch(matchId = "m", kind = MatchKind.DUO, engineVersion = 1, seeds = listOf(3L), actions = listOf("fire:A1"), expectedHash = 7L),
        ),
        settings = Settings(theme = ThemeChoice.SONAR, sfxVolume = 0.3f),
    )

    private val json = Json

    private fun editEnvelope(bytes: ByteArray, key: String, value: JsonElement): ByteArray {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + (key to value))).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun encodeThenDecodeReturnsTheSamePayload() {
        val original = payload()
        val decoded = BackupDocument.decode(BackupDocument.encode(original, codec), codec)
        assertEquals(original, decoded)
    }

    @Test
    fun changedPayloadFailsTheIntegrityCheck() {
        val bytes = BackupDocument.encode(payload(), codec)
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val text = root.getValue("payload").jsonPrimitive.content
        assertEquals(true, text.contains("0.3"))
        // Same envelope and checksum, but a different sound volume inside the payload.
        val tampered = editEnvelope(bytes, "payload", JsonPrimitive(text.replace("0.3", "0.9")))
        assertThrowsType<BackupIntegrityException>("tampered payload") {
            BackupDocument.decode(tampered, codec)
        }
    }

    @Test
    fun truncatedFileIsRejected() {
        val bytes = BackupDocument.encode(payload(), codec)
        assertThrowsType<Throwable>("truncated") {
            BackupDocument.decode(bytes.copyOf(bytes.size / 2), codec)
        }
    }

    @Test
    fun anotherAppsFileIsRejectedBeforeAnythingIsRead() {
        val bytes = editEnvelope(BackupDocument.encode(payload(), codec), "format", JsonPrimitive("neonline-backup"))
        assertThrowsType<IllegalArgumentException>("foreign format") {
            BackupDocument.decode(bytes, codec)
        }
    }

    @Test
    fun backupFromANewerVersionIsRejected() {
        val bytes = editEnvelope(BackupDocument.encode(payload(), codec), "version", JsonPrimitive(2))
        assertThrowsType<IllegalArgumentException>("newer version") {
            BackupDocument.decode(bytes, codec)
        }
    }

    @Test
    fun missingChecksumIsAnIntegrityFailure() {
        val bytes = BackupDocument.encode(payload(), codec)
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val withoutChecksum = JsonObject(root - "sha256")
        assertThrowsType<BackupIntegrityException>("no checksum") {
            BackupDocument.decode(json.encodeToString(JsonObject.serializer(), withoutChecksum).toByteArray(), codec)
        }
    }

    @Test
    fun exportedBackupIsWithinTheSizeLimit() {
        assertEquals(true, BackupDocument.encode(payload(), codec).size < BackupDocument.MAX_BYTES)
    }

    @Test
    fun importedSettingsAreSanitised() {
        val wild = payload().copy(settings = Settings(sfxVolume = 5f, fleetPaint = ""))
        val decoded = BackupDocument.decode(BackupDocument.encode(wild, codec), codec)
        assertEquals(1f, decoded.settings.sfxVolume, 0f)
        assertEquals("default", decoded.settings.fleetPaint)
    }

    @Test
    fun dailyHistoryTravelsInsideTheBackup() {
        val decoded = BackupDocument.decode(BackupDocument.encode(payload(), codec), codec)
        assertEquals(1, decoded.profile.dailyHistory.size)
        val best = decoded.profile.dailyHistory.getValue("2026-10-01").best
        assertEquals(2, best.getValue(Difficulty.HARD).getValue(ChallengeKind.PUZZLE))
    }
}
