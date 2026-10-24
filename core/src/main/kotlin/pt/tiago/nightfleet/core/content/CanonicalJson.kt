package pt.tiago.nightfleet.core.content

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.security.MessageDigest

/** Reads and writes content files (`content/`). Decoding is strict: unknown keys are errors. */
object ContentJson {
    /** Discriminator of the sealed [CertificateDto] hierarchy. */
    const val CLASS_DISCRIMINATOR = "kind"

    val json: Json = Json {
        classDiscriminator = CLASS_DISCRIMINATOR
        explicitNulls = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
}

/**
 * The only writer of content files: object keys sorted by code unit, two-space
 * indentation, `\n` line endings, a trailing newline and UTF-8 without BOM. Equal values always
 * produce equal bytes, which the reproducible build relies on.
 */
object CanonicalJson {
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        encode(ContentJson.json.encodeToJsonElement(serializer, value))

    fun encode(element: JsonElement): String =
        pretty.encodeToString(JsonElement.serializer(), sortKeys(element)) + "\n"

    fun <T> bytes(serializer: KSerializer<T>, value: T): ByteArray =
        encode(serializer, value).toByteArray(Charsets.UTF_8)

    fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(element.map { sortKeys(it) })
        is JsonPrimitive -> element
    }
}

/** SHA-256 in lower-case hex. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Hash of the canonical encoding of [value]: the identity used by certificates. */
fun <T> canonicalHash(serializer: KSerializer<T>, value: T): String = sha256Hex(CanonicalJson.bytes(serializer, value))
