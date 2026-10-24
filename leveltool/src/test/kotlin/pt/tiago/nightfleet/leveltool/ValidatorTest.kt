package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.WeaponConfigDto
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration
import pt.tiago.nightfleet.core.content.CertificateDto.Proof
import java.io.File

/** Paths of the sample catalog (see [writeSampleCatalog]). */
private const val DUEL = "missions/s01/s01m001.json"
private const val HUNT = "missions/s01/s01m002.json"
private const val PUZZLE_MISSION = "missions/s01/s01m003.json"
private const val POOL = "layouts/s01-tutorial.json"
private const val DUEL_CERT = "certificates/s01m001-E.json"
private const val PROOF_CERT = "certificates/s01m003-E.json"
private const val PUZZLE = "puzzles/p-s01m003.json"

class ValidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): File = tmp.newFolder().also { writeSampleCatalog(it) }

    private fun errors(root: File, strict: Boolean = false): List<Problem> =
        validateContent(root, strict).filter { it.severity == Severity.ERROR }

    private fun assertErrorContains(root: File, fragment: String, strict: Boolean = false) {
        val found = errors(root, strict)
        assertTrue("expected an error containing '$fragment', got: $found", found.any { it.toString().contains(fragment) })
    }

    /** Decodes, edits and rewrites one content file, then reseals the catalog. */
    private fun <T> edit(root: File, relative: String, serializer: KSerializer<T>, transform: (T) -> T) {
        val value = ContentJson.json.decodeFromString(serializer, File(root, relative).readText())
        writeContent(root, relative, serializer, transform(value))
        seal(root)
    }

    private fun editVariant(root: File, relative: String, difficulty: Difficulty, transform: (pt.tiago.nightfleet.core.content.VariantDto) -> pt.tiago.nightfleet.core.content.VariantDto) =
        edit(root, relative, MissionDto.serializer()) { mission ->
            mission.copy(variants = mission.variants + (difficulty to transform(mission.variants.getValue(difficulty))))
        }

    @Test
    fun sampleCatalogIsValidInLenientMode() {
        assertEquals(emptyList<Problem>(), errors(sample()))
    }

    @Test
    fun sampleCatalogFailsStrictModeOnlyForTheMissingCampaign() {
        val found = errors(sample(), strict = true)
        assertTrue(found.isNotEmpty())
        assertTrue(found.any { it.message.contains("strict: 12 seas required") })
        assertTrue(found.any { it.message.contains("strict: 600 missions required") })
    }

    @Test
    fun changedFileWithoutResealIsAHashMismatch() {
        val root = sample()
        File(root, DUEL).appendText("\n")
        assertErrorContains(root, "sha256 mismatch")
    }

    @Test
    fun unknownFieldIsReportedAsUndecodable() {
        val root = sample()
        File(root, DUEL).writeText(File(root, DUEL).readText().replaceFirst("\"family\": \"open-tutorial\",", "\"family\": \"open-tutorial\",\n  \"bogus\": 1,"))
        seal(root)
        assertErrorContains(root, "cannot decode")
    }

    @Test
    fun ordinalMustMatchCatalogOrder() {
        val root = sample()
        edit(root, HUNT, MissionDto.serializer()) { it.copy(ordinal = 5) }
        assertErrorContains(root, "expected ordinal 2")
    }

    @Test
    fun unknownLayoutPoolIsAReferenceError() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(layoutPool = "missing-pool") }
        assertErrorContains(root, "unknown layout pool missing-pool")
    }

    @Test
    fun overlappingLayoutIsRejectedByThePlacementValidator() {
        val root = sample()
        edit(root, POOL, LayoutPoolDto.serializer()) { pool ->
            val bad = listOf(
                PlacementDto(ShipClass.CRUISER, 0, 0, 0),
                PlacementDto(ShipClass.DESTROYER, 0, 0, 0),
                PlacementDto(ShipClass.DESTROYER, 5, 5, 0),
            )
            pool.copy(layouts = listOf(bad) + pool.layouts.drop(1))
        }
        assertErrorContains(root, "placement OVERLAP")
    }

    @Test
    fun touchingShipsAreRejected() {
        val root = sample()
        edit(root, POOL, LayoutPoolDto.serializer()) { pool ->
            val bad = listOf(
                PlacementDto(ShipClass.CRUISER, 0, 0, 0),
                PlacementDto(ShipClass.DESTROYER, 1, 0, 0),
                PlacementDto(ShipClass.DESTROYER, 6, 6, 0),
            )
            pool.copy(layouts = listOf(bad) + pool.layouts.drop(1))
        }
        assertErrorContains(root, "placement TOUCHING")
    }

    @Test
    fun mineNextToAShipIsRejected() {
        val root = sample()
        edit(root, POOL, LayoutPoolDto.serializer()) { pool ->
            val layout = listOf(
                PlacementDto(ShipClass.CRUISER, 0, 0, 0),
                PlacementDto(ShipClass.DESTROYER, 2, 5, 0),
                PlacementDto(ShipClass.DESTROYER, 6, 0, 0),
            )
            pool.copy(layouts = listOf(layout) + pool.layouts.drop(1))
        }
        // D1 is orthogonally next to the cruiser's last square A3 (row 0, col 2).
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(rules = it.rules.copy(mines = listOf(emptyList(), listOf("D1")))) }
        assertErrorContains(root, "placement MINE_NEAR_SHIP")
    }

    @Test
    fun bossOnlyAtTheFiftiethMissionOfASea() {
        val root = sample()
        edit(root, HUNT, MissionDto.serializer()) { it.copy(type = MissionKind.BOSS, variants = it.variants.mapValues { (_, v) -> v.copy(shotBudget = null) }) }
        assertErrorContains(root, "BOSS only at the 50th mission")
    }

    @Test
    fun ruleTagWithoutTheRuleIsRejected() {
        val root = sample()
        edit(root, DUEL, MissionDto.serializer()) { it.copy(tags = emptyList()) }
        assertErrorContains(root, "missing tag ISLANDS")
    }

    @Test
    fun densityAboveQuarterOfTheWaterIsRejected() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(rules = RulesDto(width = 8, height = 8, fleet = "CLASSIC", terrain = "islands-8")) }
        assertErrorContains(root, "exceeds 25 %")
    }

    @Test
    fun missingVariantIsRejected() {
        val root = sample()
        edit(root, DUEL, MissionDto.serializer()) { it.copy(variants = it.variants - Difficulty.MEDIUM) }
        assertErrorContains(root, "missing variants MEDIUM")
    }

    @Test
    fun changedVariantInvalidatesItsCalibrationCertificate() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(levelSeed = it.levelSeed + 1) }
        assertErrorContains(root, "variant changed since the certificate was made")
    }

    @Test
    fun certificateThresholdsMustMatchTheVariant() {
        val root = sample()
        edit(root, DUEL_CERT, CertificateDto.serializer()) { (it as Calibration).copy(thresholds = StarsDto(two = 1, three = 1)) }
        assertErrorContains(root, "thresholds differ")
    }

    @Test
    fun calibrationSampleNeedsAtLeastSixtyFourGames() {
        val root = sample()
        edit(root, DUEL_CERT, CertificateDto.serializer()) { (it as Calibration).copy(sample = it.sample.copy(games = 10)) }
        assertErrorContains(root, "at least 64 games")
    }

    @Test
    fun witnessEnemyLayoutMustExistInThePool() {
        val root = sample()
        edit(root, DUEL_CERT, CertificateDto.serializer()) { (it as Calibration).copy(witness = it.witness.copy(enemyLayout = 99)) }
        assertErrorContains(root, "enemyLayout 99 is not in the pool")
    }

    @Test
    fun proofMustMatchThePuzzleSolution() {
        val root = sample()
        edit(root, PROOF_CERT, CertificateDto.serializer()) {
            val proof = it as Proof
            val cells = proof.solution.cells.toMutableList()
            cells[cells.indexOfFirst { cell -> cell == PlayerCell.SHIP }] = PlayerCell.WATER
            proof.copy(solution = proof.solution.copy(cells = cells))
        }
        assertErrorContains(root, "ship squares")
    }

    @Test
    fun puzzleMissionNeedsItsPuzzleFile() {
        val root = sample()
        File(root, PUZZLE).delete()
        seal(root)
        assertErrorContains(root, "unknown puzzle p-s01m003")
    }

    @Test
    fun poolTerrainHashMustMatchTheTerrain() {
        val root = sample()
        edit(root, POOL, LayoutPoolDto.serializer()) { it.copy(terrainHash = "0".repeat(64)) }
        assertErrorContains(root, "terrainHash does not match terrain islands-8")
    }

    @Test
    fun repeatedLayoutInAPoolIsRejected() {
        val root = sample()
        edit(root, POOL, LayoutPoolDto.serializer()) { it.copy(layouts = listOf(it.layouts[0]) + it.layouts.drop(1) + listOf(it.layouts[0])) }
        assertErrorContains(root, "layout repeats an earlier layout")
    }

    @Test
    fun huntNeedsAShotBudget() {
        val root = sample()
        editVariant(root, HUNT, Difficulty.HARD) { it.copy(shotBudget = null) }
        assertErrorContains(root, "HUNT needs a shotBudget of at least 1")
    }

    @Test
    fun starsMustBeOrdered() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.HARD) { it.copy(stars = StarsDto(two = 1, three = 5)) }
        assertErrorContains(root, "stars must satisfy 1 <= three <= two")
    }

    @Test
    fun unknownWeaponIsRejected() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(weapons = mapOf("LASER" to WeaponConfigDto(initial = 1, max = 1))) }
        assertErrorContains(root, "unknown weapon LASER")
    }

    @Test
    fun malformedMineLabelIsRejected() {
        val root = sample()
        editVariant(root, DUEL, Difficulty.EASY) { it.copy(rules = it.rules.copy(mines = listOf(emptyList(), listOf("??")))) }
        assertErrorContains(root, "malformed mine label ??")
    }

    @Test
    fun engineVersionMustMatchTheEngine() {
        val root = sample()
        edit(root, "catalog.json", CatalogDto.serializer()) { it.copy(engineVersion = 99) }
        assertErrorContains(root, "does not match the engine")
    }

    @Test
    fun duplicateMissionInTheCatalogIsRejected() {
        val root = sample()
        edit(root, "catalog.json", CatalogDto.serializer()) { it.copy(missions = it.missions + it.missions.first()) }
        assertErrorContains(root, "duplicate mission s01m001")
    }

    @Test
    fun missingCertificateIsAWarningInLenientModeAndAnErrorInStrictMode() {
        val root = sample()
        File(root, DUEL_CERT).delete()
        seal(root)
        assertEquals(emptyList<Problem>(), errors(root))
        assertTrue(validateContent(root, strict = false).any { it.severity == Severity.WARNING && it.message.contains("certificate is missing") })
        assertErrorContains(root, "certificate is missing", strict = true)
    }

    @Test
    fun unlistedPublishedFileIsRejectedInStrictMode() {
        val root = sample()
        // Written without sealing: the catalog does not list it.
        writeContent(root, "seas/s09.json", pt.tiago.nightfleet.core.content.SeaDto.serializer(), Sample.sea().copy(number = 9))
        assertErrorContains(root, "published file is not listed in catalog.files", strict = true)
    }

    @Test
    fun dailyPoolIsRequiredInStrictMode() {
        val root = sample()
        assertErrorContains(root, "daily pool is missing", strict = true)
    }

    @Test
    fun adminsFileMustDecode() {
        val root = sample()
        File(root, "admirals.json").writeText("{}\n")
        seal(root)
        assertErrorContains(root, "cannot decode")
    }
}
