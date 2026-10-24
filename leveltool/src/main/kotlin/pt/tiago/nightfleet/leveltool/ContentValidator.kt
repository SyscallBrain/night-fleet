package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.sha256Hex
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.rules.RuleSet
import java.io.File
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration
import pt.tiago.nightfleet.core.content.CertificateDto.Proof
import pt.tiago.nightfleet.core.content.DailyHuntDto
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.puzzle.PUZZLE_VERSION
import pt.tiago.nightfleet.core.ai.AI_VERSION
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleHasher
import pt.tiago.nightfleet.core.puzzle.difficulty


/** Strict-mode counts. */
const val STRICT_SEAS = 12
const val STRICT_MISSIONS_PER_SEA = 50
const val STRICT_ADMIRALS = 12
const val STRICT_DAILY_PER_KIND_AND_DIFFICULTY = 400
const val STRICT_ENDLESS_PER_PACK = 200
const val STRICT_PATROL_STAGES = 20

/** Minimum simulated-player games per certified variant. */
const val MIN_SAMPLE_GAMES = 64

/** Layouts per pool by difficulty: 16 on Easy, 24 on Medium, 32 on Hard. */
val EXPECTED_LAYOUTS: Map<Difficulty, Int> = mapOf(Difficulty.EASY to 16, Difficulty.MEDIUM to 24, Difficulty.HARD to 32)

/** The boss series uses 3 distinct layouts per variant. */
const val BOSS_LAYOUTS = 3

enum class Severity { ERROR, WARNING }

/** One finding: the file (relative to `content/`), a JSON path inside it and a message. */
data class Problem(
    val file: String,
    val path: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    override fun toString(): String = "$severity $file $path: $message"
}

/** `<missionId>-<E|M|H>`: the certificate of one variant. */
fun certificateId(missionId: String, difficulty: Difficulty): String = "$missionId-" + when (difficulty) {
    Difficulty.EASY -> "E"
    Difficulty.MEDIUM -> "M"
    Difficulty.HARD -> "H"
}

/** Loads `root` and returns every problem found, in a stable order. Empty means valid. */
/**
 * A gap that a partial catalog (only some seas published while the campaign is being calibrated) is allowed to
 * have: the strict campaign size counts, and the missing daily, endless and patrol packs. Every other error,
 * including a broken file or an uncalibrated variant, still fails the build.
 */
fun Problem.isPartialCatalogGap(): Boolean =
    severity == Severity.ERROR && (
        message.contains("strict:") ||
            (message.endsWith("is missing") && file.substringBefore('/') in PARTIAL_PACK_DIRECTORIES)
        )

/** Drops the gaps of [isPartialCatalogGap] when [partial] is set; otherwise returns the problems unchanged. */
fun List<Problem>.withPartialCatalog(partial: Boolean): List<Problem> =
    if (partial) filterNot(Problem::isPartialCatalogGap) else this

private val PARTIAL_PACK_DIRECTORIES = setOf("daily", "endless", "patrol")

fun validateContent(root: File, strict: Boolean): List<Problem> {
    val set = loadContent(root)
    return ContentValidator(set, strict).run()
}

/** Tags that name a weapon, with the weapon they stand for. */
private val WEAPON_TAGS: Map<MissionTag, Weapon> = mapOf(
    MissionTag.SONAR to Weapon.SONAR,
    MissionTag.TORPEDO to Weapon.TORPEDO,
    MissionTag.RECON to Weapon.RECON,
    MissionTag.CROSS to Weapon.CROSS_SHELL,
    MissionTag.DECOY to Weapon.DECOY,
    MissionTag.REPAIR to Weapon.REPAIR,
)

private val SEA_ID = Regex("s\\d{2}")

/**
 * The strict and lenient rules. Lenient mode checks the shape and the references of
 * what exists; strict mode also demands the full campaign (12 seas of 50 missions, 12 admirals,
 * every file listed in `catalog.files`, every certificate and the exact layout counts).
 */
internal class ContentValidator(private val set: ContentSet, private val strict: Boolean) {
    private val problems = LinkedHashSet<Problem>()
    private val terrainById: Map<String, TerrainDto> = set.terrains?.value?.terrains?.associateBy { it.id }.orEmpty()
    private val terrainCells: Map<String, List<Terrain>> = terrainById.mapNotNull { (id, dto) ->
        runCatching { dto.toTerrains() }.getOrNull()?.let { id to it }
    }.toMap()
    private val usedPools = HashSet<String>()
    private val usages = mutableListOf<Usage>()
    private val layoutCache = HashMap<String, List<Problem>>()

    /** One variant of one mission, as the certificate checks need it. */
    private class Usage(
        val mission: Loaded<MissionDto>,
        val difficulty: Difficulty,
        val variant: VariantDto,
        val poolId: String?,
    )

    fun run(): List<Problem> {
        set.loadProblems.forEach { add(it) }
        val catalog = set.catalog
        if (catalog == null) {
            add(Problem("catalog.json", "$", "catalog is missing or cannot be read"))
            return problems.toList()
        }
        checkCatalog(catalog)
        checkTerrains()
        checkAdmirals()
        checkSeas(catalog)
        checkPuzzles()
        checkPools()
        checkMissions(catalog)
        checkCertificates(catalog)
        checkDailyHunts(catalog)
        checkExtras()
        return problems.toList()
    }

