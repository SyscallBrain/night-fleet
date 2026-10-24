package pt.tiago.nightfleet.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Rank

class RankProgressTest {

    @Test
    fun cabinBoyStartsTowardsSeamanWithNoProgress() {
        val progress = rankProgress(totalStars = 0, hardBossesWon = 0)
        assertEquals(Rank.CABIN_BOY, progress.rank)
        assertEquals(Rank.SEAMAN, progress.next)
        assertEquals(25, progress.starsToNext)
        assertEquals(0f, progress.fraction, 0.0001f)
    }

    @Test
    fun progressIsTheShareOfTheWayToTheNextRank() {
        val progress = rankProgress(totalStars = 50, hardBossesWon = 0)
        assertEquals(Rank.SEAMAN, progress.rank)
        assertEquals(Rank.LEADING_SEAMAN, progress.next)
        assertEquals(25, progress.starsToNext)
        assertEquals(0.5f, progress.fraction, 0.0001f)
    }

    @Test
    fun negativeStarsAreTreatedAsZero() {
        assertEquals(Rank.CABIN_BOY, rankProgress(totalStars = -10, hardBossesWon = 0).rank)
    }

    @Test
    fun admiralWithAllStarsNeedsOnlyTheHardBosses() {
        val progress = rankProgress(totalStars = 5_200, hardBossesWon = 3)
        assertEquals(Rank.ADMIRAL, progress.rank)
        assertEquals(Rank.GRAND_ADMIRAL, progress.next)
        assertEquals(0, progress.starsToNext)
        assertTrue(progress.needsBossesOnly)
        assertEquals(3f / 12f, progress.fraction, 0.0001f)
    }

    @Test
    fun grandAdmiralHasNoNextRankAndIsFull() {
        val progress = rankProgress(totalStars = 5_400, hardBossesWon = 12)
        assertEquals(Rank.GRAND_ADMIRAL, progress.rank)
        assertNull(progress.next)
        assertEquals(1f, progress.fraction, 0.0001f)
        assertFalse(progress.needsBossesOnly)
    }

    @Test
    fun insigniaFollowsTheTierOfEachRank() {
        assertEquals(Insignia(chevrons = 1, bars = 0, stars = 0, laurel = false), insigniaFor(Rank.CABIN_BOY))
        assertEquals(Insignia(chevrons = 4, bars = 0, stars = 0, laurel = false), insigniaFor(Rank.PETTY_OFFICER))
        assertEquals(Insignia(chevrons = 0, bars = 1, stars = 0, laurel = false), insigniaFor(Rank.MIDSHIPMAN))
        assertEquals(Insignia(chevrons = 0, bars = 6, stars = 0, laurel = false), insigniaFor(Rank.COMMANDER))
        assertEquals(Insignia(chevrons = 0, bars = 0, stars = 1, laurel = false), insigniaFor(Rank.CAPTAIN))
        assertEquals(Insignia(chevrons = 0, bars = 0, stars = 4, laurel = false), insigniaFor(Rank.ADMIRAL))
        assertEquals(Insignia(chevrons = 0, bars = 0, stars = 5, laurel = true), insigniaFor(Rank.GRAND_ADMIRAL))
    }
}
