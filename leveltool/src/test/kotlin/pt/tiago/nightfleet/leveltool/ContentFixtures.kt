package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.AdmiralDto
import pt.tiago.nightfleet.core.content.AdmiralsDto
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.CanonicalJson
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.ai.AI_VERSION
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyHuntDto
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.EndlessPackDto
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.PatrolStageDto
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.puzzle.PuzzleHasher
import pt.tiago.nightfleet.core.content.QuantilesDto
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.SampleDto
import pt.tiago.nightfleet.core.content.SeaDto
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.TerrainsDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.WitnessDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.sha256Hex
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import java.io.File

/** Versions the fixtures declare. */
private const val CONTENT_VERSION = 1

/** Writes a canonical JSON file under [root], creating directories. */
internal fun <T> writeContent(root: File, relative: String, serializer: KSerializer<T>, value: T) {
    val file = File(root, relative)
    file.parentFile.mkdirs()
    file.writeBytes(CanonicalJson.bytes(serializer, value))
}

/**
 * Recomputes `catalog.files` from the files on disk and rewrites `catalog.json`. Tests call it after
 * a deliberate edit, so that only the rule under test fails.
 */
internal fun seal(root: File) {
    val catalogFile = File(root, "catalog.json")
    val catalog = ContentJson.json.decodeFromString(CatalogDto.serializer(), catalogFile.readText())
    val files = root.walkTopDown()
        .filter { it.isFile }
        .map { relativePath(root, it) }
        .filter { isPublishedPath(it) }
        .associateWith { sha256Hex(File(root, it).readBytes()) }
        .toSortedMap()
    catalogFile.writeBytes(CanonicalJson.bytes(CatalogDto.serializer(), catalog.copy(files = files)))
}

/** Replaces the first occurrence of [old] in a content file and seals the catalog again. */
internal fun mutate(root: File, relative: String, old: String, new: String) {
    val file = File(root, relative)
    val text = file.readText()
    require(text.contains(old)) { "fixture changed: '$old' not found in $relative" }
    file.writeText(text.replaceFirst(old, new))
    seal(root)
}

/** The 3-ship TUTORIAL fleet on an 8 by 8 sea with one island: the smallest real battle. */
internal object Sample {
    const val SEA = "s01"
    const val POOL = "s01-tutorial"
    const val TERRAIN = "islands-8"
    const val PUZZLE = "p-s01m003"
    const val DUEL = "s01m001"
    const val HUNT = "s01m002"
    const val PUZZLE_MISSION = "s01m003"

    val terrain = TerrainDto(
        id = TERRAIN,
        rows = listOf(
            "........",
            "........",
            "........",
            "...#....",
            "........",
            "........",
            "........",
            "........",
        ),
    )

    val proven: ProvenPuzzle = Puzzles.prove(6, PuzzleGrade.EASY, 42L).let {
        ProvenPuzzle(it.definition.copy(id = PUZZLE), it.certificate)
    }

    private val tutorialRules = RulesDto(width = 8, height = 8, fleet = "TUTORIAL", terrain = TERRAIN)

    /** Layouts for the pool: certified by the same placer the engine uses for hidden fleets. */
    fun layouts(): List<List<PlacementDto>> {
        val rules = tutorialRules.toRuleSet(mapOf(TERRAIN to terrain.toTerrains()))
        val board = rules.boardFor(1)
        val out = mutableListOf<List<PlacementDto>>()
        for (seed in 1L..4L) {
            val result = RandomPlacer.place(board, rules.fleet, SplitMix64(seed))
            if (result is PlacementResult.Success) {
                out += result.placements.map {
                    PlacementDto(it.shipClass, it.origin.row, it.origin.col, it.rotationIndex)
                }
            }
        }
        check(out.size == 4) { "could not place the sample layouts" }
        return out
    }

    fun pool(): LayoutPoolDto = LayoutPoolDto(
        id = POOL,
        fleet = "TUTORIAL",
        terrain = TERRAIN,
        terrainHash = canonicalHash(TerrainDto.serializer(), terrain),
        layouts = layouts(),
    )

    fun admiral() = AdmiralDto(
        id = "sample-admiral",
        number = 1,
        nameKey = "admiral.sample",
        placementStyle = "SCATTERED",
        search = "RANDOM",
        weaponPolicy = "NONE",
    )

    fun sea() = SeaDto(
        number = 1,
        nameKey = "sea.sample",
        admiralId = "sample-admiral",
        weather = "calm",
        musicTrack = "music_sea01",
        introCards = emptyList(),
    )

