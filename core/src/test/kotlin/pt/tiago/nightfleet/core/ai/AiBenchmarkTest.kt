package pt.tiago.nightfleet.core.ai

import org.junit.Assume
import org.junit.Test
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Measurements, not tests. Runs only with `NIGHTFLEET_BENCH=1`:
 * `NIGHTFLEET_BENCH=1 tools/gw.sh :core:test --tests '*AiBenchmarkTest*'`.
 * Shots: a hunt on an open classic 10 by 10 against a random fleet, [GAMES] games per tier, four
 * threads with results collected in game order. Timing: one thread, [TIMED_GAMES] games per tier.
 * The summary goes to `logs/ai-bench.txt` at the project root and to stdout.
 */
class AiBenchmarkTest {

    private class TimedAI(private val inner: AdmiralAI) : AdmiralAI {
        val nanos = ArrayList<Long>()

        override fun decide(view: AiView, profile: AdmiralProfile, tier: AiTier, rng: SplitMix64, skill: AiSkill): AiIntent {
            val started = System.nanoTime()
            val intent = inner.decide(view, profile, tier, rng, skill)
            nanos += System.nanoTime() - started
            return intent
        }
    }

    private fun game(index: Int, tier: AiTier, ai: AdmiralAI): Pair<Int, List<Long>> {
        val seed = SplitMix64.split(0xB47A1EL, index.toLong())
        val timed = TimedAI(ai)
        val outcome = BattleRunner.hunt(RuleSet(), seed, BattleRunner.Side(timed, Admirals.NEUTRAL, tier))
        return outcome.shots[0] to timed.nanos
    }

    private fun percentile(sorted: List<Long>, p: Int): Long = sorted[((sorted.size * p + 99) / 100 - 1).coerceIn(0, sorted.size - 1)]

    @Test
    fun `measure admirals in a classic hunt`() {
        Assume.assumeTrue("set NIGHTFLEET_BENCH=1 to measure", System.getenv("NIGHTFLEET_BENCH") == "1")
        val games = System.getenv("NIGHTFLEET_BENCH_GAMES")?.toIntOrNull() ?: GAMES
        val timedGames = minOf(TIMED_GAMES, games)
        val pool = Executors.newFixedThreadPool(4)
        val lines = mutableListOf<String>()
        try {
            for (tier in AiTier.entries) {
                AiStats.reset()
                val started = System.nanoTime()
                val results = (0 until games).map { g -> pool.submit(Callable { game(g, tier, AdmiralBrain).first }) }.map { it.get() }
                val seconds = (System.nanoTime() - started) / 1e9
                val shots = results.map { it.toDouble() }
                val average = shots.average()
                val timedSingle = Executors.newSingleThreadExecutor()
                val times = try {
                    (0 until timedGames).map { g -> timedSingle.submit(Callable { game(g, tier, AdmiralBrain).second }).get() }.flatten().sorted()
                } finally {
                    timedSingle.shutdown()
                }
                lines += String.format(
                    "%-11s games=%d avg_shots=%.4f min=%d max=%d decide_p50_us=%.1f decide_p95_us=%.1f " +
                        "sampler_fallbacks=%d density_fallbacks=%d wall_s=%.1f",
                    tier.name, games, average, shots.minOrNull()?.toInt() ?: 0, shots.maxOrNull()?.toInt() ?: 0,
                    percentile(times, 50) / 1000.0, percentile(times, 95) / 1000.0,
                    AiStats.samplerFallbackCount, AiStats.densityFallbackCount, seconds,
                )
            }
        } finally {
            pool.shutdown()
        }
        val report = lines.joinToString("\n", postfix = "\n")
        println(report)
        File(System.getProperty("user.dir")).parentFile.resolve("logs/ai-bench.txt").apply { parentFile.mkdirs() }.writeText(report)
    }

    @Test
    fun `measure human players in a classic hunt`() {
        Assume.assumeTrue("set NIGHTFLEET_BENCH=1 to measure", System.getenv("NIGHTFLEET_BENCH") == "1")
        val games = System.getenv("NIGHTFLEET_BENCH_GAMES")?.toIntOrNull() ?: GAMES
        val pool = Executors.newFixedThreadPool(4)
        val lines = mutableListOf<String>()
        try {
            val withWeapons = RuleSet(
                weapons = mapOf(
                    Weapon.SONAR to WeaponConfig(initial = 1, max = 2, earn = listOf(EarnRule(EarnOn.STREAK, 3))),
                    Weapon.TORPEDO to WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK))),
                    Weapon.RECON to WeaponConfig(initial = 1, max = 1),
                    Weapon.CROSS_SHELL to WeaponConfig(initial = 1, max = 1),
                ),
            )
            for ((variant, rules) in listOf("no-weapons" to RuleSet(), "weapons" to withWeapons)) {
                for (player in listOf(HumanModel.EASY_PLAYER, HumanModel.MEDIUM_PLAYER, HumanModel.HARD_PLAYER)) {
                    val results = (0 until games).map { g ->
                        pool.submit(Callable {
                            val seed = SplitMix64.split(0xB47A1EL, g.toLong())
                            BattleRunner.hunt(rules, seed, BattleRunner.Side(player, Admirals.NEUTRAL, AiTier.HARD)).shots[0]
                        })
                    }.map { it.get().toDouble() }
                    lines += String.format(
                        "%-14s %-11s games=%d avg_shots=%.4f min=%d max=%d",
                        player.skill.name, variant, games, results.average(), results.minOrNull()?.toInt() ?: 0, results.maxOrNull()?.toInt() ?: 0,
                    )
                }
            }
        } finally {
            pool.shutdown()
        }
        val report = lines.joinToString("\n", postfix = "\n")
        println(report)
        File(System.getProperty("user.dir")).parentFile.resolve("logs/ai-bench-human.txt").apply { parentFile.mkdirs() }.writeText(report)
    }

    companion object {
        const val GAMES = 2000
        const val TIMED_GAMES = 200
    }
}
