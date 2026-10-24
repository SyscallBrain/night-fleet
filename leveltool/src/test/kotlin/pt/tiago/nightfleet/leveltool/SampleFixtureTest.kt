package pt.tiago.nightfleet.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The committed `src/test/resources/sample-content` fixture. It must equal what [writeSampleCatalog]
 * produces and must pass validation and replay. To rewrite it after a deliberate change, run the
 * tests with `NF_REGENERATE_FIXTURE=1`.
 */
class SampleFixtureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun resourceRoot(): File = File(javaClass.classLoader.getResource("sample-content")!!.toURI())

    private fun listing(root: File): Map<String, ByteArray> =
        root.walkTopDown().filter { it.isFile }.associate { relativePath(root, it) to it.readBytes() }.toSortedMap()

    @Test
    fun regenerateFixtureWhenAsked() {
        assumeTrue(System.getenv("NF_REGENERATE_FIXTURE") == "1")
        val target = File("leveltool/src/test/resources/sample-content")
        target.deleteRecursively()
        writeSampleCatalog(target)
    }

    @Test
    fun committedFixtureEqualsTheGenerator() {
        val generated = tmp.newFolder("generated")
        writeSampleCatalog(generated)
        val committed = listing(resourceRoot())
        assertEquals(listing(generated).keys, committed.keys)
        for ((path, bytes) in listing(generated)) {
            assertTrue("$path differs from the generator", bytes.contentEquals(committed.getValue(path)))
        }
    }

    @Test
    fun committedFixtureValidatesAndReplays() {
        val root = resourceRoot()
        assertEquals(emptyList<Problem>(), validateContent(root, strict = false).filter { it.severity == Severity.ERROR })
        val report = verifyCertificates(root)
        assertTrue(report.ok)
        assertEquals(6, report.replayed)
    }
}
