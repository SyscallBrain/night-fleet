package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pt.tiago.nightfleet.core.content.CanonicalJson
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CatalogManifestDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.sha256Hex
import java.io.File

/** Name of the manifest written next to the exported files. */
const val MANIFEST_NAME = "manifest.json"

/** Compact JSON (no indentation, no line breaks): the exported files are what the app reads, never what content/ holds. */
private val compactJson = Json { prettyPrint = false }

/**
 * Validates the catalog in strict mode and copies the files the app reads into [output], with a SHA-256
 * manifest. Output is deterministic: files are written in sorted order, the JSON is compact and the manifest
 * nothing depends on the clock. The output directory is cleared first.
 *
 * Three things differ from `content/`, and only in the exported copy:
 * - JSON is minified (keys keep the canonical order of content/, no indentation, no trailing newline).
 * - Calibration certificates are left out: the app reads only proof certificates (puzzles), and
 *   `verify-certificates` replays the calibrations on content/.
 * - The `files` map of the exported `catalog.json` and the manifest hold the SHA-256 of the exported bytes, so
 *   every hash matches the file the app verifies before decoding it.
 *
 * Returns the validation problems. Nothing is written when one of them is an error.
 */
fun exportAssets(root: File, output: File, partial: Boolean = false): List<Problem> {
    val problems = validateContent(root, strict = true).withPartialCatalog(partial)
    if (problems.any { it.severity == Severity.ERROR }) return problems

    val catalog = ContentJson.json.decodeFromString<CatalogDto>(File(root, "catalog.json").readText(Charsets.UTF_8))
    val target = output.absoluteFile.normalize()
    val rootPath = root.absoluteFile.normalize()
    require(!rootPath.startsWith(target) && target != rootPath) { "refusing to clear $output: it contains the content root" }
    if (output.exists()) output.deleteRecursively()
    output.mkdirs()

    val files = sortedMapOf<String, String>()
    for (path in catalog.files.keys.filter { isAppFile(root, it) }.sorted()) {
        val bytes = minify(File(root, path).readText(Charsets.UTF_8))
        writeExported(output, path, bytes, files)
    }
    val exportedCatalog = CanonicalJson.sortKeys(ContentJson.json.encodeToJsonElement(CatalogDto.serializer(), catalog.copy(files = files)))
    writeExported(output, "catalog.json", compactJson.encodeToString(JsonElement.serializer(), exportedCatalog).toByteArray(Charsets.UTF_8), files)

    val manifest = CatalogManifestDto(
        format = CatalogManifestDto.FORMAT,
        contentVersion = catalog.contentVersion,
        engineVersion = catalog.engineVersion,
        aiVersion = catalog.aiVersion,
        files = files,
    )
    File(output, MANIFEST_NAME).writeBytes(CanonicalJson.bytes(CatalogManifestDto.serializer(), manifest))
    return problems
}

/** Whether the app reads [path]: everything except calibration certificates (only proofs are read at runtime). */
private fun isAppFile(root: File, path: String): Boolean {
    if (!path.startsWith("certificates/")) return true
    val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), File(root, path).readText(Charsets.UTF_8))
    return certificate is CertificateDto.Proof
}

/** The same JSON value in compact form, keys in the order they have in content/. */
private fun minify(text: String): ByteArray =
    compactJson.encodeToString(JsonElement.serializer(), compactJson.parseToJsonElement(text)).toByteArray(Charsets.UTF_8)

private fun writeExported(output: File, path: String, bytes: ByteArray, files: MutableMap<String, String>) {
    File(output, path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    files[path] = sha256Hex(bytes)
}
