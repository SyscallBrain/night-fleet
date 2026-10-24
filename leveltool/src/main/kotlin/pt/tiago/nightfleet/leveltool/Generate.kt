package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.AdmiralDto
import pt.tiago.nightfleet.core.content.AdmiralsDto
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ConvoyDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DivingDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.EarnDto
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.FogDto
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.SeaDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.TerrainsDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.WeaponConfigDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.ai.AI_VERSION
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition as PuzzleDef
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.EarnOn
import java.io.File

/** Everything one mission needs on disk. */
internal class MissionOutput(
    val mission: MissionDto,
    val terrain: TerrainDto?,
    val pools: List<LayoutPoolDto>,
    val puzzles: List<PuzzleDef>,
    val certificates: List<Pair<String, CertificateDto>>,
)

/** Mechanic -> the tag it stands for (weapons only). */
private val WEAPON_TAGS: Map<Mech, MissionTag> = mapOf(
    Mech.SONAR to MissionTag.SONAR,
    Mech.TORPEDO to MissionTag.TORPEDO,
    Mech.RECON to MissionTag.RECON,
    Mech.CROSS to MissionTag.CROSS,
    Mech.DECOY to MissionTag.DECOY,
    Mech.REPAIR to MissionTag.REPAIR,
)

/**
 * Builds the 50 missions of [sea] with seed [seed]. Every mission is built even
 * when only part of the sea is written, because the terrain patterns of a sea depend on the earlier ones.
 */
/**
 * Builds the missions of [sea]. The plan (types, families, tags) comes from [seed]; the layouts, terrains and
 * level seeds of each mission come from [layoutSeed], which defaults to [seed]. A different layout seed keeps the
 * plan of the sea, so one mission can be regenerated without changing the campaign mix.
 */
internal fun buildSea(sea: Int, seed: Long, layoutSeed: Long = seed): List<MissionOutput> {
    val usedTerrains = HashSet<String>()
    return planSea(sea, seed).map { planned -> buildMission(planned, layoutSeed, usedTerrains) }
}

private fun buildMission(p: Planned, seed: Long, usedTerrains: MutableSet<String>): MissionOutput {
    val id = missionIdOf(p.sea, p.index)
    val ordinal = (p.sea - 1) * MISSIONS_PER_SEA + p.index
    val missionSeed = SplitMix64.split(seed, ordinal.toLong())
    fun levelSeed(d: Difficulty): Long = SplitMix64.split(missionSeed, d.ordinal + 1L)
    val admiral = ADMIRALS.first { it.id == SEAS[p.sea - 1].admiralId }

    return if (p.kind == MissionKind.PUZZLE) buildPuzzleMission(p, id, ordinal, ::levelSeed)
    else buildBattleMission(p, id, ordinal, missionSeed, admiral, usedTerrains, ::levelSeed)
}

private fun buildPuzzleMission(p: Planned, id: String, ordinal: Int, levelSeed: (Difficulty) -> Long): MissionOutput {
    val side = puzzleSide(p.sea, p.index)
    val puzzles = mutableListOf<PuzzleDef>()
    val certificates = mutableListOf<Pair<String, CertificateDto>>()
    val variants = Difficulty.entries.associateWith { d ->
        val proven = Puzzles.prove(side, Puzzles.gradeOf(d), levelSeed(d))
        puzzles += proven.definition
        val certificateId = certificateId(id, d)
        certificates += certificateId to Puzzles.proof(certificateId, proven)
        VariantDto(
            levelSeed = levelSeed(d),
            rules = RulesDto(),
            ai = AiConfigDto(tier = d, blunder = blunderRate(d, intensity(p.sea, p.index))),
            firstPlayer = FirstPlayer.PLAYER,
            puzzleId = proven.definition.id,
        )
    }
    val medium = certificates.first { it.first.endsWith("-M") }.second as CertificateDto.Proof
    val tags = if (puzzles.any { it.islands.isNotEmpty() }) listOf(MissionTag.ISLANDS) else emptyList()
    val mission = MissionDto(
        id = id,
        sea = p.sea,
        ordinal = ordinal,
        type = MissionKind.PUZZLE,
        family = "puzzle-$side-t${medium.grade.maxTechnique.ordinal + 1}",
        tags = tags,
        tutorial = null,
        variants = variants,
    )
    return MissionOutput(mission, null, emptyList(), puzzles, certificates)
}