    private fun ai(d: Difficulty) = AiConfigDto(tier = d, blunder = when (d) {
        Difficulty.EASY -> 420
        Difficulty.MEDIUM -> 260
        Difficulty.HARD -> 120
    })

    private fun variants(baseSeed: Long, rules: RulesDto, layoutPool: String?, puzzleId: String?, firstPlayer: FirstPlayer, stars: StarsDto, budget: Int?) =
        Difficulty.entries.withIndex().associate { (i, d) ->
            d to VariantDto(
                levelSeed = baseSeed + i,
                rules = rules,
                ai = ai(d),
                layoutPool = layoutPool,
                puzzleId = puzzleId,
                firstPlayer = firstPlayer,
                stars = stars,
                shotBudget = budget,
            )
        }

    fun duel(): MissionDto = MissionDto(
        id = DUEL,
        sea = 1,
        ordinal = 1,
        type = MissionKind.DUEL,
        family = "open-tutorial",
        tags = listOf(MissionTag.ISLANDS),
        tutorial = "tutorial.shoot",
        variants = variants(811092341L, tutorialRules, POOL, null, FirstPlayer.PLAYER, StarsDto(two = 14, three = 9), null),
    )

    fun hunt(): MissionDto = MissionDto(
        id = HUNT,
        sea = 1,
        ordinal = 2,
        type = MissionKind.HUNT,
        family = "hunt-tutorial",
        tags = listOf(MissionTag.ISLANDS),
        tutorial = null,
        variants = variants(811093341L, tutorialRules, POOL, null, FirstPlayer.PLAYER, StarsDto(two = 20, three = 12), 64),
    )

    fun puzzleMission(): MissionDto = MissionDto(
        id = PUZZLE_MISSION,
        sea = 1,
        ordinal = 3,
        type = MissionKind.PUZZLE,
        family = "puzzle-6-t1",
        tags = if (proven.definition.islands.isNotEmpty()) listOf(MissionTag.ISLANDS) else emptyList(),
        tutorial = null,
        variants = variants(811094341L, RulesDto(width = 8, height = 8, fleet = "TUTORIAL"), null, PUZZLE, FirstPlayer.PLAYER, StarsDto(two = 2, three = 1), null),
    )
}

/**
 * Plays the first winning game the player can find for [rules] against [enemy], scanning squares in
 * row-major order. The result is the witness (fire-only, so the M1 engine replays it).
 */
internal fun buildWitness(
    rules: RuleSet,
    type: MissionType,
    enemy: List<List<PlacementDto>>,
    budget: Int?,
    levelSeed: Long,
): WitnessDto {
    for (enemyIndex in enemy.indices) {
        for (attempt in 1..60) {
            val seed = SeedStreams.attemptSeed(levelSeed, attempt)
            val played = simulate(rules, type, enemy[enemyIndex].map { it.toPlacement() }, seed, budget) ?: continue
            return WitnessDto(
                seedHex = "%016x".format(seed),
                firstPlayer = 0,
                enemyLayout = enemyIndex,
                actions = played.first,
                finalHash = "%016x".format(StateHasher.hash(played.second)),
            )
        }
    }
    error("no winning witness found for levelSeed $levelSeed")
}


private fun simulate(
    rules: RuleSet,
    type: MissionType,
    enemy: List<pt.tiago.nightfleet.core.fleet.Placement>,
    seed: Long,
    budget: Int?,
): Pair<List<String>, BattleState>? {
    val created = BattleSetup.create(rules, type, listOf(null, enemy), 0, seed, budget)
    var state = (created as? SetupResult.Created)?.state ?: return null
    val next = IntArray(2)
    val actions = mutableListOf<String>()
    while (state.result == null && actions.size < 4000) {
        val side = state.current
        val defender = state.sides[1 - side]
        var index = next[side]
        while (index < defender.board.size &&
            (defender.board.terrainAt(index) != Terrain.WATER || defender.incoming.latest(side, index) != null)
        ) {
            index++
        }
        if (index >= defender.board.size) break
        next[side] = index + 1
        val cell = defender.board.cell(index)
        state = Engine.apply(state, Action.Fire(cell)).state
        actions += BattleCodec.encodeAction(Action.Fire(cell))
    }
    return if (state.result?.winner == 0) actions to state else null
}

private fun sample(difficultyStats: Int) = SampleDto(
    games = 64,
    wins = 60,
    shots = QuantilesDto(p10 = 12, p35 = 14 + difficultyStats, p50 = 16, p70 = 20, p90 = 26),
)

