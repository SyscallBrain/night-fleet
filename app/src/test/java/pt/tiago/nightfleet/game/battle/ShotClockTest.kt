package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotClockTest {

    /** Runs [clock] for [totalMs] in steps of [step] while [running] says so; returns the final clock, expiries and ticks. */
    private data class Run(val clock: ShotClock, val expiries: Int, val ticks: Int)

    private fun run(clock: ShotClock, totalMs: Long, step: Long = TICK, running: (Long) -> Boolean = { true }): Run {
        var current = clock
        var expiries = 0
        var ticks = 0
        var elapsed = 0L
        while (elapsed < totalMs) {
            val advance = current.advance(step, running(elapsed))
            current = advance.clock
            if (advance.expiredNow) expiries++
            if (advance.tickSecond) ticks++
            elapsed += step
        }
        return Run(current, expiries, ticks)
    }

    @Test
    fun aRestartedClockIsFullAndDisabledForNull() {
        val clock = ShotClock.restart(8)
        assertEquals(8_000L, clock.remainingMs)
        assertEquals(1f, clock.fraction)
        assertTrue(clock.enabled)
        assertFalse(ShotClock.restart(null).enabled)
    }

    @Test
    fun aStoppedClockDoesNotMove() {
        val clock = ShotClock.restart(8)
        assertEquals(clock, clock.advance(5_000, running = false).clock)
    }

    @Test
    fun theClockExpiresExactlyOnceAtZero() {
        val result = run(ShotClock.restart(4), totalMs = 8_000)
        assertEquals(0L, result.clock.remainingMs)
        assertTrue(result.clock.expired)
        assertEquals(1, result.expiries)
    }

    @Test
    fun pausedTimeDoesNotCountDown() {
        // Running for the first 2 s, paused for the next 3 s, then running: 8 s minus 4 s of running = 4 s left.
        val result = run(ShotClock.restart(8), totalMs = 7_000) { elapsed -> elapsed !in 2_000L until 5_000L }
        assertEquals(8_000L - 4_000L, result.clock.remainingMs)
        assertEquals(0, result.expiries)
    }

    @Test
    fun theDangerZoneTicksOncePerWholeSecondOfTheLastThree() {
        val result = run(ShotClock.restart(8), totalMs = 8_000)
        assertEquals(3, result.ticks)
    }

    @Test
    fun aShortClockThatStartsInsideTheDangerZoneTicksOnce() {
        // One second on the clock: the single tick of "1" happens on the first step, and no tick repeats.
        assertEquals(1, run(ShotClock.restart(1), totalMs = 1_000).ticks)
        // Two seconds: "2" on the first step, then "1" when the second changes.
        assertEquals(2, run(ShotClock.restart(2), totalMs = 2_000).ticks)
    }

    @Test
    fun theDangerZoneIsTheLastThreeSeconds() {
        assertTrue(ShotClock(8_000, 2_500).danger)
        assertTrue(ShotClock(8_000, 3_000).danger)
        assertFalse(ShotClock(8_000, 3_500).danger)
        assertFalse(ShotClock(8_000, 0).danger)
        assertFalse(ShotClock.restart(null).danger)
    }

    @Test
    fun aDisabledClockNeverExpires() {
        val result = run(ShotClock.restart(null), totalMs = 60_000)
        assertFalse(result.clock.enabled)
        assertEquals(0, result.expiries)
    }

    @Test
    fun aLongStepThatReachesZeroStillExpiresOnce() {
        val advance = ShotClock(8_000, 500).advance(10_000, running = true)
        assertTrue(advance.expiredNow)
        assertEquals(0L, advance.clock.remainingMs)
        assertFalse(advance.clock.advance(10_000, running = true).expiredNow)
    }

    @Test
    fun aRestoredBattleStartsTheTurnWithAFullClock() {
        // The clock is never saved as running: a restore builds a new clock.
        val restored = ShotClock.restart(6)
        assertEquals(6_000L, restored.remainingMs)
        assertEquals(6_000L, restored.totalMs)
    }

    private companion object {
        const val TICK = 40L
    }
}
