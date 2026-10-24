package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CatalogManifestDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.sha256Hex
import java.io.File

class ExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun listing(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { relativePath(root, it) to it.readBytes() }.toSortedMap()

    @Test
    fun exportWritesEveryListedFileAndAManifestWithItsHash() {
        val source = tmp.newFolder("content")
        writeSyntheticCampaign(source)
        val output = File(tmp.root, "assets")
        val problems = exportAssets(source, output)
        assertTrue(problems.none { it.severity == Severity.ERROR })

        val catalog = ContentJson.json.decodeFromString<pt.tiago.nightfleet.core.content.CatalogDto>(File(source, "catalog.json").readText())
        val manifest = ContentJson.json.decodeFromString<CatalogManifestDto>(File(output, MANIFEST_NAME).readText())
        assertEquals(CatalogManifestDto.FORMAT, manifest.format)
        val appFiles = catalog.files.keys.filter { !it.startsWith("certificates/") || proofCertificate(File(source, it)) }
        assertEquals((appFiles + "catalog.json").toSet(), manifest.files.keys)
        for ((path, sha) in manifest.files) {
            assertEquals("sha256 of $path", sha, sha256Hex(File(output, path).readBytes()))
        }
        // The exported catalog lists the same files, with the hashes of the exported bytes.
        val exportedCatalog = ContentJson.json.decodeFromString<pt.tiago.nightfleet.core.content.CatalogDto>(File(output, "catalog.json").readText())
        assertEquals(manifest.files.keys - "catalog.json", exportedCatalog.files.keys)
        assertEquals(manifest.files.filterKeys { it != "catalog.json" }, exportedCatalog.files)
    }

    @Test
    fun exportedJsonIsCompactAndDecodesToTheSameValues() {
        val source = tmp.newFolder("content")
        writeSyntheticCampaign(source)
        val output = File(tmp.root, "assets")
        exportAssets(source, output)
        // catalog.json differs from content/ on purpose (its files map holds the exported hashes), so it is not compared.
        for (path in listOf("missions/s01/s01m001.json", "admirals.json")) {
            val exported = File(output, path).readText(Charsets.UTF_8)
            assertFalse("$path is indented", exported.contains("\n"))
            assertEquals("$path has the same value", Json.Default.parseToJsonElement(File(source, path).readText()), Json.Default.parseToJsonElement(exported))
        }
    }

    private fun proofCertificate(file: File): Boolean =
        ContentJson.json.decodeFromString(CertificateDto.serializer(), file.readText()) is CertificateDto.Proof

    @Test
    fun exportTwiceGivesIdenticalBytes() {
        val source = tmp.newFolder("content")
        writeSyntheticCampaign(source)
        val first = File(tmp.root, "first")
        val second = File(tmp.root, "second")
        exportAssets(source, first)
        exportAssets(source, second)
        // Read each tree once: listing() reads every file, so calling it per entry was quadratic.
        val firstFiles = listing(first)
        val secondFiles = listing(second)
        assertEquals(600, firstFiles.keys.count { it.startsWith("missions/") })
        assertTrue(firstFiles.keys.filter { it.startsWith("certificates/") }.all { proofCertificate(File(first, it)) })
        assertEquals(firstFiles.keys, secondFiles.keys)
        for ((path, bytes) in firstFiles) {
            assertTrue("bytes of $path differ", bytes.contentEquals(secondFiles.getValue(path)))
        }
    }

    @Test
    fun exportClearsStaleFilesFromTheOutputDirectory() {
        val source = tmp.newFolder("content")
        writeSyntheticCampaign(source)
        val output = tmp.newFolder("assets")
        File(output, "stale.json").writeText("{}")
        exportAssets(source, output)
        assertFalse(File(output, "stale.json").exists())
    }

    @Test
    fun exportWritesNothingWhenValidationFails() {
        val source = tmp.newFolder("content")
        writeSampleCatalog(source)
        File(source, "missions/s01/s01m001.json").appendText("\n")
        val output = File(tmp.root, "assets")
        val problems = exportAssets(source, output)
        assertTrue(problems.any { it.severity == Severity.ERROR })
        assertFalse(output.exists())
    }

    @Test
    fun exportRefusesToClearTheContentRoot() {
        val source = tmp.newFolder("content")
        writeSyntheticCampaign(source)
        val parent = source.parentFile
        try {
            exportAssets(source, parent)
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("refusing to clear"))
        }
    }

    /**
     * The performance budget: export-assets stays under 30 s for the full 600-mission
     * campaign shape (strict validation, copy and manifest). The exports are also byte-compared.
     */
    @Test
    fun syntheticSixHundredMissionCampaignExportsUnderThirtySecondsDeterministically() {
        val source = tmp.newFolder("campaign")
        writeSyntheticCampaign(source)
        val catalog = ContentJson.json.decodeFromString<pt.tiago.nightfleet.core.content.CatalogDto>(File(source, "catalog.json").readText())
        assertEquals(600, catalog.missions.size)
        assertEquals(12, catalog.seas.size)

        val first = File(tmp.root, "assets-1")
        val started = System.nanoTime()
        val problems = exportAssets(source, first)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("export-assets synthetic 600 missions: $millis ms, ${catalog.files.size + 1} files")
        assertTrue("strict errors: ${problems.filter { it.severity == Severity.ERROR }.take(5)}", problems.none { it.severity == Severity.ERROR })
        assertTrue("export took $millis ms", millis < 30_000)

        val second = File(tmp.root, "assets-2")
        exportAssets(source, second)
        // Read each tree once (see exportTwiceGivesIdenticalBytes): the timed export above is not affected.
        val firstFiles = listing(first)
        val secondFiles = listing(second)
        assertEquals(firstFiles.keys, secondFiles.keys)
        assertTrue(firstFiles.all { (path, bytes) -> bytes.contentEquals(secondFiles.getValue(path)) })
    }

    @Test
    fun syntheticCampaignIsStrictlyValid() {
        val source = tmp.newFolder("campaign")
        writeSyntheticCampaign(source)
        val errors = validateContent(source, strict = true).filter { it.severity == Severity.ERROR }
        assertEquals(emptyList<Problem>(), errors)
    }
}