    private fun add(problem: Problem) {
        problems += problem
    }

    private fun error(file: String, path: String, message: String) = add(Problem(file, path, message))

    private fun warning(file: String, path: String, message: String) = add(Problem(file, path, message, Severity.WARNING))

    /** An error in strict mode, a warning otherwise. */
    private fun strictError(file: String, path: String, message: String) =
        add(Problem(file, path, message, if (strict) Severity.ERROR else Severity.WARNING))

    private fun checkCatalog(catalog: CatalogDto) {
        val file = "catalog.json"
        if (catalog.engineVersion != ENGINE_VERSION) {
            error(file, "$.engineVersion", "engineVersion ${catalog.engineVersion} does not match the engine ($ENGINE_VERSION)")
        }
        if (catalog.aiVersion != AI_VERSION) error(file, "$.aiVersion", "aiVersion ${catalog.aiVersion} does not match $AI_VERSION")
        if (catalog.contentVersion < 1) error(file, "$.contentVersion", "contentVersion must be at least 1")
        duplicates(catalog.seas).forEach { error(file, "$.seas", "duplicate sea $it") }
        duplicates(catalog.missions).forEach { error(file, "$.missions", "duplicate mission $it") }

        for ((path, sha) in catalog.files.toSortedMap()) {
            val entryPath = "$.files[\"$path\"]"
            if (path.startsWith("/") || path.contains("..") || !isPublishedPath(path)) {
                error(file, entryPath, "not a published content path")
                continue
            }
            val target = File(set.root, path)
            if (!target.isFile) {
                strictError(file, entryPath, "listed file is missing")
                continue
            }
            val actual = sha256Hex(target.readBytes())
            if (actual != sha) error(file, entryPath, "sha256 mismatch: catalog has $sha, file has $actual")
        }
        if (strict) {
            (set.publishedFiles - catalog.files.keys).forEach {
                error(it, "$", "published file is not listed in catalog.files")
            }
            if (catalog.seas.size != STRICT_SEAS) error(file, "$.seas", "strict: $STRICT_SEAS seas required, found ${catalog.seas.size}")
            val expected = STRICT_SEAS * STRICT_MISSIONS_PER_SEA
            if (catalog.missions.size != expected) error(file, "$.missions", "strict: $expected missions required, found ${catalog.missions.size}")
        }
    }

    private fun checkTerrains() {
        val terrains = set.terrains ?: return
        duplicates(terrains.value.terrains.map { it.id }).forEach { error(terrains.file, "$.terrains", "duplicate terrain $it") }
        terrains.value.terrains.forEachIndexed { i, terrain ->
            runCatching { terrain.toTerrains() }.onFailure {
                error(terrains.file, "$.terrains[$i]", it.message ?: "invalid terrain ${terrain.id}")
            }
        }
    }

    private fun checkAdmirals() {
        val admirals = set.admirals ?: return
        val list = admirals.value.admirals
        duplicates(list.map { it.id }).forEach { error(admirals.file, "$.admirals", "duplicate admiral $it") }
        duplicates(list.map { it.number.toString() }).forEach { error(admirals.file, "$.admirals", "duplicate admiral number $it") }
        list.forEachIndexed { i, admiral ->
            val path = "$.admirals[$i]"
            if (admiral.id.isBlank() || admiral.nameKey.isBlank()) error(admirals.file, path, "id and nameKey are required")
            if (admiral.placementStyle.isBlank() || admiral.search.isBlank() || admiral.weaponPolicy.isBlank()) {
                error(admirals.file, path, "placementStyle, search and weaponPolicy are required")
            }
        }
        if (strict && list.size != STRICT_ADMIRALS) error(admirals.file, "$.admirals", "strict: $STRICT_ADMIRALS admirals required, found ${list.size}")
    }

    private fun checkSeas(catalog: CatalogDto) {
        val admiralIds = set.admirals?.value?.admirals?.map { it.id }?.toSet().orEmpty()
        for ((index, seaId) in catalog.seas.withIndex()) {
            if (!SEA_ID.matches(seaId)) error("catalog.json", "$.seas[$index]", "malformed sea id $seaId")
            val sea = set.seas[seaId] ?: continue
            val path = "$"
            if (sea.value.number != seaNumberOf(seaId)) error(sea.file, "$.number", "expected ${seaNumberOf(seaId)}, found ${sea.value.number}")
            if (sea.value.admiralId !in admiralIds) error(sea.file, "$.admiralId", "unknown admiral ${sea.value.admiralId}")
            if (sea.value.nameKey.isBlank() || sea.value.weather.isBlank() || sea.value.musicTrack.isBlank()) {
                error(sea.file, path, "nameKey, weather and musicTrack are required")
            }
            if (sea.value.introCards.any { it.isBlank() }) error(sea.file, "$.introCards", "blank intro card key")
        }
    }

