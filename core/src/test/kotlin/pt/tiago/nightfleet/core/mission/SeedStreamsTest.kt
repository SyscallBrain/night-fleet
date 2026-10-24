package pt.tiago.nightfleet.core.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.mission.SeedStreams.Stream
import pt.tiago.nightfleet.core.random.SplitMix64

class SeedStreamsTest {

    @Test
    fun `mix is the generator output for that state`() {
        assertEquals(SplitMix64(12345L).nextLong(), SplitMix64.mix(12345L))
    }

    @Test
    fun `mix is deterministic and spreads nearby inputs`() {
        assertEquals(SplitMix64.mix(7L), SplitMix64.mix(7L))
        assertNotEquals(SplitMix64.mix(7L), SplitMix64.mix(8L))
    }

    @Test
    fun `the named streams produce different seeds for the same attempt`() {
        val seeds = Stream.entries.map { SeedStreams.seed(99L, it) }
        assertEquals(seeds.size, seeds.toSet().size)
    }

    @Test
    fun `the same stream is repeatable`() {
        assertEquals(SeedStreams.seed(99L, Stream.LAYOUT, 1), SeedStreams.seed(99L, Stream.LAYOUT, 1))
    }

    @Test
    fun `the side index separates layouts of the two seas`() {
        assertNotEquals(SeedStreams.seed(99L, Stream.LAYOUT, 0), SeedStreams.seed(99L, Stream.LAYOUT, 1))
    }

    @Test
    fun `an attempt seed depends on the level seed and the attempt index`() {
        assertNotEquals(SeedStreams.attemptSeed(5L, 0), SeedStreams.attemptSeed(5L, 1))
        assertNotEquals(SeedStreams.attemptSeed(5L, 0), SeedStreams.attemptSeed(6L, 0))
    }

    @Test
    fun `attempt seed follows the documented formula`() {
        assertEquals(SplitMix64.mix(5L xor 3L), SeedStreams.attemptSeed(5L, 3))
    }

    @Test
    fun `first player is always side zero or one`() {
        for (seed in 0 until 200L) {
            assertTrue(SeedStreams.firstPlayer(seed) in 0..1)
        }
    }

    @Test
    fun `first player is not always the same side`() {
        val starters = (0 until 100L).map { SeedStreams.firstPlayer(it) }.toSet()
        assertEquals(setOf(0, 1), starters)
    }

    @Test
    fun `stream rng starts from the derived seed`() {
        assertEquals(SplitMix64(SeedStreams.seed(3L, Stream.AI)).nextLong(), SeedStreams.rng(3L, Stream.AI).nextLong())
    }

    @Test
    fun `there are four named streams`() {
        assertEquals(listOf("LAYOUT", "AI", "FIRST_PLAYER", "ENGINE"), Stream.entries.map { it.name })
    }

    @Test
    fun `mission types are duel, hunt, convoy and boss in M2`() {
        assertEquals(listOf(MissionType.DUEL, MissionType.HUNT, MissionType.CONVOY, MissionType.BOSS), MissionType.entries.toList())
    }
}
