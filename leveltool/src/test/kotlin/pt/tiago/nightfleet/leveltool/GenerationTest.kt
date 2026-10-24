package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.EndlessPackDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.PatrolDefaults
import pt.tiago.nightfleet.core.content.CanonicalJson
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.sha256Hex
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.puzzle.PuzzleHasher
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.content.MissionTag
import java.io.File

/** Fast and full checks of the generator. Sea 2 and sea 5 are the usual subjects. */
class GenerationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun listing(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { relativePath(root, it) to it.readBytes() }.toSortedMap()

    private fun types(missions: List<MissionOutput>): Map<MissionKind, Int> =
        missions.groupingBy { it.mission.type }.eachCount()

    private fun planned(sea: Int) = planSea(sea, DEFAULT_SEED)

    // --- determinism -------------------------------------------------------------------------

    @Test
    fun sameSeedGivesIdenticalBytesForASea() {
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        generateSea(a, 2, 1, 50, DEFAULT_SEED)
        generateSea(b, 2, 1, 50, DEFAULT_SEED)
        val la = listing(a)
        val lb = listing(b)
        assertEquals(la.keys, lb.keys)
        for ((path, bytes) in la) assertTrue(path, bytes.contentEquals(lb.getValue(path)))
    }

    @Test
    fun differentSeedChangesTheLevels() {
        val a = buildSea(1, DEFAULT_SEED).first().mission
        val b = buildSea(1, DEFAULT_SEED + 1).first().mission
        assertNotEquals(a.variants.getValue(Difficulty.EASY).levelSeed, b.variants.getValue(Difficulty.EASY).levelSeed)
    }

    @Test
    fun planIsDeterministic() {
        assertEquals(planned(7), planned(7))
    }

    @Test
    fun sealIsIdempotentAndListsEveryPublishedFileWithItsHash() {
        val root = tmp.newFolder("sealed")
        generateSea(root, 1, 1, 50, DEFAULT_SEED)
        sealContent(root)
        val first = File(root, "catalog.json").readBytes()
        sealContent(root)
        assertTrue(first.contentEquals(File(root, "catalog.json").readBytes()))
        val catalog = ContentJson.json.decodeFromString(CatalogDto.serializer(), String(first, Charsets.UTF_8))
        val published = root.walkTopDown().filter { it.isFile }.map { relativePath(root, it) }.filter { isPublishedPath(it) }.toSet()
        assertEquals(published, catalog.files.keys)
        for ((path, sha) in catalog.files) assertEquals(path, sha, sha256Hex(File(root, path).readBytes()))
    }

    // --- counts -----------------------------------------------------------------

    @Test
    fun typeCountsMatchSection2ForAllTwelveSeas() {
        for (sea in 1..SEAS_COUNT) {
            val missions = planned(sea)
            assertEquals("sea $sea", MISSIONS_PER_SEA, missions.size)
            val counts = missions.groupingBy { it.kind }.eachCount()
            val target = TYPE_COUNTS.getValue(sea)
            assertEquals("sea $sea duel", target.duel, counts[MissionKind.DUEL] ?: 0)
            assertEquals("sea $sea puzzle", target.puzzle, counts[MissionKind.PUZZLE] ?: 0)
            assertEquals("sea $sea hunt", target.hunt, counts[MissionKind.HUNT] ?: 0)
            assertEquals("sea $sea convoy", target.convoy, counts[MissionKind.CONVOY] ?: 0)
            assertEquals("sea $sea boss", 1, counts[MissionKind.BOSS] ?: 0)
        }
    }

    @Test
    fun campaignTotalsAre300Duels150Puzzles90Hunts48Escorts12Bosses() {
        val all = (1..SEAS_COUNT).flatMap { planned(it) }.groupingBy { it.kind }.eachCount()
        assertEquals(300, all[MissionKind.DUEL])
        assertEquals(150, all[MissionKind.PUZZLE])
        assertEquals(90, all[MissionKind.HUNT])
        assertEquals(48, all[MissionKind.CONVOY])
        assertEquals(12, all[MissionKind.BOSS])
    }

    @Test
    fun missionIdsFollowTheCampaignOrdinals() {
        for (sea in listOf(1, 6, 12)) {
            planned(sea).forEach { p ->
                val expected = "s%02dm%03d".format(sea, (sea - 1) * 50 + p.index)
                assertEquals(expected, missionIdOf(sea, p.index))
            }
        }
    }

    // --- modifiers ---------------------------------------------------------------------------

    @Test
    fun minedMissionsFollowTheSchedule() {
        for (sea in 1..SEAS_COUNT) {
            val mined = planned(sea).count { Mech.MINES in it.mechs }
            val expected = when {
                sea < 5 -> 0
                sea == 5 -> 12
                else -> 5 + sea % 3
            }
            assertEquals("sea $sea", expected, mined)
        }
    }

    @Test
    fun minesAndTimersNeverReachTheBossOrPuzzles() {
        for (sea in 1..SEAS_COUNT) {
            val missions = planned(sea)
            assertTrue(missions.last().mechs.isEmpty())
            for (p in missions) if (p.kind == MissionKind.PUZZLE) assertTrue(p.mechs.isEmpty())
        }
    }

    @Test
    fun timedMissionsAreThreePerSeaFromSeaFourAndSkipIntroductions() {
        for (sea in 1..SEAS_COUNT) {
            val timed = planned(sea).filter { Mech.TIMED in it.mechs }
            assertEquals("sea $sea", if (sea >= 4) 3 else 0, timed.size)
            val introPositions = INTROS.getValue(sea).mapIndexed { k, _ -> INTRO_POSITIONS[k] }.toSet()
            assertTrue("sea $sea", timed.none { it.index in introPositions })
        }
    }

    @Test
    fun halfModifierShareInItsSeaAndAQuarterLater() {
        val eligible10 = planned(10).count { it.kind != MissionKind.PUZZLE && it.kind != MissionKind.BOSS }
        val salvo10 = planned(10).count { Mech.SALVO in it.mechs }
        assertTrue("sea 10 salvo $salvo10 of $eligible10", kotlin.math.abs(2 * salvo10 - eligible10) <= 2)
        val eligible12 = planned(12).count { it.kind != MissionKind.PUZZLE && it.kind != MissionKind.BOSS }
        val salvo12 = planned(12).count { Mech.SALVO in it.mechs }
        assertTrue("sea 12 salvo $salvo12 of $eligible12", salvo12 * 100 in eligible12 * 15..eligible12 * 30)
    }

    @Test
    fun fogAndMovingShareSeaElevenWithoutRepeatingEachOther() {
        val both = planned(11).count { Mech.FOG in it.mechs && Mech.MOVING in it.mechs }
        assertTrue("fog and moving overlap in $both missions", both <= 1)
    }

    @Test
    fun introducedModifiersSitOnTheirIntroductionMission() {
        assertTrue(Mech.MINES in planned(5)[0].mechs)
        assertTrue(Mech.SCHOOL in planned(6)[0].mechs)
        assertTrue(Mech.DIVING in planned(9)[0].mechs)
        assertTrue(Mech.SALVO in planned(10)[0].mechs)
        assertTrue(Mech.FOG in planned(11)[2].mechs)
        assertTrue(Mech.MOVING in planned(11)[5].mechs)
    }

    @Test
    fun weaponsStartAtTheirIntroductionPosition() {
        assertTrue(Mech.SONAR in planned(4)[0].weapons)
        assertFalse(Mech.RECON in planned(5)[1].weapons)
        assertTrue(Mech.RECON in planned(5)[2].weapons)
        assertFalse(Mech.DECOY in planned(7)[1].weapons)
        assertTrue(Mech.DECOY in planned(7)[2].weapons)
        assertTrue(Mech.TORPEDO in planned(8)[0].weapons)
        assertFalse(Mech.CROSS in planned(10)[1].weapons)
        assertTrue(Mech.CROSS in planned(10)[2].weapons)
        assertTrue(Mech.CROSS in planned(12)[0].weapons)
    }

    // --- ordering ----------------------------------------------

    @Test
    fun noMoreThanThreeEqualTypesInARowFromSeaTwo() {
        for (sea in 2..SEAS_COUNT) {
            val kinds = planned(sea).map { it.kind }
            for (i in 3 until kinds.size) {
                val run = kinds[i] == kinds[i - 1] && kinds[i - 1] == kinds[i - 2] && kinds[i - 2] == kinds[i - 3]
                assertFalse("sea $sea position ${i + 1}", run)
            }
        }
    }

    @Test
    fun noPuzzleNextToPuzzleInSeasTwoToFive() {
        for (sea in 2..5) {
            val kinds = planned(sea).map { it.kind }
            for (i in 1 until kinds.size) {
                assertFalse("sea $sea position ${i + 1}", kinds[i] == MissionKind.PUZZLE && kinds[i - 1] == MissionKind.PUZZLE)
            }
        }
    }

    @Test
    fun tutorialsOfSeaOneAreTenGuidedMissionsWithTheTrainingShotAsAHunt() {
        val first = planned(1)
        for (i in 0 until 10) assertEquals(TUTORIAL_KEYS[i], first[i].tutorial)
        assertEquals(MissionKind.HUNT, first[7].kind)
        for (i in listOf(0, 1, 2, 3, 4, 5, 6, 8, 9)) assertEquals(MissionKind.DUEL, first[i].kind)
        assertEquals(null, first[10].tutorial)
    }

    @Test
    fun introducedPuzzleAndEscortOpenTheirSeas() {
        assertEquals(MissionKind.PUZZLE, planned(2)[0].kind)
        assertEquals(MissionKind.CONVOY, planned(7)[0].kind)
    }

    // --- difficulty table ------------------------------------------------------

    @Test
    fun blunderRatesHitTheSectionFourEndpoints() {
        assertEquals(420, blunderRate(Difficulty.EASY, 0.0))
        assertEquals(160, blunderRate(Difficulty.EASY, 1.0))
        assertEquals(260, blunderRate(Difficulty.MEDIUM, 0.0))
        assertEquals(60, blunderRate(Difficulty.MEDIUM, 1.0))
        assertEquals(120, blunderRate(Difficulty.HARD, 0.0))
        assertEquals(0, blunderRate(Difficulty.HARD, 0.9))
    }

    @Test
    fun sawToothRisesInBlocksAndTheBossIsTheHighestPointOfTheSea() {
        // Sea 1 and sea 12 sit on the clamps of [0, 1], so their upper blocks are flat by design.
        for (sea in listOf(6, 11)) {
            for (block in 0 until 4) {
                val peak = intensity(sea, block * 10 + 10)
                val start = intensity(sea, block * 10 + 1)
                assertTrue("sea $sea block $block rises", peak > start)
                if (block > 0) assertTrue("sea $sea block $block starts below the previous peak", start < intensity(sea, block * 10))
            }
            val boss = intensity(sea, MISSIONS_PER_SEA)
            for (index in 1 until MISSIONS_PER_SEA) assertTrue(boss >= intensity(sea, index))
        }
    }

    @Test
    fun intensityStaysInsideZeroAndOne() {
        for (sea in 1..SEAS_COUNT) for (index in 1..MISSIONS_PER_SEA) {
            val value = intensity(sea, index)
            assertTrue("sea $sea index $index is $value", value in 0.0..1.0)
        }
    }

    @Test
    fun easyGetsExtraChargesAndHardNeedsAStreakOfFour() {
        val sonar = buildSea(4, DEFAULT_SEED).first { it.mission.type == MissionKind.DUEL }.mission
        val easy = sonar.variants.getValue(Difficulty.EASY).weapons.getValue("SONAR")
        val hard = sonar.variants.getValue(Difficulty.HARD).weapons.getValue("SONAR")
        assertEquals(2, easy.initial)
        assertEquals(1, hard.initial)
        assertEquals(4, hard.earn.single().n)
        assertEquals(3, sonar.variants.getValue(Difficulty.MEDIUM).weapons.getValue("SONAR").earn.single().n)
    }

    // --- terrain -------------------------------------------------------------

    @Test
    fun terrainPatternsRespectTheirCountsAndLeaveNoIsolatedWater() {
        val terrains = buildSea(3, DEFAULT_SEED).mapNotNull { it.terrain }
        assertTrue(terrains.isNotEmpty())
        for (terrain in terrains) {
            val side = terrain.rows.size
            val solid = terrain.rows.sumOf { row -> row.count { it != '.' } }
            assertTrue("${terrain.id} has $solid cells on ${side}x$side", solid in TerrainGen.countRange(side))
            assertFalse(terrain.id, TerrainGen.hasIsolatedWater(terrain.rows))
        }
    }

    @Test
    fun symmetricDuplicatesWithinASeaAreNotGenerated() {
        for (sea in listOf(4, 9)) {
            val keys = buildSea(sea, DEFAULT_SEED).mapNotNull { it.terrain }.map { terrainCanonicalKey(it.rows) }
            assertEquals("sea $sea", keys.size, keys.toSet().size)
        }
    }

    @Test
    fun aPatternAndItsRotationShareTheirCanonicalKey() {
        val rows = listOf("#.........", "##........", "..........", "..........", "..........",
            "..........", "..........", "..........", "..........", "........#.")
        val rotated = (0 until 10).map { r -> (0 until 10).map { c -> rows[9 - c][r] }.joinToString("") }
        assertEquals(terrainCanonicalKey(rows), terrainCanonicalKey(rotated))
        val other = rows.toMutableList().also { it[5] = "...#......" }
        assertNotEquals(terrainCanonicalKey(rows), terrainCanonicalKey(other))
    }

    @Test
    fun theSymmetryGroupHasEightDistinctMaps() {
        val images = (0 until Symmetry.COUNT).map { s -> Symmetry.apply(s, 0, 1, 10) to Symmetry.apply(s, 2, 5, 10) }
        assertEquals(8, images.toSet().size)
    }

    @Test
    fun terrainGenerationSkipsAUsedPattern() {
        val rng = SplitMix64(7)
        val used = HashSet<String>()
        val first = TerrainGen.generate(10, TerrainKind.SCATTER, fleetPreset("CLASSIC"), rng, used)!!
        val second = TerrainGen.generate(10, TerrainKind.SCATTER, fleetPreset("CLASSIC"), rng, used)!!
        assertNotEquals(terrainCanonicalKey(first), terrainCanonicalKey(second))
        assertEquals(2, used.size)
    }

    // --- layout pools -----------------------------------------------------------

    @Test
    fun layoutCountsFollowTheDifficultyAndTheBossHasThree() {
        val built = buildSea(3, DEFAULT_SEED)
        val duel = built.first { it.mission.type == MissionKind.DUEL }
        assertEquals(16, duel.pools.single { it.id.endsWith("-E") }.layouts.size)
        assertEquals(24, duel.pools.single { it.id.endsWith("-M") }.layouts.size)
        assertEquals(32, duel.pools.single { it.id.endsWith("-H") }.layouts.size)
        val boss = built.last().pools
        assertEquals(3, boss.map { it.layouts.size }.distinct().single())
    }

    @Test
    fun layoutsAreLegalOnTheirBoardAndDistinctUnderTheSymmetriesOfTheTerrain() {
        for (output in buildSea(5, DEFAULT_SEED).take(12)) {
            val terrain = output.terrain ?: continue
            for (pool in output.pools) {
                val variant = output.mission.variants.values.first { it.layoutPool == pool.id }
                val ruleSet = variant.toRuleSet(mapOf(terrain.id to rowsToTerrain(terrain.rows)))
                val board = ruleSet.boardFor(1)
                val fleet = ruleSet.fleetFor(1)
                val layouts = pool.layouts.map { layout -> layout.map { it.toPlacement() } }
                for (layout in layouts) assertTrue(pool.id, PlacementValidator.validate(board, fleet, layout).isEmpty())
                val usable = Symmetry.invariant(board.terrain, board.width).filter { s ->
                    board.mines.all { m -> Symmetry.apply(s, m / board.width, m % board.width, board.width).let { (r, c) -> r * board.width + c in board.mines } }
                }
                val keys = layouts.map { layout ->
                    usable.map { s -> layout.flatMap { p -> p.cells().map { cell -> Symmetry.apply(s, cell.row, cell.col, board.width).toList() } }.sortedBy { it.toString() }.toString() }.min()
                }
                assertEquals(pool.id, keys.size, keys.toSet().size)
            }
        }
    }

    @Test
    fun minesAreWaterAndNeverNextToAShipOfAnyLayout() {
        var checked = 0
        for (sea in listOf(5, 6)) for (output in buildSea(sea, DEFAULT_SEED)) {
            val terrain = output.terrain ?: continue
            for ((difficulty, variant) in output.mission.variants) {
                val mines = variant.rules.mines[1]
                if (mines.isEmpty()) continue
                checked++
                val ruleSet = variant.toRuleSet(mapOf(terrain.id to rowsToTerrain(terrain.rows)))
                val board = ruleSet.boardFor(1)
                assertTrue(board.mines.all { board.terrainAt(it) == Terrain.WATER })
                val pool = output.pools.first { it.id == variant.layoutPool }
                for (layout in pool.layouts) {
                    val problems = PlacementValidator.validate(board, ruleSet.fleetFor(1), layout.map { it.toPlacement() })
                    assertTrue("${output.mission.id} $difficulty ${problems.map { it.code }}", problems.isEmpty())
                }
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun mineCountsStayBetweenFourAndSix() {
        val mined = buildSea(5, DEFAULT_SEED).mapNotNull { output -> output.mission.variants[Difficulty.MEDIUM]?.rules?.mines?.get(1) }
        assertTrue(mined.isNotEmpty())
        for (list in mined) if (list.isNotEmpty()) assertTrue(list.size in 4..6)
    }

    @Test
    fun layoutDrawsAreReproducible() {
        val board = Board(10, 10, List(100) { Terrain.WATER }, emptyList())
        val a = LayoutPools.layouts(board, fleetPreset("CLASSIC"), PlacementStyle.BALANCED, 8, 99L)
        val b = LayoutPools.layouts(board, fleetPreset("CLASSIC"), PlacementStyle.BALANCED, 8, 99L)
        assertEquals(a, b)
        assertEquals(8, a.toSet().size)
    }

    @Test
    fun minesDrawnBeforeTheLayoutsStayOnWater() {
        val rows = MutableList(10) { ".".repeat(10) }
        rows[4] = "..#......."
        val board = Board(10, 10, rowsToTerrain(rows), emptyList())
        val cells = LayoutPools.mines(board, 5, SplitMix64(3))
        assertEquals(5, cells.size)
        assertTrue(cells.all { board.terrainAt(it.row * 10 + it.col) == Terrain.WATER })
    }

    // --- puzzles ----------------------------------------------------------

    @Test
    fun puzzleGradesFollowTheDifficulties() {
        val puzzle = buildSea(2, DEFAULT_SEED).first { it.mission.type == MissionKind.PUZZLE }
        for ((id, certificate) in puzzle.certificates) {
            val proof = certificate as CertificateDto.Proof
            val expected = PuzzleGrade.valueOf(id.last().let { if (it == 'E') "EASY" else if (it == 'M') "MEDIUM" else "HARD" })
            assertEquals(id, expected, proof.difficulty)
        }
    }

    @Test
    fun everyProofOfSeaTwoReplaysWithTheExactSolver() {
        for (output in buildSea(2, DEFAULT_SEED)) {
            for ((_, certificate) in output.certificates) {
                val proof = certificate as CertificateDto.Proof
                val puzzle = output.puzzles.first { it.id == proof.puzzleId }
                assertEquals(proof.id, null, replayProof(puzzle, proof))
            }
        }
    }

    @Test
    fun puzzleHashIsTheHasherOfTheDefinitionAndSurvivesJson() {
        val puzzle = Puzzles.prove(6, PuzzleGrade.EASY, 11L).definition
        val text = CanonicalJson.encode(PuzzleDefinition.serializer(), puzzle)
        val back = ContentJson.json.decodeFromString(PuzzleDefinition.serializer(), text)
        assertEquals(puzzle, back)
        assertEquals(PuzzleHasher.hex(puzzle), PuzzleHasher.hex(back))
    }

    @Test
    fun puzzleGenerationIsDeterministic() {
        assertEquals(Puzzles.prove(8, PuzzleGrade.MEDIUM, 5L).definition, Puzzles.prove(8, PuzzleGrade.MEDIUM, 5L).definition)
    }

    @Test
    fun tamperedProofIsRejectedByReplay() {
        val proven = Puzzles.prove(6, PuzzleGrade.EASY, 21L)
        val proof = Puzzles.proof(proven.definition.id, proven)
        val cells = proof.solution.cells.toMutableList()
        cells[cells.indexOfFirst { it == pt.tiago.nightfleet.core.puzzle.PlayerCell.SHIP }] = pt.tiago.nightfleet.core.puzzle.PlayerCell.WATER
        assertNotEquals(null, replayProof(proven.definition, proof.copy(solution = proof.solution.copy(cells = cells))))
        assertEquals(null, replayProof(proven.definition, proof))
    }

    @Test
    fun endlessPackIsOrderedByGradeThenSteps() {
        val root = tmp.newFolder("endless")
        val pack = Puzzles.endlessPack(root, 6, 12, 77L)
        assertEquals(6, pack.size)
        val keys = pack.puzzles.map { id ->
            val proof = ContentJson.json.decodeFromString(CertificateDto.serializer(), File(root, "certificates/$id.json").readText()) as CertificateDto.Proof
            proof.grade.maxTechnique.ordinal to proof.grade.steps
        }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertTrue(File(root, "endless/p06.json").isFile)
    }

    @Test
    fun dailyPoolCarriesTheRequestedEntriesPerDifficulty() {
        val root = tmp.newFolder("daily")
        val pool: DailyPoolDto = Puzzles.dailyPool(root, 1, 2, 5L)
        assertEquals(6, pool.entries.size)
        for (difficulty in Difficulty.entries) assertEquals(2, pool.entries.count { it.difficulty == difficulty })
        for (entry in pool.entries) assertTrue(File(root, "puzzles/${entry.ref}.json").isFile)
    }

    // --- validation, strict mode and the report -------------------------------------------

    @Test
    fun aGeneratedSeaIsLenientlyValidWithoutErrors() {
        val root = tmp.newFolder("lenient")
        generateSea(root, 2, 1, 50, DEFAULT_SEED)
        val errors = validateContent(root, strict = false).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList<Problem>(), errors)
    }

    @Test
    fun strictModeReportsUncalibratedVariantsAsADistinctProblem() {
        val root = tmp.newFolder("strict")
        generateSea(root, 1, 1, 50, DEFAULT_SEED)
        val uncalibrated = validateContent(root, strict = true).filter { it.message.startsWith("UNCALIBRATED") }
        assertEquals(300, uncalibrated.size)
        assertEquals(150, uncalibrated.count { it.file.startsWith("missions/") })
        assertEquals(150, uncalibrated.count { it.file.startsWith("certificates/") })
    }

    @Test
    fun strictModeHasNoOtherProblemsThanCampaignSizeAndCalibration() {
        val root = tmp.newFolder("strict2")
        generateSea(root, 1, 1, 50, DEFAULT_SEED)
        val other = validateContent(root, strict = true)
            .filter { it.severity == Severity.ERROR && !it.message.startsWith("UNCALIBRATED") }
            .map { it.message }
        assertTrue(other.toString(), other.all { it.startsWith("strict:") || it.endsWith("is missing") })
    }

    @Test
    fun reportCountsTheMixAndTheUncalibratedVariants() {
        val root = tmp.newFolder("report")
        generateSea(root, 1, 1, 50, DEFAULT_SEED)
        val report = buildReport(root)
        assertTrue(report.contains("Variants waiting for calibration: 150"))
        assertTrue(report.contains("| s01 | 40 | 0 | 9 | 0 | 1 |"))
    }

    @Test
    fun tamperedPuzzleHashIsAnError() {
        val root = tmp.newFolder("tampered")
        generateSea(root, 2, 1, 50, DEFAULT_SEED)
        val file = root.walkTopDown().first { it.isFile && it.path.contains("certificates/s02m") && it.name.endsWith("-E.json") }
        val proof = ContentJson.json.decodeFromString(CertificateDto.serializer(), file.readText()) as CertificateDto.Proof
        file.writeBytes(CanonicalJson.bytes(CertificateDto.serializer(), proof.copy(puzzleHash = "0".repeat(16))))
        assertTrue(validateContent(root, strict = false).any { it.severity == Severity.ERROR && it.message.contains("puzzle hash mismatch") })
    }

    @Test
    fun duplicatePuzzleContentIsAnError() {
        val root = tmp.newFolder("duplicate")
        generateSea(root, 2, 1, 50, DEFAULT_SEED)
        val original = root.walkTopDown().first { it.isFile && it.path.contains("/puzzles/") }
        val definition = ContentJson.json.decodeFromString(PuzzleDefinition.serializer(), original.readText())
        writeCanonicalFile(root, "puzzles/copy-of-puzzle.json", PuzzleDefinition.serializer(), definition.copy(id = "copy-of-puzzle"))
        assertTrue(validateContent(root, strict = false).any { it.message.contains("same puzzle content") })
    }

    @Test
    fun verifyCertificatesReProvesEveryPuzzleOfSeaTwo() {
        val root = tmp.newFolder("proofs")
        generateSea(root, 2, 1, 50, DEFAULT_SEED)
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.ok)
        assertEquals(66, report.proofs)
    }

    // --- patrol, seal and the command line ------------------------------------------------

    @Test
    fun theGeneratedPatrolIsTheBuiltInDefault() {
        assertEquals(PatrolDefaults.config(), patrolConfig())
    }

    @Test
    fun patrolHasTwentyStagesThatGrow() {
        val config: PatrolConfigDto = patrolConfig()
        assertEquals((1..20).toList(), config.stages.map { it.ordinal })
        assertEquals(config.stages.map { it.width }.sorted(), config.stages.map { it.width })
        assertEquals(config.stages.map { it.mines }.sorted(), config.stages.map { it.mines })
    }

    @Test
    fun commandLineUsesTheDocumentedExitCodes() {
        assertEquals(EXIT_USAGE, run(arrayOf("bogus")))
        assertEquals(EXIT_USAGE, run(arrayOf("generate")))
        assertEquals(EXIT_USAGE, run(arrayOf("seal", "--root", tmp.newFolder("nocatalog").path)))
        assertEquals(EXIT_FAILED, run(arrayOf("validate", "--root", tmp.newFolder("empty").path)))
    }

    @Test
    fun generateThenValidateThroughTheCommandLine() {
        val out = tmp.newFolder("cli")
        assertEquals(EXIT_OK, run(arrayOf("generate", "--sea", "1", "--output", out.path)))
        assertEquals(EXIT_OK, run(arrayOf("validate", "--root", out.path)))
        assertEquals(EXIT_FAILED, run(arrayOf("validate", "--strict", "--root", out.path)))
        assertEquals(EXIT_OK, run(arrayOf("seal", "--root", out.path)))
    }

    @Test
    fun terrainGenerationCommandWritesPatterns() {
        val out = tmp.newFolder("terrain-cli")
        assertEquals(EXIT_OK, run(arrayOf("terrain-gen", "--family", "scatter", "--size", "10", "--count", "3", "--output", out.path)))
        val text = File(out, "terrains.json").readText()
        assertTrue(text.contains("islands-scatter-10x10-02"))
    }

    @Test
    fun allTwelveSeasGenerateWithinTheBudgetAndKeepTheirCounts() {
        val started = System.nanoTime()
        for (sea in 1..SEAS_COUNT) {
            val built = buildSea(sea, DEFAULT_SEED)
            assertEquals("sea $sea", MISSIONS_PER_SEA, built.size)
            val counts = types(built)
            val target = TYPE_COUNTS.getValue(sea)
            assertEquals("sea $sea", target.duel, counts[MissionKind.DUEL] ?: 0)
            assertEquals("sea $sea", target.puzzle, counts[MissionKind.PUZZLE] ?: 0)
            assertEquals("sea $sea", target.hunt, counts[MissionKind.HUNT] ?: 0)
            assertEquals("sea $sea", target.convoy, counts[MissionKind.CONVOY] ?: 0)
            assertEquals("sea $sea", 1, counts[MissionKind.BOSS] ?: 0)
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue("12 seas took $seconds s", seconds < 120.0)
    }

}