    private fun checkPuzzles() {
        val seen = HashMap<String, String>()
        for ((id, loaded) in set.puzzles) {
            val puzzle: PuzzleDefinition = loaded.value
            if (puzzle.id != id) error(loaded.file, "$.id", "id ${puzzle.id} does not match the file name")
            // Sizes, fleet and count consistency are enforced by PuzzleDefinition when it is decoded.
            val hash = PuzzleHasher.hex(puzzle)
            val first = seen.putIfAbsent(hash, id)
            if (first != null) error(loaded.file, "$", "same puzzle content as $first (puzzle hash $hash)")
        }
    }

    private fun checkPools() {
        for ((id, loaded) in set.pools) {
            val pool: LayoutPoolDto = loaded.value
            if (pool.id != id) error(loaded.file, "$.id", "id ${pool.id} does not match the file name")
            if (pool.layouts.isEmpty()) error(loaded.file, "$.layouts", "a pool needs at least one layout")
            val terrainId = pool.terrain
            if (terrainId == null) {
                if (pool.terrainHash != null) error(loaded.file, "$.terrainHash", "terrainHash without a terrain")
            } else {
                val dto = terrainById[terrainId]
                if (dto == null) {
                    error(loaded.file, "$.terrain", "unknown terrain $terrainId")
                } else if (pool.terrainHash != canonicalHash(TerrainDto.serializer(), dto)) {
                    error(loaded.file, "$.terrainHash", "terrainHash does not match terrain $terrainId")
                }
            }
        }
    }

    private fun checkMissions(catalog: CatalogDto) {
        val seaCounts = HashMap<String, Int>()
        var lastOrdinal: Int? = null
        val familyKeys = HashSet<String>()
        for ((index, missionId) in catalog.missions.withIndex()) {
            val loaded = set.missions[missionId] ?: continue
            val mission = loaded.value
            val file = loaded.file
            val seaId = seaIdOf(missionId) ?: continue
            seaCounts.merge(seaId, 1, Int::plus)
            val idMatch = MISSION_ID_NUMBERS.matchEntire(missionId) ?: continue
            val idOrdinal = idMatch.groupValues[2].toInt()
            val idSea = idMatch.groupValues[1].toInt()

            if (mission.id != missionId) error(file, "$.id", "id ${mission.id} does not match catalog entry $missionId")
            if (mission.sea != idSea) error(file, "$.sea", "sea ${mission.sea} does not match id $missionId")
            val previous = lastOrdinal
            if (previous != null && mission.ordinal != previous + 1) error(file, "$.ordinal", "expected ordinal ${previous + 1}, found ${mission.ordinal}")
            lastOrdinal = mission.ordinal
            if (idOrdinal != mission.ordinal) error(file, "$.id", "id number $idOrdinal does not match ordinal ${mission.ordinal}")
            if (!catalog.seas.contains(seaId)) error(file, "$.sea", "sea $seaId is not listed in catalog.seas")
            if (mission.family.isBlank()) error(file, "$.family", "family is required")
            mission.tutorial?.let { if (it.isBlank()) error(file, "$.tutorial", "tutorial id is blank") }
            duplicates(mission.tags.map { it.name }).forEach { error(file, "$.tags", "duplicate tag $it") }
            if (mission.type == MissionKind.BOSS && mission.ordinal % STRICT_MISSIONS_PER_SEA != 0) {
                error(file, "$.type", "BOSS only at the 50th mission of a sea")
            }
            if (strict && mission.ordinal % STRICT_MISSIONS_PER_SEA == 0 && mission.type != MissionKind.BOSS) {
                error(file, "$.type", "the 50th mission of a sea must be BOSS")
            }
            val missing = Difficulty.entries.filter { it !in mission.variants }
            if (missing.isNotEmpty()) {
                error(file, "$.variants", "missing variants ${missing.joinToString()}")
                continue
            }

            val features = mutableSetOf<MissionTag>()
            var allVariantsParsed = true
            for (difficulty in Difficulty.entries) {
                val variant = mission.variants.getValue(difficulty)
                val parsed = checkVariant(loaded, mission, difficulty, variant)
                if (parsed == null) allVariantsParsed = false else features += parsed
                usages += Usage(loaded, difficulty, variant, variant.layoutPool)
            }
            if (allVariantsParsed) checkTags(file, mission, features)

            val medium = mission.variants.getValue(Difficulty.MEDIUM)
            val identity = if (mission.type == MissionKind.PUZZLE) "puzzle:${medium.puzzleId}" else canonicalHash(RulesDto.serializer(), medium.rules)
            val familyKey = "$seaId|${mission.family}|$identity"
            if (!familyKeys.add(familyKey)) error(file, "$.family", "family ${mission.family} repeats the same rules in sea $seaId")
        }
        if (strict) {
            for (seaId in catalog.seas) {
                val count = seaCounts[seaId] ?: 0
                if (count != STRICT_MISSIONS_PER_SEA) {
                    error("catalog.json", "$.missions", "strict: sea $seaId has $count missions, expected $STRICT_MISSIONS_PER_SEA")
                }
            }
        }
        for (pool in set.pools.keys - usedPools) {
            strictError(set.pools.getValue(pool).file, "$", "layout pool $pool is not used by any variant")
        }
    }

