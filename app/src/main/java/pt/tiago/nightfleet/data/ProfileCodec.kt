package pt.tiago.nightfleet.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Migration from schema version N to N + 1. Operates on the raw JSON so old shapes can be read. */
typealias ProfileMigration = (JsonObject) -> JsonObject

object ProfileMigrations {
    /** Empty while the first schema is current. Add `1 to { ... }` when schema 2 is introduced. */
    val steps: Map<Int, ProfileMigration> = emptyMap()
}

/**
 * Converts profiles to and from JSON. Decoding applies every migration from the stored
 * schemaVersion (missing means 0) up to [currentVersion], then clamps impossible values.
 */
class ProfileCodec(
    val currentVersion: Int = PROFILE_SCHEMA_VERSION,
    private val migrations: Map<Int, ProfileMigration> = ProfileMigrations.steps,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(profile: Profile): String =
        json.encodeToString(Profile.serializer(), profile.copy(schemaVersion = currentVersion))

    /** Returns null for unreadable text, a newer schema, or a missing migration step. */
    fun decode(text: String): Profile? = runCatching {
        fromJsonObject(json.parseToJsonElement(text).jsonObject)
    }.getOrNull()

    fun fromJsonElement(element: JsonElement): Profile? = runCatching {
        fromJsonObject(element.jsonObject)
    }.getOrNull()

    private fun fromJsonObject(root: JsonObject): Profile {
        var version = root["schemaVersion"]?.jsonPrimitive?.int ?: 0
        require(version in 0..currentVersion) { "Unsupported profile schema $version" }
        var current = root
        while (version < currentVersion) {
            val step = requireNotNull(migrations[version]) { "No migration from schema $version" }
            current = step(current)
            version++
        }
        val profile = json.decodeFromJsonElement(Profile.serializer(), current)
        return ProfileValidator.clamp(profile.copy(schemaVersion = currentVersion))
    }

    /** Shared JSON settings for the other documents of the app (logbook, saved matches, backups). */
    companion object {
        val documentJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
