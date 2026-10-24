package pt.tiago.nightfleet.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RanksTest {
    @Test
    fun zeroStarsIsCabinBoy() {
        assertEquals(Rank.CABIN_BOY, Ranks.rankFor(0, 0))
    }

    @Test
    fun thresholdsAreInclusiveAtTheStart() {
        assertEquals(Rank.CABIN_BOY, Ranks.rankFor(24, 0))
        assertEquals(Rank.SEAMAN, Ranks.rankFor(25, 0))
        assertEquals(Rank.SUB_LIEUTENANT, Ranks.rankFor(800, 0))
        assertEquals(Rank.SUB_LIEUTENANT, Ranks.rankFor(1_199, 0))
        assertEquals(Rank.LIEUTENANT, Ranks.rankFor(1_200, 0))
    }

    @Test
    fun everyNonGrandRankIsReachedExactlyAtItsThreshold() {
        for (rank in Rank.entries.filter { it != Rank.GRAND_ADMIRAL }) {
            assertEquals(rank, Ranks.rankFor(rank.minStars, 0))
        }
    }

    @Test
    fun admiralNeedsStarsOnlyAndIsNotGrandWithoutHardBosses() {
        assertEquals(Rank.VICE_ADMIRAL, Ranks.rankFor(5_199, 12))
        assertEquals(Rank.ADMIRAL, Ranks.rankFor(5_200, 0))
        assertEquals(Rank.ADMIRAL, Ranks.rankFor(5_200, 11))
    }

    @Test
    fun grandAdmiralNeedsTwelveHardBossesAndAllTheStars() {
        assertEquals(Rank.GRAND_ADMIRAL, Ranks.rankFor(5_200, 12))
        assertEquals(Rank.GRAND_ADMIRAL, Ranks.rankFor(5_400, 12))
        assertEquals(Rank.VICE_ADMIRAL, Ranks.rankFor(5_199, 12))
    }

    @Test
    fun negativeStarsAreTreatedAsZero() {
        assertEquals(Rank.CABIN_BOY, Ranks.rankFor(-40, 12))
    }
}