    /** Checks one variant. Returns the rule features it uses, or null when its rules cannot be built. */
    private fun checkVariant(loaded: Loaded<MissionDto>, mission: MissionDto, difficulty: Difficulty, variant: VariantDto): Set<MissionTag>? {
        val file = loaded.file
        val base = "$.variants.${difficulty.name}"
        // The admiral tier may be escalated above the difficulty, never lowered.
        if (variant.ai.tier.ordinal < difficulty.ordinal) {
            error(file, "$base.ai.tier", "AI tier ${variant.ai.tier} is below the difficulty ${difficulty.name}")
        }
        if (variant.ai.blunder !in 0..1000) error(file, "$base.ai.blunder", "blunder must be 0..1000")
        if (variant.ai.followUpMiss !in 0..900) error(file, "$base.ai.followUpMiss", "followUpMiss must be 0..900")
        variant.ai.admiralWeapons?.forEach { name ->
            if (Weapon.entries.none { it.name == name } || name !in variant.weapons) {
                error(file, "$base.ai.admiralWeapons", "admiral weapon $name is not one of the variant's weapons")
            }
        }
        if (mission.type == MissionKind.PUZZLE) return checkPuzzleVariant(file, base, variant)

        val stars = variant.stars
        if (variant.calibrated) {
            if (stars == null) error(file, "$base.stars", "a calibrated variant needs stars")
        } else {
            strictError(file, "$base.calibrated", "UNCALIBRATED: ${mission.id} ${difficulty.name} waits for calibration (stars, shotBudget, certificate)")
        }
        if (stars != null && (stars.three < 1 || stars.two < stars.three)) {
            error(file, "$base.stars", "stars must satisfy 1 <= three <= two")
        }
        if (mission.type == MissionKind.HUNT && variant.firstPlayer != FirstPlayer.PLAYER) {
            error(file, "$base.firstPlayer", "HUNT always starts with the player")
        }
        checkTypeFields(file, base, mission, variant)
        val terrainId = variant.rules.terrain
        if (terrainId != null && terrainCells[terrainId] == null) {
            error(file, "$base.rules.terrain", "unknown or malformed terrain $terrainId")
            return null
        }
        val rules: RuleSet = try {
            variant.toRuleSet(terrainCells)
        } catch (e: IllegalArgumentException) {
            error(file, "$base.rules", e.message ?: "invalid rules")
            return null
        }
        val rulesProblems = rules.problems()
        rulesProblems.forEach { error(file, "$base.rules", it) }
        if (rulesProblems.isNotEmpty()) return null

        val board = try {
            rules.boardFor(1)
        } catch (e: IllegalArgumentException) {
            error(file, "$base.rules", "invalid board: ${e.message}")
            return null
        }
        PlacementValidator.fleetProblems(board, rules.fleet).forEach {
            error(file, "$base.rules.fleet", "fleet ${rules.fleet.name} exceeds 25 % of the water squares")
        }
        checkPlayerFleet(file, base, variant, rules)

        val features = ruleFeatures(rules)
        if (variant.puzzleId != null) error(file, "$base.puzzleId", "only PUZZLE has a puzzleId")
        val poolId = variant.layoutPool
        if (poolId == null) {
            error(file, "$base.layoutPool", "${mission.type} needs a layoutPool")
        } else {
            usedPools += poolId
            val expected = if (mission.type == MissionKind.BOSS) BOSS_LAYOUTS else EXPECTED_LAYOUTS.getValue(difficulty)
            checkLayoutUsage(file, base, poolId, expected, variant, rules)
        }
        return features
    }

    /**
     * The player's fleet, extra ships included (CONVOY freighters), must fit on the terrain: the density limit holds
     * and a random layout exists, because a battle with a null placement (calibration, witness replay) needs one.
     */
    private fun checkPlayerFleet(file: String, base: String, variant: VariantDto, rules: RuleSet) {
        if (rules.extraShips.getOrNull(0).isNullOrEmpty()) return
        val fleet = rules.fleetFor(0)
        val board = rules.boardFor(0)
        if (PlacementValidator.fleetProblems(board, fleet).isNotEmpty()) {
            error(file, "$base.rules.extraShips", "fleet ${fleet.name} with its extra ships exceeds 25 % of the water squares")
            return
        }
        val placed = RandomPlacer.place(board, fleet, SplitMix64(variant.levelSeed), PlacementStyle.BALANCED, rules.shipsMayTouch)
        if (placed is PlacementResult.Failure) {
            error(file, "$base.rules.extraShips", "the player fleet with its extra ships cannot be placed on the terrain: ${placed.reason}")
        }
    }

    /** A PUZZLE variant: the puzzle carries its own rules, so the battle rules are not checked. */
    private fun checkPuzzleVariant(file: String, base: String, variant: VariantDto): Set<MissionTag>? {
        val id = variant.puzzleId
        if (id == null) {
            error(file, "$base.puzzleId", "PUZZLE needs a puzzleId")
            return null
        }
        if (variant.layoutPool != null) error(file, "$base.layoutPool", "PUZZLE has no layout pool")
        if (variant.shotBudget != null || variant.convoy != null) error(file, base, "PUZZLE has no shotBudget or convoy")
        val puzzle = set.puzzles[id]?.value
        if (puzzle == null) {
            error(file, "$base.puzzleId", "unknown puzzle $id")
            return null
        }
        return if (puzzle.islands.isNotEmpty()) setOf(MissionTag.ISLANDS) else emptySet()
    }