/**
 * Writes the sample catalog of the leveltool tests: one sea, three missions (DUEL, HUNT and a PUZZLE
 * placeholder) with three variants each, one layout pool and one puzzle. It is a fixture, not
 * campaign content: its sample numbers are not calibration results.
 */
internal fun writeSampleCatalog(root: File) {
    root.mkdirs()
    writeContent(root, "admirals.json", AdmiralsDto.serializer(), AdmiralsDto(listOf(Sample.admiral())))
    writeContent(root, "terrains.json", TerrainsDto.serializer(), TerrainsDto(listOf(Sample.terrain)))
    writeContent(root, "seas/${Sample.SEA}.json", SeaDto.serializer(), Sample.sea())
    val duel = Sample.duel()
    val hunt = Sample.hunt()
    val puzzle = Sample.puzzleMission()
    writeContent(root, "missions/${Sample.SEA}/${duel.id}.json", MissionDto.serializer(), duel)
    writeContent(root, "missions/${Sample.SEA}/${hunt.id}.json", MissionDto.serializer(), hunt)
    writeContent(root, "missions/${Sample.SEA}/${puzzle.id}.json", MissionDto.serializer(), puzzle)
    val pool = Sample.pool()
    writeContent(root, "layouts/${Sample.POOL}.json", LayoutPoolDto.serializer(), pool)
    writeContent(root, "puzzles/${Sample.PUZZLE}.json", PuzzleDefinition.serializer(), Sample.proven.definition)

    for (mission in listOf(duel, hunt)) {
        val ruleSet = mission.variants.getValue(Difficulty.EASY).rules.toRuleSet(mapOf(Sample.TERRAIN to Sample.terrain.toTerrains()))
        val type = if (mission.type == MissionKind.HUNT) MissionType.HUNT else MissionType.DUEL
        val witness = buildWitness(ruleSet, type, pool.layouts, mission.variants.getValue(Difficulty.EASY).shotBudget, mission.variants.getValue(Difficulty.EASY).levelSeed)
        for (difficulty in Difficulty.entries) {
            val variant = mission.variants.getValue(difficulty)
            val calibration = CertificateDto.Calibration(
                id = certificateId(mission.id, difficulty),
                missionId = mission.id,
                difficulty = difficulty,
                variantHash = canonicalHash(VariantDto.serializer(), variant),
                poolHash = canonicalHash(LayoutPoolDto.serializer(), pool),
                engineVersion = ENGINE_VERSION,
                aiVersion = AI_VERSION,
                contentVersion = CONTENT_VERSION,
                sample = sample(difficulty.ordinal),
                thresholds = variant.stars!!,
                witness = witness,
            )
            writeContent(root, "certificates/${calibration.id}.json", CertificateDto.serializer(), calibration)
        }
    }
    val puzzleHash = PuzzleHasher.hex(Sample.proven.definition)
    for (difficulty in Difficulty.entries) {
        val proof = Puzzles.proof(certificateId(puzzle.id, difficulty), Sample.proven)
        check(proof.puzzleHash == puzzleHash)
        writeContent(root, "certificates/${proof.id}.json", CertificateDto.serializer(), proof)
    }
    writeCatalog(root, listOf(Sample.SEA), listOf(duel.id, hunt.id, puzzle.id))
    seal(root)
}

private fun writeCatalog(root: File, seas: List<String>, missions: List<String>) {
    writeContent(
        root,
        "catalog.json",
        CatalogDto.serializer(),
        CatalogDto(
            contentVersion = CONTENT_VERSION,
            engineVersion = ENGINE_VERSION,
            aiVersion = AI_VERSION,
            seas = seas,
            missions = missions,
            files = emptyMap(),
        ),
    )
}

/**
 * Writes a complete strict-valid campaign shape: 12 seas of 50 missions (49 duels and a boss),
 * 12 admirals, 36 layout pools with the exact layout counts, 1800 calibration certificates, the daily,
 * endless and patrol files. Certificates carry a placeholder witness: `validate` and `export-assets`
 * check its shape, `verify-certificates` replays only real witnesses.
 */