private fun buildBattleMission(
    p: Planned,
    id: String,
    ordinal: Int,
    missionSeed: Long,
    admiral: AdmiralDto,
    usedTerrains: MutableSet<String>,
    levelSeed: (Difficulty) -> Long,
): MissionOutput {
    val isBoss = p.kind == MissionKind.BOSS
    val side = if (isBoss) (if (p.sea >= 11) 12 else 10) else boardSide(p.sea, p.index)
    val fleet = when {
        p.tutorial != null -> "TUTORIAL"
        p.kind == MissionKind.CONVOY -> if (side == 12) "LARGE" else "CLASSIC"
        Mech.SCHOOL in p.mechs && side == 10 -> "SCHOOL"
        else -> fleetForSide(side)
    }
    val choices = if (p.sea >= 3) TerrainKind.entries else TerrainKind.entries.filter { it != TerrainKind.REEF }
    val first = SplitMix64(SplitMix64.split(missionSeed, 99L)).nextInt(choices.size)
    val (kind, rows) = terrainFor(id, side, choices, first, terrainFleetOf(p.kind, fleetPreset(fleet)), missionSeed, usedTerrains)
    val terrainId = "${kind.family}-$id"
    val cells = rowsToTerrain(rows)
    val terrainDto = TerrainDto(terrainId, rows)
    val terrainHash = canonicalHash(TerrainDto.serializer(), terrainDto)
    val terrainMap = mapOf(terrainId to cells)
    val fleetPreset = fleetPreset(fleet)
    val style = placementStyleNamed(admiral.placementStyle)

    val pools = mutableListOf<LayoutPoolDto>()
    val puzzles = mutableListOf<PuzzleDef>()
    val variants = Difficulty.entries.associateWith { d ->
        val seed = levelSeed(d)
        val poolId = "$id-${letterOf(d)}"
        val mineCells = if (Mech.MINES in p.mechs) {
            LayoutPools.mines(Board(side, side, cells, emptyList()), 4 + SplitMix64(SplitMix64.split(seed, 2L)).nextInt(3), SplitMix64(SplitMix64.split(seed, 3L)))
        } else {
            emptyList()
        }
        val mines = mineCells.map { it.label() }
        val board = Board(side, side, cells, mineCells.map { it.row * side + it.col }.sorted())
        val layouts = LayoutPools.layouts(board, fleetPreset, style, if (isBoss) BOSS_LAYOUTS else layoutsFor(d), SplitMix64.split(seed, 1L))
        pools += layoutPoolDto(poolId, fleet, terrainId, terrainHash, layouts)
        val rules = RulesDto(
            width = side,
            height = side,
            fleet = fleet,
            terrain = terrainId,
            salvo = Mech.SALVO in p.mechs,
            diving = if (Mech.DIVING in p.mechs) DivingDto(surfaceRounds = 2, submergedRounds = 2) else null,
            fog = if (Mech.FOG in p.mechs) FogDto(missFadeTurns = 4) else null,
            movingShips = Mech.MOVING in p.mechs,
            mines = listOf(emptyList(), mines),
            extraShips = if (p.kind == MissionKind.CONVOY) listOf(convoyFreighters(), emptyList()) else listOf(emptyList(), emptyList()),
            shotClockSeconds = if (Mech.TIMED in p.mechs) shotClockFor(d) else null,
        )
        VariantDto(
            levelSeed = seed,
            rules = rules,
            ai = AiConfigDto(
                tier = d,
                blunder = blunderRate(d, intensity(p.sea, p.index)),
                admiralWeapons = admiralWeapons(p, d),
            ),
            weapons = p.weapons.associate { weapon -> weaponName(weapon) to weaponConfig(weapon, d) },
            layoutPool = poolId,
            firstPlayer = when {
                p.kind == MissionKind.HUNT -> FirstPlayer.PLAYER
                isBoss || d == Difficulty.HARD -> FirstPlayer.ALTERNATE
                else -> FirstPlayer.PLAYER
            },
            calibrated = false,
            stars = null,
            shotBudget = null,
            convoy = if (p.kind == MissionKind.CONVOY) convoyFor(d) else null,
        )
    }

    val features = variants.values.flatMap { variant ->
        val ruleSet = variant.toRuleSet(terrainMap)
        ruleFeaturesOf(ruleSet)
    }.toSet()
    val weaponTags = p.weapons.mapNotNull { WEAPON_TAGS[it] }.toSet()
    val tags = (features + weaponTags).sortedBy { it.ordinal }

    val mission = MissionDto(
        id = id,
        sea = p.sea,
        ordinal = ordinal,
        type = p.kind,
        family = familyOf(p, side, kind),
        tags = tags,
        tutorial = p.tutorial,
        variants = variants,
    )
    return MissionOutput(mission, terrainDto, pools, puzzles, emptyList())
}

/** Freighters the player adds to the fleet of a CONVOY. */
private const val CONVOY_FREIGHTERS = 3