    /** Fields that only some mission types may carry. */
    private fun checkTypeFields(file: String, base: String, mission: MissionDto, variant: VariantDto) {
        val isHunt = mission.type == MissionKind.HUNT
        val budget = variant.shotBudget
        if (isHunt) {
            if (variant.calibrated && (budget == null || budget < 1)) {
                error(file, "$base.shotBudget", "HUNT needs a shotBudget of at least 1")
            } else if (budget != null && budget < 1) {
                error(file, "$base.shotBudget", "shotBudget must be at least 1")
            }
        } else if (budget != null) {
            error(file, "$base.shotBudget", "only HUNT has a shotBudget")
        }
        if (mission.type == MissionKind.CONVOY) {
            val convoy = variant.convoy
            if (convoy == null) {
                error(file, "$base.convoy", "CONVOY needs convoy parameters")
            } else {
                if (convoy.surviveRounds < 1) error(file, "$base.convoy.surviveRounds", "surviveRounds must be at least 1")
                if (convoy.minFreighters !in 0..3) error(file, "$base.convoy.minFreighters", "minFreighters must be 0..3")
            }
        } else if (variant.convoy != null) {
            error(file, "$base.convoy", "only CONVOY has convoy parameters")
        }
    }

    /** Every layout of the pool must be a legal fleet on the enemy sea of this variant. */
    private fun checkLayoutUsage(file: String, base: String, poolId: String, expected: Int, variant: VariantDto, rules: RuleSet) {
        val loaded = set.pools[poolId]
        if (loaded == null) {
            error(file, "$base.layoutPool", "unknown layout pool $poolId")
            return
        }
        val pool = loaded.value
        if (pool.fleet != rules.fleet.name) error(loaded.file, "$.fleet", "pool fleet ${pool.fleet} differs from ${rules.fleet.name}")
        if (pool.terrain != variant.rules.terrain) error(loaded.file, "$.terrain", "pool terrain differs from the variant")
        if (strict && pool.layouts.size != expected) {
            error(loaded.file, "$.layouts", "strict: this variant needs $expected layouts, found ${pool.layouts.size}")
        }
        val key = poolId + "|" + canonicalHash(RulesDto.serializer(), variant.rules)
        layoutCache.getOrPut(key) { validateLayouts(loaded, rules) }.forEach { add(it) }
    }

    private fun validateLayouts(loaded: Loaded<LayoutPoolDto>, rules: RuleSet): List<Problem> {
        val out = mutableListOf<Problem>()
        val board = rules.boardFor(1)
        val seen = HashSet<String>()
        loaded.value.layouts.forEachIndexed { i, layout ->
            val path = "$.layouts[$i]"
            val placements = layout.map { it.toPlacement() }
            PlacementValidator.validate(board, rules.fleetFor(1), placements, rules.shipsMayTouch).forEach { problem ->
                out += Problem(loaded.file, path, "placement ${problem.code}" + (problem.cell?.let { " at ${it.label()}" } ?: ""))
            }
            val key = layout.map { "${it.ship}:${it.row}:${it.col}:${it.rotation}" }.sorted().joinToString(",")
            if (!seen.add(key)) out += Problem(loaded.file, path, "layout repeats an earlier layout")
        }
        return out
    }

    private fun ruleFeatures(rules: RuleSet): Set<MissionTag> = ruleFeaturesOf(rules)

    /** Tags agree with the rules: a tag is present exactly when some variant uses its feature. */
    private fun checkTags(file: String, mission: MissionDto, features: Set<MissionTag>) {
        val weaponFeatures = mission.variants.values.flatMap { it.weapons.keys }.toSet()
        val used = features + WEAPON_TAGS.filter { (_, weapon) -> weapon.name in weaponFeatures }.keys
        for (tag in mission.tags.toSet() - used) {
            error(file, "$.tags", "tag $tag has no matching rule or weapon in any variant")
        }
        for (tag in used - mission.tags.toSet()) {
            error(file, "$.tags", "missing tag $tag: a variant uses it")
        }
    }

    private fun checkCertificates(catalog: CatalogDto) {
        val expected = HashSet<String>()
        for (usage in usages) {
            val id = certificateId(usage.mission.value.id, usage.difficulty)
            expected += id
            val certificate = set.certificates[id]
            val isPuzzle = usage.mission.value.type == MissionKind.PUZZLE
            if (certificate == null) {
                if (isPuzzle || usage.variant.calibrated) {
                    strictError("certificates/$id.json", "$", "certificate is missing")
                } else {
                    strictError("certificates/$id.json", "$", "UNCALIBRATED: certificate pending for ${usage.mission.value.id} ${usage.difficulty.name}")
                }
            } else {
                checkCertificate(catalog, certificate, usage, id)
            }
        }
        for (id in set.certificates.keys - expected) {
            val loaded = set.certificates.getValue(id)
            // A PROOF may also certify a stand-alone puzzle (daily and endless packs), named by the puzzle id.
            if (loaded.value is CertificateDto.Proof && id in set.puzzles) {
                checkProof(catalog, loaded.file, loaded.value, expectedPuzzle = id)
            } else {
                error(loaded.file, "$", "certificate $id matches no mission variant")
            }
        }
    }

