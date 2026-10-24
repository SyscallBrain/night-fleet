package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.serializer
import pt.tiago.nightfleet.core.content.AdmiralsDto
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.EndlessPackDto
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.SeaDto
import pt.tiago.nightfleet.core.content.TerrainsDto
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import java.io.File

/** A decoded file together with its path relative to the content root. */
internal class Loaded<T>(val file: String, val value: T)

/**
 * Everything under `content/`, decoded. Files that fail to decode are reported in [loadProblems]
 * and left out, so validation can still report everything else.
 */
internal class ContentSet(
    val root: File,
    val catalog: CatalogDto?,
    /** Sea id (`s01`) -> sea. */
    val seas: Map<String, Loaded<SeaDto>>,
    val admirals: Loaded<AdmiralsDto>?,
    val terrains: Loaded<TerrainsDto>?,
    /** Mission id -> mission. */
    val missions: Map<String, Loaded<MissionDto>>,
    /** Pool id -> pool. */
    val pools: Map<String, Loaded<LayoutPoolDto>>,
    /** Puzzle id -> puzzle (the [PuzzleDefinition] of core/puzzle, stored as is). */
    val puzzles: Map<String, Loaded<PuzzleDefinition>>,
    /** Certificate id (`<missionId>-<E|M|H>`) -> certificate. */
    val certificates: Map<String, Loaded<CertificateDto>>,
    val daily: Loaded<DailyPoolDto>?,
    /** `daily/hunts.json`: the HUNT variants of the daily pool. */
    val dailyHunts: Loaded<DailyHuntsDto>?,
    /** File base name (`p06`) -> endless pack. */
    val endless: Map<String, Loaded<EndlessPackDto>>,
    val patrol: Loaded<PatrolConfigDto>?,
    /** Relative paths of every published file that exists on disk. */
    val publishedFiles: Set<String>,
    val loadProblems: List<Problem>,
)

/** Directories and files that hold published content. Everything else is ignored. */
internal val PUBLISHED_DIRECTORIES = listOf("seas/", "missions/", "layouts/", "puzzles/", "certificates/", "daily/", "endless/", "patrol/")
internal val PUBLISHED_FILES = setOf("admirals.json", "terrains.json")

private val MISSION_ID = Regex("s(\\d{2})m(\\d{3})")

/** Path of a mission file: `missions/<sea>/<id>.json`. */
internal fun missionPath(seaId: String, missionId: String): String = "missions/$seaId/$missionId.json"

/** Sea id (`s03`) of a mission id (`s03m112`), or null when the id is malformed. */
internal fun seaIdOf(missionId: String): String? = MISSION_ID.matchEntire(missionId)?.let { "s" + it.groupValues[1] }

internal fun isPublishedPath(relative: String): Boolean =
    relative in PUBLISHED_FILES || PUBLISHED_DIRECTORIES.any { relative.startsWith(it) }

internal fun relativePath(root: File, file: File): String =
    file.relativeTo(root).invariantSeparatorsPath

internal fun loadContent(root: File): ContentSet {
    val problems = mutableListOf<Problem>()

    fun <T> read(relative: String, serializer: kotlinx.serialization.KSerializer<T>): Loaded<T>? {
        val file = File(root, relative)
        if (!file.isFile) {
            problems += Problem(relative, "$", "missing file")
            return null
        }
        return try {
            Loaded(relative, ContentJson.json.decodeFromString(serializer, file.readText(Charsets.UTF_8)))
        } catch (e: SerializationException) {
            problems += Problem(relative, "$", "cannot decode: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            problems += Problem(relative, "$", "cannot decode: ${e.message}")
            null
        }
    }

    fun <T> readDirectory(prefix: String, serializer: kotlinx.serialization.KSerializer<T>): Map<String, Loaded<T>> {
        val dir = File(root, prefix)
        if (!dir.isDirectory) return emptyMap()
        val out = sortedMapOf<String, Loaded<T>>()
        dir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.forEach { file ->
            val relative = relativePath(root, file)
            val loaded = read(relative, serializer) ?: return@forEach
            out[file.nameWithoutExtension] = loaded
        }
        return out
    }

    val catalogLoaded = read("catalog.json", serializer<CatalogDto>())
    val catalog = catalogLoaded?.value

    val publishedFiles = root.walkTopDown()
        .filter { it.isFile }
        .map { relativePath(root, it) }
        .filter { isPublishedPath(it) }
        .toSortedSet()

    val seas = linkedMapOf<String, Loaded<SeaDto>>()
    val missions = linkedMapOf<String, Loaded<MissionDto>>()
    if (catalog != null) {
        for (seaId in catalog.seas) {
            read("seas/$seaId.json", serializer<SeaDto>())?.let { seas[seaId] = it }
        }
        for (missionId in catalog.missions) {
            val seaId = seaIdOf(missionId)
            if (seaId == null) {
                problems += Problem("catalog.json", "$.missions", "malformed mission id $missionId")
                continue
            }
            read(missionPath(seaId, missionId), serializer<MissionDto>())?.let { missions[missionId] = it }
        }
    }

    return ContentSet(
        root = root,
        catalog = catalog,
        seas = seas,
        admirals = read("admirals.json", serializer<AdmiralsDto>()),
        terrains = read("terrains.json", serializer<TerrainsDto>()),
        missions = missions,
        pools = readDirectory("layouts", serializer<LayoutPoolDto>()),
        puzzles = readDirectory("puzzles", serializer<PuzzleDefinition>()),
        certificates = readDirectory("certificates", serializer<CertificateDto>()),
        daily = if (File(root, "daily/pool.json").isFile) read("daily/pool.json", serializer<DailyPoolDto>()) else null,
        dailyHunts = if (File(root, DAILY_HUNTS_PATH).isFile) read(DAILY_HUNTS_PATH, serializer<DailyHuntsDto>()) else null,
        endless = readDirectory("endless", serializer<EndlessPackDto>()),
        patrol = if (File(root, "patrol/config.json").isFile) read("patrol/config.json", serializer<PatrolConfigDto>()) else null,
        publishedFiles = publishedFiles,
        loadProblems = problems,
    )
}
