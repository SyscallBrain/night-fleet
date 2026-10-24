package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/** Prints timings (target: a full random 10 by 10 battle in under 2 ms). Never asserts timing. */
class EnginePerformanceTest {

    @Test
    fun `random battles run quickly and the timing is printed`() {
        RandomBattles.duel(0L) // warm up the JIT
        val battles = 300
        val started = System.nanoTime()
        var shots = 0
        for (seed in 1L..battles.toLong()) {
            shots += RandomBattles.duel(seed).turn
        }
        val elapsedNs = System.nanoTime() - started
        println(
            "Engine: $battles random 10x10 duels, ${elapsedNs / 1_000 / battles} us per battle, " +
                "${elapsedNs / shots} ns per turn (average $shots turns)",
        )
        assertTrue(shots > 0)
    }
}