    private fun checkCertificate(catalog: CatalogDto, loaded: Loaded<CertificateDto>, usage: Usage, id: String) {
        val file = loaded.file
        val certificate = loaded.value
        if (certificate.id != id) error(file, "$.id", "id ${certificate.id} does not match ${id}")
        if (usage.mission.value.type == MissionKind.PUZZLE) {
            checkProof(catalog, file, certificate, expectedPuzzle = usage.variant.puzzleId)
        } else {
            checkCalibration(catalog, file, certificate, usage)
        }
    }

    /** A PROOF names its puzzle, carries the unique solution of the puzzle and matches the puzzle hash. */
    private fun checkProof(catalog: CatalogDto, file: String, certificate: CertificateDto, expectedPuzzle: String?) {
        if (certificate !is Proof) {
            error(file, "$.kind", "puzzles need a PROOF certificate")
            return
        }
        if (expectedPuzzle != null && certificate.puzzleId != expectedPuzzle) error(file, "$.puzzleId", "certificate names another puzzle")
        val puzzle = set.puzzles[certificate.puzzleId]?.value
        if (puzzle == null) {
            error(file, "$.puzzleId", "unknown puzzle ${certificate.puzzleId}")
        } else {
            if (certificate.puzzleHash != PuzzleHasher.hex(puzzle)) error(file, "$.puzzleHash", "puzzle hash mismatch")
            checkSolution(file, puzzle, certificate)
        }
        if (certificate.solutionCount != 1) error(file, "$.solutionCount", "a PROOF needs solutionCount 1")
        if (certificate.difficulty != certificate.grade.difficulty()) error(file, "$.difficulty", "difficulty does not follow from the grade")
        if (certificate.puzzleVersion != PUZZLE_VERSION) error(file, "$.puzzleVersion", "puzzleVersion ${certificate.puzzleVersion} differs from $PUZZLE_VERSION")
        checkVersions(file, certificate.engineVersion, certificate.contentVersion, catalog)
    }

    private fun checkSolution(file: String, puzzle: PuzzleDefinition, certificate: Proof) {
        val solution = certificate.solution
        if (solution.width != puzzle.width || solution.height != puzzle.height) {
            error(file, "$.solution", "solution has another size")
            return
        }
        if (solution.cells.any { it == PlayerCell.EMPTY }) error(file, "$.solution", "solution must fill every square")
        val w = puzzle.width
        val h = puzzle.height
        val ships = solution.cells.count { it == PlayerCell.SHIP }
        if (ships != puzzle.fleet.sum()) error(file, "$.solution", "solution has $ships ship squares, the fleet has ${puzzle.fleet.sum()}")
        val rowsOk = (0 until h).all { r -> (0 until w).count { c -> solution[r * w + c] == PlayerCell.SHIP } == puzzle.rowCounts[r] }
        val colsOk = (0 until w).all { c -> (0 until h).count { r -> solution[r * w + c] == PlayerCell.SHIP } == puzzle.colCounts[c] }
        if (!rowsOk || !colsOk) error(file, "$.solution", "solution does not match the row and column counts")
        if (puzzle.islands.any { solution[it] == PlayerCell.SHIP }) error(file, "$.solution", "solution has a ship on an island")
    }

    private fun checkCalibration(catalog: CatalogDto, file: String, certificate: CertificateDto, usage: Usage) {
        if (certificate !is Calibration) {
            error(file, "$.kind", "battle missions need a CALIBRATION certificate")
            return
        }
        if (certificate.missionId != usage.mission.value.id) error(file, "$.missionId", "certificate names another mission")
        if (certificate.difficulty != usage.difficulty) error(file, "$.difficulty", "certificate names another difficulty")
        if (certificate.variantHash != canonicalHash(VariantDto.serializer(), usage.variant)) {
            error(file, "$.variantHash", "variant changed since the certificate was made")
        }
        val pool = usage.poolId?.let { set.pools[it] }
        if (pool == null) {
            error(file, "$.poolHash", "the variant's layout pool is missing")
        } else if (certificate.poolHash != canonicalHash(LayoutPoolDto.serializer(), pool.value)) {
            error(file, "$.poolHash", "layout pool changed since the certificate was made")
        }
        checkVersions(file, certificate.engineVersion, certificate.contentVersion, catalog, certificate.aiVersion)
        checkSampleAndWitness(file, certificate, usage.variant.stars, pool?.value?.layouts?.size)
    }

