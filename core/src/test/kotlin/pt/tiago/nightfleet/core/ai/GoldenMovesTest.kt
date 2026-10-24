package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden moves: 200 fixed views, each decided by every tier with a fixed profile and rng.
 * The expected moves live in `src/test/resources/.../ai/golden-moves.txt`. Any change in a decision
 * changes a line, which must come with an `AI_VERSION` bump. Regenerate with
 * `NIGHTFLEET_REGEN_GOLDEN=1 tools/gw.sh :core:test --tests '*GoldenMovesTest.regenerate*'`.
 * The views and the parser are in `:core` main, so the Android debug diagnostics use the same code.
 */
class GoldenMovesTest {

    @Test
    fun `easy golden moves`() = assertGolden(AiTier.EASY)

    @Test
    fun `medium golden moves`() = assertGolden(AiTier.MEDIUM)

    @Test
    fun `hard golden moves`() = assertGolden(AiTier.HARD)

    @Test
    fun `relentless golden moves`() = assertGolden(AiTier.RELENTLESS)

    @Test
    fun `regenerate golden moves when asked`() {
        if (System.getenv("NIGHTFLEET_REGEN_GOLDEN") != "1") return
        File("src/test/resources/${GoldenMoves.RESOURCE}").writeText(GoldenMoves.render())
    }

    private fun assertGolden(tier: AiTier) {
        val table = GoldenMoves.parse(expectedText())
        assertEquals("ai_version", AI_VERSION, table.aiVersion)
        val outcome = GoldenMoves.compare(table, tier)
        val first = outcome.mismatches.take(5).joinToString { "view ${it.view}: expected ${it.expected}, got ${it.actual}" }
        assertTrue("${tier.name} differs from the golden file: $first", outcome.mismatches.isEmpty())
        assertEquals(GoldenMoves.VIEWS, outcome.checked)
    }

    private fun expectedText(): String {
        val stream = javaClass.classLoader.getResourceAsStream(GoldenMoves.RESOURCE) ?: error("missing ${GoldenMoves.RESOURCE}")
        return stream.bufferedReader().use { it.readText() }
    }
}
