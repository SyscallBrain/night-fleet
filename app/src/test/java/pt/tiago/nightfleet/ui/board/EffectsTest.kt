package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Particles are deterministic, bounded, and the mine flashes never exceed three per second. */
class EffectsTest {

    @Test
    fun sameSeedGivesTheSameParticles() {
        assertEquals(EffectParticles.explosion(42L), EffectParticles.explosion(42L))
        assertEquals(EffectParticles.smoke(42L), EffectParticles.smoke(42L))
        assertEquals(WeatherParticles.generate(WeatherKind.SNOW, seed = 7L), WeatherParticles.generate(WeatherKind.SNOW, seed = 7L))
    }

    @Test
    fun differentSeedsGiveDifferentParticles() {
        assertNotEquals(EffectParticles.explosion(1L), EffectParticles.explosion(2L))
    }

    @Test
    fun explosionHasAtMostTwentyFourParticlesInRange() {
        val particles = EffectParticles.explosion(99L)
        assertEquals(EffectParticles.MAX_PARTICLES, particles.size)
        for (p in particles) {
            assertTrue(p.angle in 0f..(2 * PI.toFloat() + 0.001f))
            assertTrue(p.distance in 0.25f..0.81f)
            assertTrue(p.size in 0.05f..0.13f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun explosionRejectsMoreThanTheBudget() {
        EffectParticles.explosion(1L, count = EffectParticles.MAX_PARTICLES + 1)
    }

    @Test
    fun weatherNeverHasMoreThanSixtyParticles() {
        for (kind in WeatherKind.entries) {
            assertTrue(WeatherParticles.generate(kind).size <= WeatherParticles.MAX_WEATHER_PARTICLES)
        }
        assertEquals(60, WeatherParticles.generate(WeatherKind.RAIN, count = 60).size)
    }

    @Test
    fun mineFlashesAreLimitedToThreePerSecond() {
        // Ten mines in the first second, one every 100 ms: at most three may survive.
        val mines = (0 until 10).map { EffectEvent(EffectKind.MINE_FLASH, listOf(it), startMillis = it * 100L) }
        val kept = limitMineFlashes(mines).filter { it.kind == EffectKind.MINE_FLASH }
        assertTrue("kept ${kept.size}", kept.size <= 3)
        for (pair in kept.zipWithNext()) {
            assertTrue(pair.second.startMillis - pair.first.startMillis >= EffectEvent.MINE_MIN_GAP_MS)
        }
    }

    @Test
    fun otherEffectsAreNeverDropped() {
        val events = listOf(
            EffectEvent(EffectKind.SPLASH, listOf(1), startMillis = 0),
            EffectEvent(EffectKind.SPLASH, listOf(2), startMillis = 0),
            EffectEvent(EffectKind.MINE_FLASH, listOf(3), startMillis = 0),
        )
        assertEquals(3, limitMineFlashes(events).size)
    }

    @Test
    fun durationsFollowDesignSection6() {
        assertEquals(420L, EffectEvent(EffectKind.SPLASH, listOf(0)).durationMillis)
        assertEquals(480L + 2000L, EffectEvent(EffectKind.EXPLOSION, listOf(0)).durationMillis)
        assertEquals(900L + 30L * 3, EffectEvent(EffectKind.SINK, listOf(0, 1), autoWater = listOf(2, 3, 4)).durationMillis)
        assertEquals(600L, EffectEvent(EffectKind.SONAR_PULSE, listOf(0)).durationMillis)
        assertEquals(55L * 4, EffectEvent(EffectKind.TORPEDO_TRAIL, listOf(0, 1, 2, 3)).durationMillis)
        assertEquals(500L, EffectEvent(EffectKind.RECON_SHADOW, listOf(0)).durationMillis)
        assertEquals(90L * 5, EffectEvent(EffectKind.CROSS, listOf(0, 1, 2, 3, 4)).durationMillis)
        assertEquals(500L, EffectEvent(EffectKind.MINE_FLASH, listOf(0)).durationMillis)
    }
}