    /** Sample size, quantiles, thresholds and the witness format of a CALIBRATION certificate. */
    private fun checkSampleAndWitness(file: String, certificate: Calibration, stars: StarsDto?, layoutCount: Int?) {
        val sample = certificate.sample
        if (sample.games < MIN_SAMPLE_GAMES) error(file, "$.sample.games", "at least $MIN_SAMPLE_GAMES games are required")
        if (sample.wins !in 0..sample.games) error(file, "$.sample.wins", "wins must be between 0 and games")
        val q = sample.shots
        if (!(q.p10 <= q.p35 && q.p35 <= q.p50 && q.p50 <= q.p70 && q.p70 <= q.p90)) error(file, "$.sample.shots", "quantiles must not decrease")
        if (certificate.thresholds != stars) error(file, "$.thresholds", "thresholds differ from the variant stars")
        val witness = certificate.witness
        if (!HEX_SEED.matches(witness.seedHex)) error(file, "$.witness.seedHex", "seed must be 1 to 16 hex digits")
        if (witness.firstPlayer !in 0..1) error(file, "$.witness.firstPlayer", "firstPlayer must be 0 or 1")
        if (layoutCount != null && witness.enemyLayout !in 0 until layoutCount) {
            error(file, "$.witness.enemyLayout", "enemyLayout ${witness.enemyLayout} is not in the pool")
        }
        if (!HEX_HASH.matches(witness.finalHash)) error(file, "$.witness.finalHash", "finalHash must be 16 lower-case hex digits")
        if (witness.actions.isEmpty()) error(file, "$.witness.actions", "a witness needs at least one action")
        witness.actions.forEachIndexed { i, text ->
            if (runCatching { BattleCodec.decodeAction(text) }.isFailure) {
                error(file, "$.witness.actions[$i]", "witness action is not a BattleCodec action")
            }
        }
    }

    private fun checkVersions(file: String, engine: Int, content: Int, catalog: CatalogDto, ai: Int? = null) {
        if (engine != catalog.engineVersion) error(file, "$.engineVersion", "engineVersion $engine differs from the catalog")
        if (content != catalog.contentVersion) error(file, "$.contentVersion", "contentVersion $content differs from the catalog")
        if (ai != null && ai != catalog.aiVersion) error(file, "$.aiVersion", "aiVersion $ai differs from the catalog")
    }

    /** Daily hunts: rules, layouts, calibration and certificate, all inline in `daily/hunts.json`. */
    private fun checkDailyHunts(catalog: CatalogDto) {
        val loaded = set.dailyHunts
        if (loaded == null) {
            strictError("daily/hunts.json", "$", "daily hunts file is missing")
            return
        }
        val file = loaded.file
        duplicates(loaded.value.hunts.map { it.id }).forEach { error(file, "$.hunts", "duplicate hunt $it") }
        loaded.value.hunts.forEachIndexed { i, hunt -> checkDailyHunt(catalog, file, "$.hunts[$i]", hunt) }
        if (strict) {
            for (difficulty in Difficulty.entries) {
                val count = loaded.value.hunts.count { it.difficulty == difficulty }
                if (count != STRICT_DAILY_PER_KIND_AND_DIFFICULTY) {
                    error(file, "$.hunts", "strict: $count hunts for ${difficulty.name}, expected $STRICT_DAILY_PER_KIND_AND_DIFFICULTY")
                }
            }
        }
    }

    private fun checkDailyHunt(catalog: CatalogDto, file: String, base: String, hunt: DailyHuntDto) {
        val terrainId = hunt.rules.terrain
        if (terrainId == null) {
            error(file, "$base.rules.terrain", "a daily hunt needs its own terrain")
            return
        }
        val variant = hunt.asVariant()
        val rules: RuleSet = try {
            variant.toRuleSet(mapOf(terrainId to TerrainDto(terrainId, hunt.terrain).toTerrains()))
        } catch (e: IllegalArgumentException) {
            error(file, "$base.rules", e.message ?: "the rules cannot be built")
            return
        }
        if (strict && hunt.layouts.size != EXPECTED_LAYOUTS.getValue(hunt.difficulty)) {
            error(file, "$base.layouts", "strict: this hunt needs ${EXPECTED_LAYOUTS.getValue(hunt.difficulty)} layouts, found ${hunt.layouts.size}")
        }
        validateLayouts(Loaded(file, hunt.layoutPool()), rules).forEach { add(it) }
        if (!hunt.calibrated) {
            strictError(file, "$base.calibrated", "UNCALIBRATED: ${hunt.id} ${hunt.difficulty.name} waits for calibration (stars, shotBudget, certificate)")
            if (hunt.certificate != null) error(file, "$base.certificate", "an uncalibrated hunt has no certificate")
            return
        }
        val stars = hunt.stars
        if (stars == null) {
            error(file, "$base.stars", "a calibrated hunt needs stars")
            return
        }
        if (stars.three < 1 || stars.two < stars.three) error(file, "$base.stars", "stars must satisfy 1 <= three <= two")
        val budget = hunt.shotBudget
        if (budget == null || budget < 1) error(file, "$base.shotBudget", "HUNT needs a shotBudget of at least 1")
        val certificate = hunt.certificate
        if (certificate == null) {
            error(file, "$base.certificate", "a calibrated hunt needs its certificate")
            return
        }
        if (certificate.id != hunt.id) error(file, "$base.certificate.id", "certificate id ${certificate.id} does not match ${hunt.id}")
        if (certificate.missionId != hunt.id) error(file, "$base.certificate.missionId", "certificate names another hunt")
        if (certificate.difficulty != hunt.difficulty) error(file, "$base.certificate.difficulty", "certificate names another difficulty")
        if (certificate.variantHash != canonicalHash(VariantDto.serializer(), variant)) {
            error(file, "$base.certificate.variantHash", "variant changed since the certificate was made")
        }
        if (certificate.poolHash != canonicalHash(LayoutPoolDto.serializer(), hunt.layoutPool())) {
            error(file, "$base.certificate.poolHash", "layouts changed since the certificate was made")
        }
        checkVersions(file, certificate.engineVersion, certificate.contentVersion, catalog, certificate.aiVersion)
        checkSampleAndWitness(file, certificate, stars, hunt.layouts.size)
    }

