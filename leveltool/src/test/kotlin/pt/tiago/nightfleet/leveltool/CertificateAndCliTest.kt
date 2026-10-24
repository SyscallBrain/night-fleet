package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.WitnessDto
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import java.io.File

class CertificateAndCliTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): File = tmp.newFolder().also { writeSampleCatalog(it) }

    private fun editCertificate(root: File, id: String, transform: (Calibration) -> Calibration) {
        val relative = "certificates/$id.json"
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), File(root, relative).readText()) as Calibration
        writeContent(root, relative, CertificateDto.serializer(), transform(certificate))
        seal(root)
    }

    @Test
    fun sampleWitnessesReplayToTheCertifiedWins() {
        val report = verifyCertificates(sample())
        assertTrue(report.failed.toString(), report.failed.isEmpty())
        assertTrue(report.problems.none { it.severity == Severity.ERROR })
        // Three DUEL and three HUNT variants; the puzzle has a PROOF, not a battle replay.
        assertEquals(6, report.replayed)
    }

    @Test
    fun wrongFinalHashFailsTheReplay() {
        val root = sample()
        editCertificate(root, "s01m001-E") { it.copy(witness = it.witness.copy(finalHash = "0000000000000001")) }
        val report = verifyCertificates(root)
        assertTrue(report.failed.any { it.startsWith("s01m001-E: final hash") })
        assertTrue(!report.ok)
    }

    @Test
    fun anIllegalActionFailsTheReplay() {
        val root = sample()
        editCertificate(root, "s01m002-M") { cert ->
            cert.copy(witness = cert.witness.copy(actions = listOf(cert.witness.actions.first()) + cert.witness.actions))
        }
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.failed.any { it.startsWith("s01m002-M: action 1") && it.contains("is illegal") })
    }

    @Test
    fun aWitnessThatLosesIsRejected() {
        val root = sample()
        editCertificate(root, "s01m001-H") { cert ->
            cert.copy(witness = cert.witness.copy(actions = listOf("F:A1")))
        }
        val report = verifyCertificates(root)
        assertTrue(report.failed.any { it.startsWith("s01m001-H:") })
    }

    @Test
    fun weaponActionWithoutChargesIsReplayedAndRejected() {
        val root = sample()
        val weapon = BattleCodec.encodeAction(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(Cell(0, 0))))
        editCertificate(root, "s01m001-E") { cert ->
            cert.copy(witness = WitnessDto(cert.witness.seedHex, 0, cert.witness.enemyLayout, listOf(weapon) + cert.witness.actions, cert.witness.finalHash))
        }
        val report = verifyCertificates(root)
        assertTrue(report.failed.any { it.startsWith("s01m001-E: action 0 is illegal") })
    }

    @Test
    fun missingCatalogFailsVerification() {
        val empty = tmp.newFolder()
        assertTrue(!verifyCertificates(empty).ok)
    }

    @Test
    fun reportListsMissionsPerSeaAndType() {
        val root = sample()
        val text = buildReport(root)
        assertTrue(text.contains("| s01 | 3 | 1 | 1 | 1 | 0 | 0 |"))
        assertTrue(text.contains("- Certificates missing: 0"))
        assertEquals(text, buildReport(root))
    }

    @Test
    fun reportListsMissingCertificates() {
        val root = sample()
        File(root, "certificates/s01m002-H.json").delete()
        seal(root)
        assertTrue(buildReport(root).contains("- Certificates missing: 1"))
    }

    @Test
    fun cliUsageErrorsExitWithTwo() {
        assertEquals(EXIT_USAGE, run(arrayOf()))
        assertEquals(EXIT_USAGE, run(arrayOf("bogus")))
        assertEquals(EXIT_USAGE, run(arrayOf("validate", "--nope")))
        assertEquals(EXIT_USAGE, run(arrayOf("validate", "--root")))
        assertEquals(EXIT_USAGE, run(arrayOf("export-assets", "--root", "content")))
        assertEquals(EXIT_USAGE, run(arrayOf("hash-file")))
    }

    @Test
    fun cliValidateExitsZeroOnTheSampleAndOneOnFailure() {
        val root = sample()
        assertEquals(EXIT_OK, run(arrayOf("validate", "--root", root.path)))
        assertEquals(EXIT_FAILED, run(arrayOf("validate", "--root", root.path, "--strict")))
        File(root, "missions/s01/s01m001.json").appendText("\n")
        assertEquals(EXIT_FAILED, run(arrayOf("validate", "--root", root.path)))
    }

    @Test
    fun cliHashFileAndReportSucceed() {
        val file = File(tmp.root, "data.txt").apply { writeText("abc") }
        assertEquals(EXIT_OK, run(arrayOf("hash-file", file.path)))
        val root = sample()
        assertEquals(EXIT_OK, run(arrayOf("report", "--root", root.path, "--output", File(tmp.root, "report.md").path)))
        assertTrue(File(tmp.root, "report.md").readText().startsWith("# Night Fleet content report"))
    }

    @Test
    fun exportCommandFailsWithoutWritingOnInvalidContent() {
        val root = sample()
        File(root, "missions/s01/s01m002.json").appendText("\n")
        val output = File(tmp.root, "assets")
        assertEquals(EXIT_FAILED, run(arrayOf("export-assets", "--root", root.path, "--output", output.path)))
        assertTrue(!output.exists())
    }

    @Test
    fun difficultyLettersMatchCertificateNames() {
        assertEquals("s01m001-E", certificateId("s01m001", Difficulty.EASY))
        assertEquals("s01m001-M", certificateId("s01m001", Difficulty.MEDIUM))
        assertEquals("s01m001-H", certificateId("s01m001", Difficulty.HARD))
    }

    @Test
    fun missionDocumentsDecodeWithStrictSchema() {
        val root = sample()
        val mission = ContentJson.json.decodeFromString(MissionDto.serializer(), File(root, "missions/s01/s01m002.json").readText())
        assertEquals("s01m002", mission.id)
    }
}