private fun convoyFreighters(): List<ShipClass> = List(CONVOY_FREIGHTERS) { ShipClass.FREIGHTER }

/**
 * The fleet a terrain must hold: for a CONVOY, the player's fleet with its freighters (the density limit and
 * a random layout both count them, see PlacementValidator and RandomPlacer), otherwise the fleet itself.
 */
internal fun terrainFleetOf(kind: MissionKind, fleet: Fleet): Fleet =
    if (kind == MissionKind.CONVOY) Fleet(fleet.name, fleet.ships + convoyFreighters()) else fleet

/**
 * A terrain pattern of [side] that holds [fleet]. The first try uses the drawn shape and its own seed; when no
 * pattern fits (a convoy's extra ships on a crowded grid), the other shapes and new seeds are tried in turn, so
 * the same mission always gets the same terrain. Fails only when every try fails.
 */
private fun terrainFor(
    id: String,
    side: Int,
    choices: List<TerrainKind>,
    first: Int,
    fleet: Fleet,
    missionSeed: Long,
    used: MutableSet<String>,
): Pair<TerrainKind, List<String>> {
    for (attempt in 0 until choices.size * TERRAIN_ROUNDS) {
        val kind = choices[(first + attempt) % choices.size]
        val seed = if (attempt == 0) SplitMix64.split(missionSeed, 98L) else SplitMix64.split(missionSeed, 300L + attempt)
        TerrainGen.generate(side, kind, fleet, SplitMix64(seed), used)?.let { return kind to it }
    }
    error("$id: no terrain pattern of ${side}x$side holds the ${fleet.name} fleet (${fleet.ships.size} ships)")
}

private const val TERRAIN_ROUNDS = 3

/** Family name of a battle mission from its modifiers, size and terrain. */
private fun familyOf(p: Planned, side: Int, terrain: TerrainKind): String {
    val islands = terrain != TerrainKind.REEF
    return when (p.kind) {
        MissionKind.BOSS -> "boss-series"
        MissionKind.HUNT -> when {
            p.sea == 1 -> "hunt-practice"
            Mech.MINES in p.mechs -> "hunt-mines"
            Mech.SCHOOL in p.mechs -> "hunt-school"
            Mech.FOG in p.mechs -> "hunt-fog"
            p.weapons.isNotEmpty() -> "hunt-weapons"
            side == 12 -> "hunt-large"
            islands -> "hunt-islands"
            else -> "hunt-tight"
        }
        MissionKind.CONVOY -> when {
            Mech.DECOY in p.weapons -> "convoy-decoy"
            Mech.SALVO in p.mechs -> "convoy-salvo"
            islands -> "convoy-islands"
            else -> "convoy-line"
        }
        else -> when {
            p.tutorial != null -> "open-tutorial"
            Mech.MINES in p.mechs -> if (p.index % 2 == 0) "mines-scatter" else "mines-belt"
            Mech.DIVING in p.mechs -> "dive-fjord"
            Mech.FOG in p.mechs -> "fog-large"
            Mech.MOVING in p.mechs -> "moving-open"
            Mech.SALVO in p.mechs -> if (islands) "salvo-islands" else "salvo-open"
            Mech.SCHOOL in p.mechs -> if (islands) "school-islands" else "school-open"
            Mech.TIMED in p.mechs -> if (side == 8) "timed-small" else "timed-classic"
            else -> terrain.family
        }
    }
}

private fun letterOf(d: Difficulty): String = when (d) {
    Difficulty.EASY -> "E"
    Difficulty.MEDIUM -> "M"
    Difficulty.HARD -> "H"
}

private fun weaponName(weapon: Mech): String = WEAPON_OF.getValue(weapon)

/** The sea that introduces a weapon (its first sea in INTROS). */
private fun introSeaOf(weapon: Mech): Int = INTROS.entries.firstOrNull { weapon in it.value }?.key ?: 1

/**
 * The weapons a difficulty's admiral may use in a mission, by `Weapon` name: Easy only Sonar from sea 6
 * (and the Medium rule before it); Medium the weapons introduced at least one sea earlier; Hard all the mission's.
 */
internal fun admiralWeapons(p: Planned, d: Difficulty): List<String> {
    val earlier = p.weapons.filter { introSeaOf(it) < p.sea }
    val chosen = when (d) {
        Difficulty.HARD -> p.weapons
        Difficulty.MEDIUM -> earlier
        Difficulty.EASY -> if (p.sea >= EASY_SONAR_ONLY_FROM_SEA) p.weapons.filter { it == Mech.SONAR } else earlier
    }
    return chosen.sortedBy { it.ordinal }.map { weaponName(it) }
}

