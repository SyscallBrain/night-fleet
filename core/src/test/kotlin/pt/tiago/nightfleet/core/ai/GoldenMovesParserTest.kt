package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The golden-file parser and comparer, on small texts (the full file is checked by [GoldenMovesTest]). */
class GoldenMovesParserTest {

    private val sample = """
        ai_version 2
        EASY 0 Fire(cell=Cell(row=8, col=7))
        HARD 199 Repair(cell=Cell(row=1, col=2))

    """.trimIndent()

    @Test
    fun `parses the version and every move keyed by tier and view`() {
        val table = GoldenMoves.parse(sample)
        assertEquals(2, table.aiVersion)
        assertEquals("Fire(cell=Cell(row=8, col=7))", table.moves["EASY 0"])
        assertEquals("Repair(cell=Cell(row=1, col=2))", table.moves["HARD 199"])
        assertEquals(2, table.moves.size)
    }

    @Test
    fun `move text keeps its own spaces`() {
        val table = GoldenMoves.parse("ai_version 2\nMEDIUM 3 Fire(cell=Cell(row=0, col=0))\n")
        assertEquals("Fire(cell=Cell(row=0, col=0))", table.moves["MEDIUM 3"])
    }

    @Test
    fun `rejects a file without the version line`() {
        expectRejected("EASY 0 Fire(cell=Cell(row=8, col=7))\n")
        expectRejected("")
        expectRejected("ai_version two\n")
    }

    @Test
    fun `rejects unknown tiers, bad views, missing moves and repeats with the line number`() {
        expectRejected("ai_version 2\nNIGHTMARE 0 Fire(cell=Cell(row=0, col=0))\n", "line 2")
        expectRejected("ai_version 2\nEASY 200 Fire(cell=Cell(row=0, col=0))\n", "line 2")
        expectRejected("ai_version 2\nEASY -1 Fire(cell=Cell(row=0, col=0))\n", "line 2")
        expectRejected("ai_version 2\nEASY x Fire(cell=Cell(row=0, col=0))\n", "line 2")
        expectRejected("ai_version 2\nEASY 0\n", "line 2")
        expectRejected("ai_version 2\n\nEASY 0 Fire(cell=Cell(row=0, col=0))\nEASY 0 Timeout\n", "line 4")
    }

    @Test
    fun `compare passes when every view matches and reports each difference`() {
        val table = GoldenMoves.parse(GoldenMoves.render())
        assertTrue(GoldenMoves.compare(table, AiTier.HARD) { tier, i -> GoldenMoves.moveFor(tier, i) }.passed)

        val outcome = GoldenMoves.compare(table, AiTier.HARD) { tier, i ->
            if (i == 7 || i == 150) "Timeout" else GoldenMoves.moveFor(tier, i)
        }
        assertFalse(outcome.passed)
        assertEquals(listOf(7, 150), outcome.mismatches.map { it.view })
        assertEquals("Timeout", outcome.mismatches.first().actual)
        assertEquals(table.moves["HARD 7"], outcome.mismatches.first().expected)
    }

    @Test
    fun `compare fails on another ai version and on a missing line`() {
        val text = GoldenMoves.render().replaceFirst("ai_version $AI_VERSION", "ai_version ${AI_VERSION + 1}")
        val outcome = GoldenMoves.compare(GoldenMoves.parse(text), AiTier.EASY) { _, _ -> "Timeout" }
        assertFalse(outcome.versionMatches)
        assertFalse(outcome.passed)

        val missing = GoldenMoves.parse("ai_version $AI_VERSION\n")
        val result = GoldenMoves.compare(missing, AiTier.EASY) { _, _ -> "Timeout" }
        assertEquals(GoldenMoves.VIEWS, result.mismatches.size)
        assertEquals(null, result.mismatches.first().expected)
    }

    private fun expectRejected(text: String, mustMention: String? = null) {
        try {
            GoldenMoves.parse(text)
        } catch (error: IllegalArgumentException) {
            if (mustMention != null) assertTrue("'${error.message}' should mention $mustMention", error.message!!.contains(mustMention))
            return
        }
        fail("accepted: $text")
    }
}
