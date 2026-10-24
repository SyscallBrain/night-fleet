package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.DailyKind
import java.io.File

/** Daily hunts: generation, validation, calibration and replay of `daily/hunts.json`. */
class DailyHuntTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A catalog with no seas: the daily pool is checked on its own, as the campaign catalog is not needed for it. */
    private fun writeEmptyCatalog(root: File) {
        writeCanonicalFile(root, "catalog.json", CatalogDto.serializer(), CatalogDto(1, 1, 1, emptyList(), emptyList(), emptyMap()))
    }

    private fun readHunts(root: File): DailyHuntsDto =
        ContentJson.json.decodeFromString(DailyHuntsDto.serializer(), File(root, DAILY_HUNTS_PATH).readText())

    @Test
    fun generationIsDeterministicAndWritesOneEntryPerHunt() {
        val first = tmp.newFolder()
        val second = tmp.newFolder()
        generateDailyHunts(first, 1, 3, DEFAULT_SEED)
        generateDailyHunts(second, 1, 3, DEFAULT_SEED)
        assertTrue(File(first, DAILY_HUNTS_PATH).readBytes().contentEquals(File(second, DAILY_HUNTS_PATH).readBytes()))
        assertTrue(File(first, DAILY_POOL_PATH).readBytes().contentEquals(File(second, DAILY_POOL_PATH).readBytes()))
        assertEquals(9, readHunts(first).hunts.size)
        assertEquals(9, readDailyPool(first)!!.entries.count { it.kind == DailyKind.HUNT })
        assertTrue(readHunts(first).hunts.all { !it.calibrated && it.certificate == null })
    }

    @Test
    fun regeneratingThePoolKeepsItsHuntEntries() {
        val root = tmp.newFolder()
        generateDailyHunts(root, 1, 2, DEFAULT_SEED)
        Puzzles.dailyPool(root, version = 1, count = 1, seed = DEFAULT_SEED)
        val pool = readDailyPool(root)!!
        assertEquals(6, pool.entries.count { it.kind == DailyKind.HUNT })
        assertEquals(3, pool.entries.count { it.kind == DailyKind.PUZZLE })
    }

    @Test
    fun generatedHuntsHaveNoErrorsBeforeCalibration() {
        val root = tmp.newFolder()
        generateDailyHunts(root, 1, 3, DEFAULT_SEED)
        writeEmptyCatalog(root)
        val errors = validateContent(root, strict = false)
            .filter { it.file == DAILY_HUNTS_PATH && it.severity == Severity.ERROR }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun strictCountsDemandFourHundredHuntsPerDifficulty() {
        val root = tmp.newFolder()
        generateDailyHunts(root, 1, 3, DEFAULT_SEED)
        writeEmptyCatalog(root)
        val strict = validateContent(root, strict = true)
            .filter { it.file == DAILY_HUNTS_PATH && it.severity == Severity.ERROR }
        assertTrue(strict.toString(), strict.any { it.message == "strict: 3 hunts for EASY, expected 400" })
    }

    @Test
    fun calibratedHuntsAreWrittenReplayAndResumeSkipsThem() {
        val root = tmp.newFolder()
        generateDailyHunts(root, 1, 3, DEFAULT_SEED)
        writeEmptyCatalog(root)
        val first = calibrateContent(root, CalibrateOptions(sea = null, mission = null, games = 8, threads = 2, resume = false, dailyOnly = true)) {}
        assertEquals(9, first.done)
        assertEquals(0, first.failed)
        val calibrated = readHunts(root)
        assertTrue(calibrated.hunts.all { it.calibrated && it.certificate != null && it.shotBudget != null && it.stars != null })

        val before = File(root, DAILY_HUNTS_PATH).readBytes()
        val second = calibrateContent(root, CalibrateOptions(null, null, 8, 2, resume = true, dailyOnly = true)) {}
        assertEquals(9, second.skipped)
        assertTrue(before.contentEquals(File(root, DAILY_HUNTS_PATH).readBytes()))

        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.failed.isEmpty())
        assertEquals(9, report.replayed)
    }

    @Test
    fun aChangedHuntLayoutBreaksItsCertificate() {
        val root = tmp.newFolder()
        generateDailyHunts(root, 1, 3, DEFAULT_SEED)
        writeEmptyCatalog(root)
        calibrateContent(root, CalibrateOptions(null, null, 8, 1, resume = false, dailyOnly = true)) {}
        val dto = readHunts(root)
        val hunt = dto.hunts.first()
        // Rotate the layouts: the certificate names the pool by hash, so it no longer matches.
        val rotated = hunt.copy(layouts = hunt.layouts.drop(1) + listOf(hunt.layouts.first()))
        writeCanonicalFile(root, DAILY_HUNTS_PATH, DailyHuntsDto.serializer(), dto.copy(hunts = listOf(rotated) + dto.hunts.drop(1)))
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.problems.toString(), report.problems.any { it.file == DAILY_HUNTS_PATH && it.message.contains("layouts changed") })
    }
}
