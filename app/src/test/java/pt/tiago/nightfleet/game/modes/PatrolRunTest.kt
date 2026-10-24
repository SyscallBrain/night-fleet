package pt.tiago.nightfleet.game.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty

class PatrolRunTest {

    @Test
    fun startingReserveMatchesThePlan() {
        assertEquals(60, PatrolRules.startingReserve(Difficulty.EASY))
        assertEquals(50, PatrolRules.startingReserve(Difficulty.MEDIUM))
        assertEquals(40, PatrolRules.startingReserve(Difficulty.HARD))
    }

    @Test
    fun refundIsThreeShotsPerShipSunk() {
        assertEquals(0, PatrolRules.refund(0))
        assertEquals(15, PatrolRules.refund(5))
        assertThrows(IllegalArgumentException::class.java) { PatrolRules.refund(-1) }
    }

    @Test
    fun aNewRunStartsAtSeaOneWithTheFullReserve() {
        val run = PatrolRun.start(Difficulty.HARD, runSeed = 7L)
        assertEquals(1, run.sea)
        assertEquals(40, run.reserve)
        assertEquals(0, run.seasCleared)
    }

    @Test
    fun aClearedSeaPaysItsShotsAndRefundsThreePerShip() {
        val run = PatrolRun.start(Difficulty.MEDIUM, runSeed = 1L)
        val next = run.after(SeaEnd.Cleared(shotsUsed = 20, shipsSunk = 4))
        assertEquals(50 - 20 + 12, next?.reserve)
        assertEquals(2, next?.sea)
        assertEquals(1, next?.seasCleared)
    }

    @Test
    fun clearingWithTheLastShotStillRefunds() {
        val run = PatrolRun(runSeed = 1L, difficulty = Difficulty.EASY, sea = 3, reserve = 10, seasCleared = 2)
        val next = run.after(SeaEnd.Cleared(shotsUsed = 10, shipsSunk = 5))
        assertEquals(15, next?.reserve)
        assertEquals(3, next?.seasCleared)
    }

    @Test
    fun aLostSeaEndsTheRun() {
        val run = PatrolRun.start(Difficulty.EASY, runSeed = 1L)
        assertNull(run.after(SeaEnd.Lost))
    }

    @Test
    fun aClearedSeaCannotUseMoreThanTheReserveOrNoShots() {
        val run = PatrolRun.start(Difficulty.MEDIUM, runSeed = 1L)
        assertThrows(IllegalArgumentException::class.java) { run.after(SeaEnd.Cleared(shotsUsed = 0, shipsSunk = 5)) }
        assertThrows(IllegalArgumentException::class.java) { run.after(SeaEnd.Cleared(shotsUsed = 51, shipsSunk = 5)) }
    }

    @Test
    fun aRunStateMustBeConsistent() {
        assertThrows(IllegalArgumentException::class.java) {
            PatrolRun(runSeed = 1L, difficulty = Difficulty.MEDIUM, sea = 2, reserve = 5, seasCleared = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PatrolRun(runSeed = 1L, difficulty = Difficulty.MEDIUM, sea = 1, reserve = 0)
        }
    }

    @Test
    fun reserveAccountingHoldsOverSeveralSeas() {
        var run: PatrolRun? = PatrolRun.start(Difficulty.MEDIUM, runSeed = 9L)
        run = run?.after(SeaEnd.Cleared(shotsUsed = 30, shipsSunk = 3))
        assertEquals(29, run?.reserve)
        run = run?.after(SeaEnd.Cleared(shotsUsed = 29, shipsSunk = 3))
        assertEquals(9, run?.reserve)
        assertEquals(2, run?.seasCleared)
        assertNull(run?.after(SeaEnd.Lost))
    }

    @Test
    fun aReserveThatReachesZeroWithoutRefundEndsTheRun() {
        val run = PatrolRun(runSeed = 1L, difficulty = Difficulty.HARD, sea = 2, reserve = 3, seasCleared = 1)
        assertNull(run.after(SeaEnd.Cleared(shotsUsed = 3, shipsSunk = 0)))
    }

    @Test
    fun seedsAreDeterministicAndDifferPerSeaAndPerRun() {
        assertEquals(PatrolRun.seaSeed(5L, 3), PatrolRun.seaSeed(5L, 3))
        assertNotEquals(PatrolRun.seaSeed(5L, 1), PatrolRun.seaSeed(5L, 2))
        assertNotEquals(PatrolRun.seaSeed(5L, 1), PatrolRun.seaSeed(6L, 1))
    }

    @Test
    fun matchIdsAreUniquePerSeaAndCarryThePatrolPrefix() {
        val run = PatrolRun.start(Difficulty.EASY, runSeed = 42L)
        val second = PatrolRun(runSeed = 42L, difficulty = Difficulty.EASY, sea = 2, reserve = 70, seasCleared = 1)
        assertEquals(true, run.matchId.startsWith("pa-"))
        assertNotEquals(run.matchId, second.matchId)
    }
}
