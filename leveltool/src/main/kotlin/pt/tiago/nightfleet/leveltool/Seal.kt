package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.PatrolDefaults
import pt.tiago.nightfleet.core.content.sha256Hex
import java.io.File

/**
 * `seal`: recomputes the SHA-256 of every published file and rewrites `catalog.json` with them
 *. Makes hand-made or generated content publishable. Returns the number of files sealed.
 */
internal fun sealContent(root: File): Int {
    val catalogFile = File(root, "catalog.json")
    val catalog = ContentJson.json.decodeFromString(CatalogDto.serializer(), catalogFile.readText(Charsets.UTF_8))
    val files: Map<String, String> = root.walkTopDown()
        .filter { it.isFile }
        .map { relativePath(root, it) }
        .filter { isPublishedPath(it) }
        .associateWith { sha256Hex(File(root, it).readBytes()) }
        .toSortedMap()
    writeCanonicalFile(root, "catalog.json", CatalogDto.serializer(), catalog.copy(files = files))
    return files.size
}

/** `patrol/config.json`: the built-in config of the core, so the app and the tool always agree. */
internal fun patrolConfig(): PatrolConfigDto = PatrolDefaults.config()
