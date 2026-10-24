package pt.tiago.nightfleet.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectThrottleTest {

    @Test
    fun firstCallIsAccepted() {
        val throttle = EffectThrottle<String>(50L)
        assertTrue(throttle.accept("hit", 1_000L))
    }

    @Test
    fun sameKeyInsideTheIntervalIsRejected() {
        val throttle = EffectThrottle<String>(50L)
        assertTrue(throttle.accept("hit", 1_000L))
        assertFalse(throttle.accept("hit", 1_049L))
        assertFalse(throttle.accept("hit", 1_000L))
    }

    @Test
    fun sameKeyAtTheIntervalIsAcceptedAgain() {
        val throttle = EffectThrottle<String>(50L)
        assertTrue(throttle.accept("hit", 1_000L))
        assertTrue(throttle.accept("hit", 1_050L))
    }

    @Test
    fun rejectedCallsDoNotExtendTheWindow() {
        val throttle = EffectThrottle<String>(50L)
        assertTrue(throttle.accept("hit", 0L))
        assertFalse(throttle.accept("hit", 30L))
        assertTrue(throttle.accept("hit", 50L))
    }

    @Test
    fun differentKeysAreIndependent() {
        val throttle = EffectThrottle<String>(50L)
        assertTrue(throttle.accept("hit", 0L))
        assertTrue(throttle.accept("splash", 10L))
        assertFalse(throttle.accept("splash", 20L))
    }
}
