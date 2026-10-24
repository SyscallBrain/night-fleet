package pt.tiago.nightfleet.game.battle

import org.junit.Assume.assumeTrue
import org.junit.Test
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.FreePlayFixtures
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.FleetChoice

/**
 * Measures the p95 of [AdmiralOpponent.decide] on the JVM for HARD and RELENTLESS on 10 by 10 and 12 by 12 seas.
 * It runs only when NIGHTFLEET_BENCH is set, because a RELENTLESS decision takes a while:
 * `NIGHTFLEET_BENCH=1 tools/gw.sh :app:testDebugUnitTest --tests '*AdmiralDecideBenchmarkTest*'`. The timings are
 * printed to the test output; nothing is asserted.
 */
class AdmiralDecideBenchmarkTest {

    @Test
    fun p95OfDecideOnTheJvm() {
        assumeTrue("set NIGHTFLEET_BENCH to measure", System.getenv("NIGHTFLEET_BENCH") != null)
        val samples = System.getenv("NIGHTFLEET_BENCH_SAMPLES")?.toIntOrNull() ?: 40
        for (grid in listOf(10, 12)) for (level in listOf(OpponentLevel.HARD, OpponentLevel.RELENTLESS)) {
            val config = FreePlayConfig(grid = grid, fleet = FleetChoice.CLASSIC, level = level)
            val setup = FreePlayFixtures.setupFor(config, 1_000L + grid)
            val brain = AdmiralOpponent.forLevel("coral", level, setup.attemptSeed)
            // Observations of the admiral at different moments of a battle: a few parity shots by the player first.
            val observations = ArrayList<Observation>()
            var match = FreePlayFixtures.startOf(setup)
            var step = 0
            while (observations.size < samples && !match.finished && step++ < 4_000) {
                if (match.state.current == BattleMatch.ADMIRAL) {
                    observations += BattleMatch.admiralObservation(match)
                    match = match.apply(brain.decide(BattleMatch.admiralObservation(match), SplitMix64(step.toLong()))).match
                } else {
                    match = match.apply(ParityBrain.decide(Observation.of(match.state, 0), SplitMix64(step.toLong()))).match
                }
            }
            val times = observations.mapIndexed { i, obs ->
                val start = System.nanoTime()
                brain.decide(obs, SplitMix64(i.toLong()))
                (System.nanoTime() - start) / 1_000_000.0
            }.sorted()
            val p95 = times[minOf(times.lastIndex, (times.size * 0.95).toInt())]
            println("BENCH decide level=$level grid=${grid}x$grid samples=${times.size} median=${"%.1f".format(times[times.size / 2])}ms p95=${"%.1f".format(p95)}ms max=${"%.1f".format(times.last())}ms")
        }
    }
}