internal fun writeSyntheticCampaign(root: File) {
    root.mkdirs()
    val admirals = (1..12).map {
        AdmiralDto(id = "syn-admiral-$it", number = it, nameKey = "admiral.$it", placementStyle = "SCATTERED", search = "RANDOM", weaponPolicy = "NONE")
    }
    writeContent(root, "admirals.json", AdmiralsDto.serializer(), AdmiralsDto(admirals))
    writeContent(root, "terrains.json", TerrainsDto.serializer(), TerrainsDto(emptyList()))
    val classic = RulesDto(width = 10, height = 10, fleet = "CLASSIC")
    val seaIds = (1..12).map { "s%02d".format(it) }
    val missionIds = mutableListOf<String>()
    for (sea in 1..12) {
        val seaId = seaIds[sea - 1]
        writeContent(
            root,
            "seas/$seaId.json",
            SeaDto.serializer(),
            SeaDto(sea, "sea.$sea", "syn-admiral-$sea", "calm", "music_sea%02d".format(sea), emptyList()),
        )
        val board = classic.toRuleSet().boardFor(1)
        val layouts = (0 until 32).mapNotNull { k ->
            (RandomPlacer.place(board, classic.toRuleSet().fleet, SplitMix64(sea * 1000L + k)) as? PlacementResult.Success)
                ?.placements?.map { PlacementDto(it.shipClass, it.origin.row, it.origin.col, it.rotationIndex) }
        }
        check(layouts.size == 32)
        for (difficulty in Difficulty.entries) {
            val size = EXPECTED_LAYOUTS.getValue(difficulty)
            val pool = LayoutPoolDto(poolId(seaId, difficulty), "CLASSIC", null, null, layouts.take(size))
            writeContent(root, "layouts/${pool.id}.json", LayoutPoolDto.serializer(), pool)
        }
        for (difficulty in Difficulty.entries) {
            val pool = LayoutPoolDto(bossPoolId(seaId, difficulty), "CLASSIC", null, null, layouts.take(BOSS_LAYOUTS))
            writeContent(root, "layouts/${pool.id}.json", LayoutPoolDto.serializer(), pool)
        }
        for (position in 1..STRICT_MISSIONS_PER_SEA) {
            val ordinal = (sea - 1) * STRICT_MISSIONS_PER_SEA + position
            val id = "s%02dm%03d".format(sea, ordinal)
            missionIds += id
            val type = if (position == STRICT_MISSIONS_PER_SEA) MissionKind.BOSS else MissionKind.DUEL
            val variants = Difficulty.entries.withIndex().associate { (i, d) ->
                d to VariantDto(
                    levelSeed = 1000L + ordinal * 3 + i,
                    rules = classic,
                    ai = AiConfigDto(tier = d, blunder = 100),
                    layoutPool = if (type == MissionKind.BOSS) bossPoolId(seaId, d) else poolId(seaId, d),
                    firstPlayer = FirstPlayer.PLAYER,
                    stars = StarsDto(two = 60, three = 40),
                )
            }
            val mission = MissionDto(id, sea, ordinal, type, "syn-$ordinal", emptyList(), null, variants)
            writeContent(root, "missions/$seaId/$id.json", MissionDto.serializer(), mission)
            for (difficulty in Difficulty.entries) {
                val variant = variants.getValue(difficulty)
                val cert = CertificateDto.Calibration(
                    id = certificateId(id, difficulty),
                    missionId = id,
                    difficulty = difficulty,
                    variantHash = canonicalHash(VariantDto.serializer(), variant),
                    poolHash = canonicalHash(
                        LayoutPoolDto.serializer(),
                        LayoutPoolDto(
                            variant.layoutPool!!,
                            "CLASSIC",
                            null,
                            null,
                            layouts.take(if (type == MissionKind.BOSS) BOSS_LAYOUTS else EXPECTED_LAYOUTS.getValue(difficulty)),
                        ),
                    ),
                    engineVersion = ENGINE_VERSION,
                    aiVersion = AI_VERSION,
                    contentVersion = CONTENT_VERSION,
                    sample = sample(0),
                    thresholds = variant.stars!!,
                    witness = WitnessDto("1", 0, 0, listOf(PLACEHOLDER_ACTION), "0000000000000000"),
                )
                writeContent(root, "certificates/${cert.id}.json", CertificateDto.serializer(), cert)
            }
        }
    }
    val puzzles = listOf(6, 8, 10).map { size ->
        Puzzles.prove(size, PuzzleGrade.EASY, 100L + size).definition.copy(id = "syn-p%02d".format(size))
    }
    for (puzzle in puzzles) writeContent(root, "puzzles/${puzzle.id}.json", PuzzleDefinition.serializer(), puzzle)
    for ((size, puzzleId) in listOf(6 to "syn-p06", 8 to "syn-p08", 10 to "syn-p10")) {
        val file = "p%02d".format(size)
        writeContent(root, "endless/$file.json", EndlessPackDto.serializer(), EndlessPackDto(size, List(200) { puzzleId }))
    }
    writeSyntheticDailyHunts(root)
    val daily = buildList {
        for (kind in DailyKind.entries) for (difficulty in Difficulty.entries) for (i in 0 until 400) {
            add(
                DailyEntryDto(
                    id = "$kind-$difficulty-$i",
                    kind = kind,
                    difficulty = difficulty,
                    ref = if (kind == DailyKind.PUZZLE) "syn-p06" else syntheticHuntId(difficulty, i),
                ),
            )
        }
    }
    writeContent(root, "daily/pool.json", DailyPoolDto.serializer(), DailyPoolDto(1, daily))
    val stages = (1..STRICT_PATROL_STAGES).map {
        PatrolStageDto(it, 8, 8, "SMALL", null, 0, 10)
    }
    writeContent(root, "patrol/config.json", PatrolConfigDto.serializer(), PatrolConfigDto(stages, 20, 1))
    writeCatalog(root, seaIds, missionIds)
    seal(root)
}