    private fun checkExtras() {
        val daily = set.daily
        if (daily == null) {
            strictError("daily/pool.json", "$", "daily pool is missing")
        } else {
            val pool = daily.value
            if (pool.version < 1) error(daily.file, "$.version", "version must be at least 1")
            duplicates(pool.entries.map { it.id }).forEach { error(daily.file, "$.entries", "duplicate entry $it") }
            pool.entries.forEachIndexed { i, entry ->
                val path = "$.entries[$i]"
                when (entry.kind) {
                    DailyKind.PUZZLE ->
                        if (entry.ref !in set.puzzles) error(daily.file, "$path.ref", "unknown puzzle ${entry.ref}")
                    DailyKind.HUNT ->
                        if (set.dailyHunts?.value?.hunts?.any { it.id == entry.ref } != true) {
                            error(daily.file, "$path.ref", "unknown daily hunt ${entry.ref}")
                        }
                }
            }
            if (strict) {
                for (kind in DailyKind.entries) for (difficulty in Difficulty.entries) {
                    val count = pool.entries.count { it.kind == kind && it.difficulty == difficulty }
                    if (count != STRICT_DAILY_PER_KIND_AND_DIFFICULTY) {
                        error(daily.file, "$.entries", "strict: $count $kind entries for ${difficulty.name}, expected $STRICT_DAILY_PER_KIND_AND_DIFFICULTY")
                    }
                }
            }
        }

        for ((name, pack) in set.endless) {
            val expectedSize = name.removePrefix("p").toIntOrNull()
            if (expectedSize == null || pack.value.size != expectedSize) error(pack.file, "$.size", "size ${pack.value.size} does not match $name")
            if (pack.value.size !in listOf(6, 8, 10)) error(pack.file, "$.size", "endless sizes are 6, 8 and 10")
            pack.value.puzzles.forEach { id ->
                val puzzle = set.puzzles[id]
                if (puzzle == null) error(pack.file, "$.puzzles", "unknown puzzle $id")
                else if (puzzle.value.width != pack.value.size || puzzle.value.height != pack.value.size) error(pack.file, "$.puzzles", "puzzle $id has another size")
            }
            if (strict && pack.value.puzzles.size != STRICT_ENDLESS_PER_PACK) {
                error(pack.file, "$.puzzles", "strict: $STRICT_ENDLESS_PER_PACK puzzles per pack, found ${pack.value.puzzles.size}")
            }
        }
        if (strict) {
            for (name in listOf("p06", "p08", "p10")) if (name !in set.endless) error("endless/$name.json", "$", "endless pack is missing")
        }

        val patrol = set.patrol
        if (patrol == null) {
            strictError("patrol/config.json", "$", "patrol config is missing")
        } else {
            val config = patrol.value
            if (strict && config.stages.size != STRICT_PATROL_STAGES) {
                error(patrol.file, "$.stages", "strict: $STRICT_PATROL_STAGES stages required, found ${config.stages.size}")
            }
            config.stages.forEachIndexed { i, stage ->
                val path = "$.stages[$i]"
                if (stage.ordinal != i + 1) error(patrol.file, "$path.ordinal", "expected ${i + 1}")
                if (stage.width !in RuleSet.MIN_SIDE..RuleSet.MAX_SIDE || stage.height !in RuleSet.MIN_SIDE..RuleSet.MAX_SIDE) {
                    error(patrol.file, path, "grid sides must be ${RuleSet.MIN_SIDE}..${RuleSet.MAX_SIDE}")
                }
                if (runCatching { fleetPreset(stage.fleet) }.isFailure) error(patrol.file, "$path.fleet", "unknown fleet ${stage.fleet}")
                if (stage.reloadBudget < 1) error(patrol.file, "$path.reloadBudget", "reloadBudget must be at least 1")
            }
        }
    }

    private fun duplicates(values: List<String>): List<String> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    private companion object {
        val MISSION_ID_NUMBERS = Regex("s(\\d{2})m(\\d{3})")
        val HEX_SEED = Regex("[0-9a-fA-F]{1,16}")
        val HEX_HASH = Regex("[0-9a-f]{16}")
    }
}

/** Rule features of a variant, used to check and to write the mission tags. */
fun ruleFeaturesOf(rules: RuleSet): Set<MissionTag> = buildSet {
    if (rules.terrain.any { it == Terrain.ISLAND }) add(MissionTag.ISLANDS)
    if (rules.terrain.any { it == Terrain.REEF }) add(MissionTag.REEFS)
    if (rules.mines.any { it.isNotEmpty() }) add(MissionTag.MINES)
    if (rules.shotClockSeconds != null) add(MissionTag.TIMED)
    if (rules.salvo) add(MissionTag.SALVO)
    if (rules.diving != null) add(MissionTag.DIVING)
    if (rules.fog != null) add(MissionTag.FOG)
    if (rules.movingShips) add(MissionTag.MOVING)
    if (rules.fleet.name == "SCHOOL") add(MissionTag.SCHOOL)
    if (rules.fleet.name == "LARGE") add(MissionTag.LARGE)
}
