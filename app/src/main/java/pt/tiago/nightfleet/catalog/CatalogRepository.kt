package pt.tiago.nightfleet.catalog

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CatalogManifestDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.PatrolDefaults
import pt.tiago.nightfleet.core.content.SeaDto
import pt.tiago.nightfleet.core.content.TerrainsDto
import pt.tiago.nightfleet.core.content.sha256Hex
import java.io.FileNotFoundException

/** Either a decoded value or the reason it could not be read, shown to the player as an error state. */
sealed interface CatalogResult<out T> {
    data class Ok<T>(val value: T) : CatalogResult<T>

    /** Fail closed: the content that does not verify is never decoded or used. */
    data class Failed(val reason: String) : CatalogResult<Nothing>
}

/**
 * Read-only access to the campaign content that the build exports into the APK assets
 * (`manifest.json`, `catalog.json`, `missions/`, `layouts/`, `certificates/` with the puzzle proofs only).
 * The exported files are compact JSON, and their hashes are the ones of the exported bytes (see `exportAssets`).
 *
 * Every file is checked against its SHA-256 in `manifest.json` the first time it is read; the
 * result (value or failure) is kept, so each file is read and hashed once. [readAsset] returns the
 * bytes of an asset path, or null when there is no such asset, which keeps this class free of
 * Android types and testable on the JVM.
 */
class CatalogRepository(private val readAsset: (String) -> ByteArray?) {
    private val lock = Any()
    private var manifestResult: CatalogResult<CatalogManifestDto>? = null
    private var catalogResult: CatalogResult<CatalogDto>? = null
    private var terrainsResult: CatalogResult<TerrainsDto>? = null
    private var patrolResult: CatalogResult<PatrolConfigDto>? = null
    private val seas = HashMap<Int, CatalogResult<SeaDto>>()
    private val missions = HashMap<String, CatalogResult<MissionDto>>()
    private val missionsBySea = HashMap<Int, CatalogResult<List<MissionDto>>>()
    private val pools = HashMap<String, CatalogResult<LayoutPoolDto>>()
    private val certificates = HashMap<String, CatalogResult<CertificateDto>>()
    private val puzzles = HashMap<String, CatalogResult<PuzzleDefinition>>()
    private var dailyPoolResult: CatalogResult<DailyPoolDto>? = null
    private var dailyHuntsResult: CatalogResult<DailyHuntsDto>? = null

    /** The catalog itself: versions, seas and the ordered mission ids. */
    fun catalog(): CatalogResult<CatalogDto> = synchronized(lock) {
        catalogResult ?: load(CATALOG_PATH, CatalogDto.serializer()).also { catalogResult = it }
    }

    /** Missions of sea [sea] (1..12) in campaign order. Decoded when first asked for. */
    fun missionsForSea(sea: Int): CatalogResult<List<MissionDto>> = synchronized(lock) {
        missionsBySea.getOrPut(sea) {
            val catalog = catalog().let { (it as? CatalogResult.Ok)?.value ?: return@getOrPut failedCatalog() }
            val seaId = seaId(sea)
            val ids = catalog.missions.filter { seaIdOf(it) == seaId }
            val out = ArrayList<MissionDto>(ids.size)
            for (id in ids) {
                when (val result = missionLocked(id)) {
                    is CatalogResult.Ok -> out += result.value
                    is CatalogResult.Failed -> return@getOrPut result
                }
            }
            CatalogResult.Ok(out)
        }
    }

    /** One mission by id, such as `s03m112`. */
    fun mission(id: String): CatalogResult<MissionDto> = synchronized(lock) { missionLocked(id) }

    /** Sea metadata of sea [sea] (1..12): admiral, weather, music and intro cards, `seas/sNN.json`. */
    fun sea(sea: Int): CatalogResult<SeaDto> = synchronized(lock) {
        seas.getOrPut(sea) { load("seas/${seaId(sea)}.json", SeaDto.serializer()) }
    }

    /**
     * The patrol config, `patrol/config.json`. The patrol needs no campaign content: without a
     * manifest, or without that file in it, the built-in [PatrolDefaults] are used. A listed file that fails its
     * SHA-256 check is refused like any other content.
     */
    fun patrol(): CatalogResult<PatrolConfigDto> = synchronized(lock) {
        patrolResult ?: loadPatrol().also { patrolResult = it }
    }

    private fun loadPatrol(): CatalogResult<PatrolConfigDto> {
        val listed = (manifest() as? CatalogResult.Ok)?.value?.files?.containsKey(PATROL_PATH) == true
        if (!listed) return CatalogResult.Ok(PatrolDefaults.config())
        return when (val loaded = load(PATROL_PATH, PatrolConfigDto.serializer())) {
            is CatalogResult.Ok -> if (loaded.value.isUsable()) loaded else CatalogResult.Failed("$PATROL_PATH has no usable stages")
            is CatalogResult.Failed -> loaded
        }
    }

    /** Terrain maps by id, `terrains.json`. Decoded once. */
    fun terrains(): CatalogResult<TerrainsDto> = synchronized(lock) {
        terrainsResult ?: load(TERRAINS_PATH, TerrainsDto.serializer()).also { terrainsResult = it }
    }

    /** Certified enemy layouts of a pool, `layouts/<id>.json`. */
    fun layoutPool(id: String): CatalogResult<LayoutPoolDto> = synchronized(lock) {
        pools.getOrPut(id) { load("layouts/$id.json", LayoutPoolDto.serializer()) }
    }

