package pt.tiago.nightfleet.backup

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogbookRules
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.sanitized
import pt.tiago.nightfleet.data.totalStars
import pt.tiago.nightfleet.game.modes.ModeRunSanitizer
import pt.tiago.nightfleet.game.modes.PatrolRun
import pt.tiago.nightfleet.game.modes.TournamentRun
import java.security.MessageDigest

/** Everything a backup carries. */
data class BackupPayload(
    val profile: Profile,
    val logbook: List<LogEntry>,
    val savedMatches: List<SavedMatch>,
    val settings: Settings,
    /** The patrol run in progress, or null. Optional: backups written before M12 have none. */
    val patrolRun: PatrolRun? = null,
    /** The tournament in progress, or null. Optional, as [patrolRun]. */
    val tournamentRun: TournamentRun? = null,
)

class BackupIntegrityException(message: String) : IllegalArgumentException(message)

/**
 * The backup file: one JSON document
 * `{"format":"nightfleet-backup","version":1,"sha256":"<hex>","payload":"<json text>"}`.
 * The SHA-256 covers the exact payload text, so the checksum never depends on re-encoding.
 * It detects damage and truncation, not forgery: the file is not signed.
 */
object BackupDocument {
    const val FORMAT = "nightfleet-backup"
    const val VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024

    private val json = ProfileCodec.documentJson

    fun encode(payload: BackupPayload, codec: ProfileCodec): ByteArray {
        val body = buildJsonObject {
            put("profile", json.parseToJsonElement(codec.encode(payload.profile)))
            put("logbook", json.encodeToJsonElement(ListSerializer(LogEntry.serializer()), payload.logbook))
            put("savedMatches", json.encodeToJsonElement(ListSerializer(SavedMatch.serializer()), payload.savedMatches))
            put("settings", json.encodeToJsonElement(Settings.serializer(), payload.settings))
            put("patrolRun", payload.patrolRun?.let { json.encodeToJsonElement(PatrolRun.serializer(), it) } ?: JsonNull)
            put("tournamentRun", payload.tournamentRun?.let { json.encodeToJsonElement(TournamentRun.serializer(), it) } ?: JsonNull)
        }
        val text = json.encodeToString(JsonObject.serializer(), body)
        val envelope = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("sha256", sha256Hex(text))
            put("payload", text)
        }
        val bytes = json.encodeToString(JsonObject.serializer(), envelope).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Backup too large" }
        return bytes
    }

    /** Verifies format, version and checksum before reading anything inside. Throws on any failure. */
    fun decode(bytes: ByteArray, codec: ProfileCodec): BackupPayload {
        require(bytes.size <= MAX_BYTES) { "Backup too large" }
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root["format"]?.jsonPrimitive?.content == FORMAT) { "Not a Night Fleet backup" }
        val version = root["version"]?.jsonPrimitive?.int ?: 0
        require(version in 1..VERSION) { "Unsupported backup version $version" }
        val payloadText = root["payload"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing payload")
        val checksum = root["sha256"]?.jsonPrimitive?.content
        if (checksum != sha256Hex(payloadText)) throw BackupIntegrityException("Damaged backup: checksum does not match")

        val body = json.parseToJsonElement(payloadText).jsonObject
        val profile = codec.fromJsonElement(body.getValue("profile"))
            ?: throw IllegalArgumentException("Invalid profile in backup")
        val logbook = LogbookRules.clamp(
            json.decodeFromJsonElement(ListSerializer(LogEntry.serializer()), body.getValue("logbook")),
        )
        val savedMatches = json.decodeFromJsonElement(ListSerializer(SavedMatch.serializer()), body.getValue("savedMatches"))
        val settings = json.decodeFromJsonElement(Settings.serializer(), body.getValue("settings")).sanitized()
        // A run that cannot be read or fails the checks is dropped: the mode starts again, the rest is restored.
        val patrolRun = readRun(body["patrolRun"], PatrolRun.serializer())?.let(ModeRunSanitizer::patrol)
        val tournamentRun = readRun(body["tournamentRun"], TournamentRun.serializer())?.let(ModeRunSanitizer::tournament)
        return BackupPayload(profile, logbook, savedMatches, settings, patrolRun, tournamentRun)
    }

    private fun <T : Any> readRun(element: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        if (element == null || element == JsonNull) null else runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()

    fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** A decoded, verified backup waiting for the user's confirmation. Nothing is written until applied. */
data class ImportPreview(val payload: BackupPayload) {
    val totalStars: Int get() = payload.profile.totalStars()
    val logbookEntries: Int get() = payload.logbook.size
    val savedMatches: Int get() = payload.savedMatches.size
}
