package pt.tiago.nightfleet.core.ai

/**
 * Golden moves: [VIEWS] fixed cases, each decided by every tier. The expected moves are the text
 * format written by [render]:
 *
 * ```
 * ai_version 2
 * EASY 0 Fire(cell=Cell(row=8, col=7))
 * ```
 *
 * The file lives in `core/src/test/resources`. The JVM test compares it with [compare]; the Android debug build
 * copies the same file into its debug assets, so the diagnostics screen can repeat the check on the device.
 */
object GoldenMoves {

    const val VIEWS = 200

    /** Path of the golden file, relative to a classpath root. */
    const val RESOURCE = "pt/tiago/nightfleet/core/ai/golden-moves.txt"

    private const val VERSION_KEY = "ai_version"

    /** The expected moves of a golden file. [moves] is keyed by `"TIER index"`. */
    class Table(val aiVersion: Int, val moves: Map<String, String>)

    /** The result of checking one tier against the table. */
    class Outcome(
        val tier: AiTier,
        val checked: Int,
        val versionMatches: Boolean,
        val mismatches: List<Mismatch>,
    ) {
        val passed: Boolean get() = versionMatches && mismatches.isEmpty()
    }

    /** One view whose move differs from the golden file. [expected] is null when the file has no line for it. */
    class Mismatch(val view: Int, val expected: String?, val actual: String)

    /** The move of [tier] on view [i], as text (the `toString` of the intent). */
    fun moveFor(tier: AiTier, i: Int): String = DecisionCases.build(i).decide(tier).toString()

    /** The golden file for the current [AI_VERSION] and all tiers, with a trailing newline. */
    fun render(decide: (AiTier, Int) -> String = { t, i -> moveFor(t, i) }): String = buildString {
        append(VERSION_KEY).append(' ').append(AI_VERSION).append('\n')
        for (tier in AiTier.entries) for (i in 0 until VIEWS) {
            append(tier.name).append(' ').append(i).append(' ').append(decide(tier, i)).append('\n')
        }
    }

    /**
     * Parses a golden file. Blank lines are skipped. Throws [IllegalArgumentException] with the line number on
     * a missing version line, an unknown tier, a bad index, a missing move or a repeated line.
     */
    fun parse(text: String): Table {
        val lines = text.lineSequence().withIndex().filter { it.value.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "golden file is empty" }
        val header = lines.first().value.trim().split(' ')
        require(header.size == 2 && header[0] == VERSION_KEY) { "line ${lines.first().index + 1}: expected '$VERSION_KEY <n>'" }
        val version = header[1].toIntOrNull() ?: throw IllegalArgumentException("line ${lines.first().index + 1}: bad version")
        val moves = LinkedHashMap<String, String>()
        for ((index, raw) in lines.drop(1)) {
            val line = index + 1
            val parts = raw.trim().split(' ', limit = 3)
            require(parts.size == 3) { "line $line: expected 'TIER index move'" }
            val tier = AiTier.entries.firstOrNull { it.name == parts[0] } ?: throw IllegalArgumentException("line $line: unknown tier ${parts[0]}")
            val view = parts[1].toIntOrNull()?.takeIf { it in 0 until VIEWS } ?: throw IllegalArgumentException("line $line: bad view ${parts[1]}")
            require(parts[2].isNotBlank()) { "line $line: empty move" }
            val key = "${tier.name} $view"
            require(moves.put(key, parts[2]) == null) { "line $line: repeated $key" }
        }
        return Table(version, moves)
    }

    /** Checks every view of [tier] against [table]; [decide] is the engine under test. */
    fun compare(table: Table, tier: AiTier, decide: (AiTier, Int) -> String = { t, i -> moveFor(t, i) }): Outcome {
        val mismatches = ArrayList<Mismatch>()
        for (i in 0 until VIEWS) {
            val expected = table.moves["${tier.name} $i"]
            val actual = decide(tier, i)
            if (expected != actual) mismatches += Mismatch(i, expected, actual)
        }
        return Outcome(tier, VIEWS, table.aiVersion == AI_VERSION, mismatches)
    }
}
