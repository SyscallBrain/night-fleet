package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyHuntDto
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64
import java.io.File

/**
 * Daily hunts. Dedicated HUNT variants live in `daily/hunts.json`, 400 per difficulty; each
 * one is self-contained (its terrain, rules, layouts and certificate), and `daily/pool.json` refers to it
 * by id with a HUNT entry. Everything here is deterministic in the version and the seed.
 */

/** Daily file of the hunts. */
internal const val DAILY_HUNTS_PATH = "daily/hunts.json"

/** Daily pool index. */
internal const val DAILY_POOL_PATH = "daily/pool.json"

/** Board side of the daily hunts: the classic 10 x 10 fleet, as the campaign's classic missions. */
internal const val DAILY_HUNT_SIDE = 10

/** Fleet preset of the daily hunts. */
internal const val DAILY_HUNT_FLEET = "CLASSIC"

/** Id of a daily hunt: `v<version>-hunt-<difficulty>-<nnn>`. */
internal fun dailyHuntId(version: Int, difficulty: Difficulty, index: Int): String =
    "v$version-hunt-${difficulty.name.lowercase()}-%03d".format(index)

/** Terrain map of a list of hunts, keyed by the terrain id of each hunt's rules. */
internal fun dailyTerrains(hunts: List<DailyHuntDto>): Map<String, List<Terrain>> = hunts.associate { hunt ->
    val id = checkNotNull(hunt.rules.terrain) { "daily hunt ${hunt.id} has no terrain" }
    id to TerrainDto(id, hunt.terrain).toTerrains()
}

/** The layout pool of a hunt, as the hash of its certificate names it. */
internal fun DailyHuntDto.layoutPool(): LayoutPoolDto {
    val terrainId = checkNotNull(rules.terrain) { "daily hunt $id has no terrain" }
    return LayoutPoolDto(
        id = id,
        fleet = rules.fleet,
        terrain = terrainId,
        terrainHash = canonicalHash(TerrainDto.serializer(), TerrainDto(terrainId, terrain)),
        layouts = layouts,
    )
}

/**
 * Generates `daily/hunts.json` with [count] hunts per difficulty and adds their HUNT entries to
 * `daily/pool.json`, keeping the puzzle entries that are already there. Uncalibrated, as `calibrate` expects.
 */
internal fun generateDailyHunts(root: File, version: Int, count: Int, seed: Long): DailyHuntsDto {
    require(version >= 1) { "version must be at least 1" }
    val hunts = mutableListOf<DailyHuntDto>()
    for (difficulty in Difficulty.entries) {
        for (i in 0 until count) {
            val huntSeed = SplitMix64.split(seed, difficulty.ordinal * 100_000L + i)
            hunts += buildDailyHunt(dailyHuntId(version, difficulty, i), difficulty, huntSeed)
        }
    }
    val dto = DailyHuntsDto(version = version, hunts = hunts)
    writeCanonicalFile(root, DAILY_HUNTS_PATH, DailyHuntsDto.serializer(), dto)

    val puzzles = readDailyPool(root)?.entries?.filter { it.kind == DailyKind.PUZZLE }.orEmpty()
    val huntEntries = hunts.map { DailyEntryDto(id = it.id, kind = DailyKind.HUNT, difficulty = it.difficulty, ref = it.id) }
    writeCanonicalFile(root, DAILY_POOL_PATH, DailyPoolDto.serializer(), DailyPoolDto(version = version, entries = puzzles + huntEntries))
    return dto
}

/** The daily pool document if it exists. */
internal fun readDailyPool(root: File): DailyPoolDto? {
    val file = File(root, DAILY_POOL_PATH)
    if (!file.isFile) return null
    return ContentJson.json.decodeFromString(DailyPoolDto.serializer(), file.readText(Charsets.UTF_8))
}

private fun buildDailyHunt(id: String, difficulty: Difficulty, huntSeed: Long): DailyHuntDto {
    val fleet = fleetPreset(DAILY_HUNT_FLEET)
    val kinds = TerrainKind.entries
    val kind = kinds[SplitMix64(SplitMix64.split(huntSeed, 1L)).nextInt(kinds.size)]
    val rows = TerrainGen.generate(DAILY_HUNT_SIDE, kind, fleet, SplitMix64(SplitMix64.split(huntSeed, 2L)), HashSet())
        ?: error("$id: no ${kind.family} pattern fits the $DAILY_HUNT_FLEET fleet on ${DAILY_HUNT_SIDE}x$DAILY_HUNT_SIDE")
    val rules = RulesDto(width = DAILY_HUNT_SIDE, height = DAILY_HUNT_SIDE, fleet = DAILY_HUNT_FLEET, terrain = id)
    val levelSeed = SplitMix64.split(huntSeed, 3L)
    val ruleSet = rules.toRuleSet(mapOf(id to TerrainDto(id, rows).toTerrains()))
    val layouts = LayoutPools.layouts(
        board = ruleSet.boardFor(1),
        fleet = fleet,
        style = PlacementStyle.BALANCED,
        count = EXPECTED_LAYOUTS.getValue(difficulty),
        seed = levelSeed,
    ).map { LayoutPools.toDto(it) }
    return DailyHuntDto(id = id, difficulty = difficulty, levelSeed = levelSeed, terrain = rows, rules = rules, layouts = layouts)
}