    /** A certificate by id, `<missionId>-<E|M|H>` (or the puzzle id for stand-alone puzzles). */
    fun certificate(id: String): CatalogResult<CertificateDto> = synchronized(lock) {
        certificates.getOrPut(id) { load("certificates/$id.json", CertificateDto.serializer()) }
    }

    /** A puzzle definition by id, `puzzles/<id>.json`. */
    fun puzzle(id: String): CatalogResult<PuzzleDefinition> = synchronized(lock) {
        puzzles.getOrPut(id) { load("puzzles/$id.json", PuzzleDefinition.serializer()) }
    }

    /** The daily pool index, `daily/pool.json`. Decoded once. */
    fun dailyPool(): CatalogResult<DailyPoolDto> = synchronized(lock) {
        dailyPoolResult ?: load(DAILY_POOL_PATH, DailyPoolDto.serializer()).also { dailyPoolResult = it }
    }

    /** The daily hunts, `daily/hunts.json`: one self-contained HUNT per pool entry. Decoded once. */
    fun dailyHunts(): CatalogResult<DailyHuntsDto> = synchronized(lock) {
        dailyHuntsResult ?: load(DAILY_HUNTS_PATH, DailyHuntsDto.serializer()).also { dailyHuntsResult = it }
    }

    private fun missionLocked(id: String): CatalogResult<MissionDto> {
        val seaId = seaIdOf(id) ?: return CatalogResult.Failed("malformed mission id $id")
        return missions.getOrPut(id) { load("missions/$seaId/$id.json", MissionDto.serializer()) }
    }

    private fun <T> load(path: String, serializer: KSerializer<T>): CatalogResult<T> {
        val bytes = when (val verified = verifiedBytes(path)) {
            is CatalogResult.Ok -> verified.value
            is CatalogResult.Failed -> return verified
        }
        return try {
            CatalogResult.Ok(ContentJson.json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)))
        } catch (e: SerializationException) {
            CatalogResult.Failed("cannot decode $path: ${e.message}")
        } catch (e: IllegalArgumentException) {
            CatalogResult.Failed("cannot decode $path: ${e.message}")
        }
    }

    /** The bytes of [path], only when they match the SHA-256 that the manifest lists for them. */
    private fun verifiedBytes(path: String): CatalogResult<ByteArray> {
        val manifest = manifest()
        val listed = (manifest as? CatalogResult.Ok)?.value?.files
            ?: return CatalogResult.Failed((manifest as CatalogResult.Failed).reason)
        val expected = listed[path] ?: return CatalogResult.Failed("$path is not listed in the manifest")
        val bytes = readAsset(path) ?: return CatalogResult.Failed("$path is missing from the app assets")
        val actual = sha256Hex(bytes)
        return if (actual == expected) {
            CatalogResult.Ok(bytes)
        } else {
            CatalogResult.Failed("$path failed its SHA-256 check (expected $expected, found $actual)")
        }
    }

    private fun manifest(): CatalogResult<CatalogManifestDto> = manifestResult ?: run {
        val result = readManifest()
        manifestResult = result
        result
    }

    private fun readManifest(): CatalogResult<CatalogManifestDto> {
        val bytes = readAsset(MANIFEST_PATH) ?: return CatalogResult.Failed("$MANIFEST_PATH is missing from the app assets")
        return try {
            val manifest = ContentJson.json.decodeFromString(CatalogManifestDto.serializer(), bytes.toString(Charsets.UTF_8))
            if (manifest.format != CatalogManifestDto.FORMAT) {
                CatalogResult.Failed("unknown content format ${manifest.format}")
            } else {
                CatalogResult.Ok(manifest)
            }
        } catch (e: SerializationException) {
            CatalogResult.Failed("cannot decode $MANIFEST_PATH: ${e.message}")
        }
    }

    private fun failedCatalog(): CatalogResult.Failed = when (val result = catalog()) {
        is CatalogResult.Failed -> result
        is CatalogResult.Ok -> CatalogResult.Failed("catalog unavailable")
    }

    companion object {
        const val MANIFEST_PATH = "manifest.json"
        const val CATALOG_PATH = "catalog.json"
        const val TERRAINS_PATH = "terrains.json"
        const val DAILY_POOL_PATH = "daily/pool.json"
        const val DAILY_HUNTS_PATH = "daily/hunts.json"
        const val PATROL_PATH = "patrol/config.json"

        /** A patrol config every sea can be built from: at least one stage, square grids within the engine's sizes. */
        private fun PatrolConfigDto.isUsable(): Boolean = stages.isNotEmpty() &&
            stages.all { it.width == it.height && it.width in pt.tiago.nightfleet.core.rules.RuleSet.MIN_SIDE..pt.tiago.nightfleet.core.rules.RuleSet.MAX_SIDE }

        private val MISSION_ID = Regex("s(\\d{2})m\\d{3}")

        /** `s03` for sea 3. */
        fun seaId(sea: Int): String = "s%02d".format(sea)

        /** Sea id of a mission id (`s03m112` -> `s03`), or null when malformed. */
        fun seaIdOf(missionId: String): String? = MISSION_ID.matchEntire(missionId)?.let { "s" + it.groupValues[1] }

        /** Reads the content from the APK assets. A missing asset is reported as null. */
        fun fromAssets(context: Context): CatalogRepository = CatalogRepository { path ->
            try {
                context.assets.open(path).use { it.readBytes() }
            } catch (e: FileNotFoundException) {
                null
            }
        }
    }
}
