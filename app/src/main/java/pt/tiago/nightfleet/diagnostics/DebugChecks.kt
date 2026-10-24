package pt.tiago.nightfleet.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.core.ai.AI_VERSION
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.DecisionCases
import pt.tiago.nightfleet.core.ai.GoldenMoves
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.hash.ScriptedDuel
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.rules.RuleSet
import kotlin.math.ceil

/**
 * Checks that run on the device in debug builds (Settings → About, seven taps on the version). They prove that
 * the admirals and the engine give the same answers on ART as on the JVM. Nothing here is in the release APK
 * except the code: the golden file is copied into the debug assets only (see `app/build.gradle.kts`).
 */
object DebugChecks {

    private const val AI_VERSION_TEXT = AI_VERSION

    /** Asset path of the golden moves, from the debug source set. */
    const val GOLDEN_ASSET = "diagnostics/golden-moves.txt"

    /** Decisions per timing series. */
    const val TIMED_DECISIONS = 100

    /** Decisions run before timing starts, so that the first measured one is not a cold start. */
    private const val WARM_UP = 5

    /** The two timed tiers: the strongest campaign admiral and free play's relentless one. */
    val TIMED_TIERS = listOf(AiTier.HARD, AiTier.RELENTLESS)

    /** The two board sizes: the classic 10×10 and the 12×12 of later seas, with the large fleet. */
    val TIMED_BOARDS: List<Pair<String, RuleSet>> = listOf(
        "10x10" to RuleSet(),
        "12x12" to RuleSet(width = 12, height = 12, fleet = Fleet.LARGE),
    )

    /** One golden tier: whether every view matches, and the first differences. */
    class GoldenResult(
        val tier: AiTier,
        val versionMatches: Boolean,
        val checked: Int,
        val mismatches: Int,
        val firstMismatches: List<GoldenMoves.Mismatch>,
        /** Set when the golden file itself cannot be read; then nothing was compared. */
        val parseError: String? = null,
    ) {
        val passed: Boolean get() = parseError == null && versionMatches && mismatches == 0
    }

    /** Milliseconds of [TIMED_DECISIONS] decisions. */
    class Timing(val tier: AiTier, val board: String, val p50: Double, val p95: Double, val max: Double)

    /** Engine golden: the hash of the scripted duel against the one pinned in the JVM tests. */
    class EngineResult(val expected: Long, val actual: Long) {
        val passed: Boolean get() = expected == actual
    }

    /** A full run, shown on the screen and written into the diagnostics export. */
    class Run(val golden: List<GoldenResult>, val timings: List<Timing>, val engine: EngineResult, val goldenLoaded: Boolean)

    /** Runs everything on [Dispatchers.Default], never on the main thread. */
    suspend fun runAll(context: Context): Run = withContext(Dispatchers.Default) {
        val text = runCatching { context.assets.open(GOLDEN_ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        val golden = text?.let { golden(it) }.orEmpty()
        Run(
            golden = golden,
            timings = timings(),
            engine = engine(),
            goldenLoaded = text != null,
        )
    }

    /** Compares every tier with the golden text. A malformed file counts as a failed run, with its reason. */
    fun golden(text: String): List<GoldenResult> {
        val table = try {
            GoldenMoves.parse(text)
        } catch (error: IllegalArgumentException) {
            val reason = error.message.orEmpty()
            return AiTier.entries.map { GoldenResult(it, versionMatches = false, checked = 0, mismatches = 0, firstMismatches = emptyList(), parseError = reason) }
        }
        return AiTier.entries.map { tier ->
            val outcome = GoldenMoves.compare(table, tier)
            GoldenResult(
                tier = tier,
                versionMatches = outcome.versionMatches,
                checked = outcome.checked,
                mismatches = outcome.mismatches.size,
                firstMismatches = outcome.mismatches.take(3),
            )
        }
    }

    /** Times [TIMED_DECISIONS] decisions for each tier and board. Views are built before the clock starts. */
    fun timings(): List<Timing> = TIMED_BOARDS.flatMap { (board, rules) ->
        val cases = (0 until WARM_UP + TIMED_DECISIONS).map { DecisionCases.build(it, rules) }
        TIMED_TIERS.map { tier ->
            cases.take(WARM_UP).forEach { it.decide(tier) }
            val millis = cases.drop(WARM_UP).map { case ->
                val start = System.nanoTime()
                case.decide(tier)
                (System.nanoTime() - start) / 1_000_000.0
            }
            val sorted = millis.sorted()
            Timing(tier, board, percentile(sorted, 50.0), percentile(sorted, 95.0), sorted.last())
        }
    }

    /** Nearest-rank percentile of an ascending list. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty()) { "no samples" }
        require(p in 0.0..100.0) { "percentile $p outside 0..100" }
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Hash of the scripted duel on this device against the golden of the JVM tests. */
    fun engine(): EngineResult = EngineResult(
        expected = ScriptedDuel.GOLDEN_HASH,
        actual = StateHasher.hash(ScriptedDuel.play(ScriptedDuel.SEED)),
    )

    /** The last run of this process, so that the diagnostics export can include it. Null until a run finishes. */
    @Volatile
    var lastRun: Run? = null

    /** The plain-text section of the diagnostics export. */
    fun render(run: Run): String = buildString {
        appendLine("[debug checks]")
        appendLine("golden file: ${if (run.goldenLoaded) "loaded" else "missing"} (ai_version $AI_VERSION_TEXT)")
        for (result in run.golden) {
            appendLine(
                "golden ${result.tier.name.lowercase()}: ${if (result.passed) "pass" else "FAIL"}, " +
                    "checked ${result.checked}, mismatches ${result.mismatches}, version ${if (result.versionMatches) "ok" else "differs"}",
            )
            result.parseError?.let { appendLine("  ${it}") }
            for (mismatch in result.firstMismatches) {
                appendLine("  view ${mismatch.view}: expected ${mismatch.expected}, got ${mismatch.actual}")
            }
        }
        for (timing in run.timings) {
            appendLine(
                "decide ${timing.tier.name.lowercase()} ${timing.board}: p50 ${fmt(timing.p50)} ms, " +
                    "p95 ${fmt(timing.p95)} ms, max ${fmt(timing.max)} ms (n=$TIMED_DECISIONS)",
            )
        }
        appendLine(
            "engine hash: ${if (run.engine.passed) "pass" else "FAIL"}, expected ${run.engine.expected}, got ${run.engine.actual}",
        )
    }

    private fun fmt(ms: Double): String = "%.2f".format(java.util.Locale.ROOT, ms)
}