/** First sea whose Easy admirals fire only Sonar. */
private const val EASY_SONAR_ONLY_FROM_SEA = 6

/** Charges and earning of a weapon by difficulty (+1 charge on Easy, streak 4 on Hard). */
private fun weaponConfig(weapon: Mech, d: Difficulty): WeaponConfigDto {
    val easyBonus = if (d == Difficulty.EASY) 1 else 0
    return when (weapon) {
        Mech.SONAR -> WeaponConfigDto(
            initial = 1 + easyBonus,
            max = 2,
            earn = listOf(EarnDto(EarnOn.STREAK, if (d == Difficulty.HARD) 4 else 3)),
        )
        Mech.TORPEDO -> WeaponConfigDto(initial = easyBonus, max = 1 + easyBonus, earn = listOf(EarnDto(EarnOn.SINK, 1)))
        Mech.CROSS -> WeaponConfigDto(initial = easyBonus, max = 1 + easyBonus, earn = listOf(EarnDto(EarnOn.EVERY, 4)))
        Mech.RECON, Mech.DECOY, Mech.REPAIR -> WeaponConfigDto(initial = 1 + easyBonus, max = 1 + easyBonus)
        else -> error("$weapon is not a weapon")
    }
}

private fun convoyFor(d: Difficulty): ConvoyDto = when (d) {
    Difficulty.EASY -> ConvoyDto(surviveRounds = 12, minFreighters = 1)
    Difficulty.MEDIUM, Difficulty.HARD -> ConvoyDto(surviveRounds = 10, minFreighters = 2)
}

/** Writes the missions of [sea] in [from]..[to] (inclusive) under [root], then reseals the catalog. */
internal fun generateSea(root: File, sea: Int, from: Int, to: Int, seed: Long, layoutSeed: Long = seed): List<String> {
    require(sea in 1..SEAS_COUNT) { "sea must be 1..$SEAS_COUNT" }
    require(from in 1..MISSIONS_PER_SEA && to in from..MISSIONS_PER_SEA) { "range must be 1..50 with from <= to" }
    val built = buildSea(sea, seed, layoutSeed)
    val seaId = seaIdOfNumber(sea)
    val written = built.filter { it.mission.ordinal - (sea - 1) * MISSIONS_PER_SEA in from..to }

    writeCanonicalFile(root, "admirals.json", AdmiralsDto.serializer(), AdmiralsDto(ADMIRALS))
    writeCanonicalFile(root, "seas/$seaId.json", SeaDto.serializer(), SEAS[sea - 1])

    val terrains = readTerrains(root).toMutableMap()
    for (output in written) {
        val mission = output.mission
        writeCanonicalFile(root, missionPath(seaId, mission.id), MissionDto.serializer(), mission)
        output.terrain?.let { terrains[it.id] = it }
        for (pool in output.pools) writeCanonicalFile(root, "layouts/${pool.id}.json", LayoutPoolDto.serializer(), pool)
        for (puzzle in output.puzzles) writeCanonicalFile(root, "puzzles/${puzzle.id}.json", PuzzleDef.serializer(), puzzle)
        for ((id, certificate) in output.certificates) {
            writeCanonicalFile(root, "certificates/$id.json", CertificateDto.serializer(), certificate)
        }
    }
    writeCanonicalFile(root, "terrains.json", TerrainsDto.serializer(), TerrainsDto(terrains.values.sortedBy { it.id }))
    mergeCatalog(root, sea, written.map { it.mission.id })
    sealContent(root)
    return written.map { it.mission.id }
}

private fun readTerrains(root: File): Map<String, TerrainDto> {
    val file = File(root, "terrains.json")
    if (!file.isFile) return emptyMap()
    return ContentJson.json.decodeFromString(TerrainsDto.serializer(), file.readText(Charsets.UTF_8)).terrains.associateBy { it.id }
}

private fun mergeCatalog(root: File, sea: Int, missions: List<String>) {
    val file = File(root, "catalog.json")
    val existing = if (file.isFile) ContentJson.json.decodeFromString(CatalogDto.serializer(), file.readText(Charsets.UTF_8)) else null
    val seas = ((existing?.seas ?: emptyList()) + seaIdOfNumber(sea)).distinct().sorted()
    val all = ((existing?.missions ?: emptyList()) + missions).distinct().sorted()
    val catalog = CatalogDto(
        contentVersion = CONTENT_VERSION,
        engineVersion = ENGINE_VERSION,
        aiVersion = AI_VERSION,
        seas = seas,
        missions = all,
        files = emptyMap(),
    )
    writeCanonicalFile(root, "catalog.json", CatalogDto.serializer(), catalog)
}