/** Pool id of one sea and difficulty, e.g. `s01-E`. */
private fun poolId(seaId: String, difficulty: Difficulty): String = "$seaId-" + certificateId("x", difficulty).last()

private fun bossPoolId(seaId: String, difficulty: Difficulty): String = "$seaId-B" + certificateId("x", difficulty).last()

/** A syntactically valid action for placeholder witnesses (the shape is checked, not the replay). */
private val PLACEHOLDER_ACTION = BattleCodec.encodeAction(Action.Fire(Cell(0, 0)))


/** Id of the synthetic daily hunt number [i] of [difficulty]. */
internal fun syntheticHuntId(difficulty: Difficulty, i: Int): String = "syn-hunt-${difficulty.name.lowercase()}-%03d".format(i)

/**
 * `daily/hunts.json` of the synthetic campaign: [STRICT_DAILY_PER_KIND_AND_DIFFICULTY] calibrated hunts per
 * difficulty on an open board, with layouts drawn once per difficulty. Each certificate carries the hashes the
 * validator checks; its witness is well formed but not replayed here (the replay is covered elsewhere).
 */
internal fun writeSyntheticDailyHunts(root: File) {
    val terrainId = "syn-hunt-open"
    val rows = List(10) { ".".repeat(10) }
    val rules = RulesDto(terrain = terrainId)
    val board = rules.toRuleSet(mapOf(terrainId to TerrainDto(terrainId, rows).toTerrains())).boardFor(1)
    val layouts = Difficulty.entries.associateWith { difficulty ->
        LayoutPools.layouts(board, fleetPreset("CLASSIC"), PlacementStyle.BALANCED, EXPECTED_LAYOUTS.getValue(difficulty), 1_234L)
            .map { LayoutPools.toDto(it) }
    }
    val hunts = buildList {
        for (difficulty in Difficulty.entries) for (i in 0 until STRICT_DAILY_PER_KIND_AND_DIFFICULTY) {
            val id = syntheticHuntId(difficulty, i)
            val stars = StarsDto(two = 30, three = 20)
            val base = DailyHuntDto(
                id = id,
                difficulty = difficulty,
                levelSeed = 1_000L + i,
                terrain = rows,
                rules = rules,
                layouts = layouts.getValue(difficulty),
            )
            val calibrated = base.copy(calibrated = true, stars = stars, shotBudget = 40)
            add(
                calibrated.copy(
                    certificate = CertificateDto.Calibration(
                        id = id,
                        missionId = id,
                        difficulty = difficulty,
                        variantHash = canonicalHash(VariantDto.serializer(), calibrated.asVariant()),
                        poolHash = canonicalHash(LayoutPoolDto.serializer(), base.layoutPool()),
                        engineVersion = ENGINE_VERSION,
                        aiVersion = AI_VERSION,
                        contentVersion = 1,
                        sample = SampleDto(games = 64, wins = 40, shots = QuantilesDto(p10 = 10, p35 = 20, p50 = 25, p70 = 30, p90 = 40)),
                        thresholds = stars,
                        witness = WitnessDto(
                            seedHex = "1",
                            firstPlayer = 0,
                            enemyLayout = 0,
                            actions = listOf(BattleCodec.encodeAction(Action.Fire(Cell(0, 0)))),
                            finalHash = "0000000000000001",
                        ),
                    ),
                ),
            )
        }
    }
    writeContent(root, DAILY_HUNTS_PATH, DailyHuntsDto.serializer(), DailyHuntsDto(1, hunts))
}
