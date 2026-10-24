package pt.tiago.nightfleet.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.GoldenMoves
import java.io.File

/** The debug checks on the JVM: percentiles, the golden comparison on the shared file, and the export text. */
class DebugChecksTest {

    private fun goldenFile(): File {
        // Gradle runs unit tests in the module directory; the golden file lives in the core module.
        val file = File("../core/src/test/resources/${GoldenMoves.RESOURCE}")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        return file
    }

    @Test
    fun `nearest-rank percentiles on a known series`() {
        val series = (1..100).map { it.toDouble() }
        assertEquals(50.0, DebugChecks.percentile(series, 50.0), 0.0)
        assertEquals(95.0, DebugChecks.percentile(series, 95.0), 0.0)
        assertEquals(100.0, DebugChecks.percentile(series, 100.0), 0.0)
        assertEquals(1.0, DebugChecks.percentile(series, 0.0), 0.0)
        assertEquals(7.0, DebugChecks.percentile(listOf(7.0), 95.0), 0.0)
    }

    @Test
    fun `percentile rejects an empty series and an out of range p`() {
        try {
            DebugChecks.percentile(emptyList(), 50.0)
            fail("empty series accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            DebugChecks.percentile(listOf(1.0), 101.0)
            fail("p above 100 accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `the shared golden file matches the JVM on every tier`() {
        val results = DebugChecks.golden(goldenFile().readText())
        assertEquals(AiTier.entries, results.map { it.tier })
        for (result in results) {
            assertTrue("${result.tier} differs: ${result.firstMismatches}", result.passed)
            assertEquals(GoldenMoves.VIEWS, result.checked)
        }
    }

    @Test
    fun `a malformed golden file fails every tier with its reason and compares nothing`() {
        val results = DebugChecks.golden("ai_version 2\nNIGHTMARE 0 Timeout\n")
        for (result in results) {
            assertFalse(result.passed)
            assertEquals(0, result.checked)
            assertTrue(result.parseError!!.contains("line 2"))
        }
    }

    @Test
    fun `a golden file of another version fails with the version flag`() {
        val text = goldenFile().readText().replaceFirst(Regex("^ai_version \\d+"), "ai_version 999")
        val results = DebugChecks.golden(text)
        for (result in results) {
            assertFalse(result.versionMatches)
            assertFalse(result.passed)
        }
    }

    @Test
    fun `the export text lists every golden tier, timing series and the engine hash`() {
        val run = DebugChecks.Run(
            golden = listOf(DebugChecks.GoldenResult(AiTier.EASY, true, 200, 0, emptyList())),
            timings = listOf(DebugChecks.Timing(AiTier.HARD, "10x10", 1.5, 3.25, 9.0)),
            engine = DebugChecks.EngineResult(expected = 1L, actual = 2L),
            goldenLoaded = true,
        )
        val text = DebugChecks.render(run)
        assertTrue(text.contains("golden easy: pass, checked 200, mismatches 0"))
        assertTrue(text.contains("decide hard 10x10: p50 1.50 ms, p95 3.25 ms, max 9.00 ms (n=100)"))
        assertTrue(text.contains("engine hash: FAIL, expected 1, got 2"))
    }
}
