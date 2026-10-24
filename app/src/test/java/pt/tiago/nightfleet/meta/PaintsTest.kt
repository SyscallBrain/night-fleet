package pt.tiago.nightfleet.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.VariantProgress

class PaintsTest {

    private fun stars(total: Int): Map<String, VariantProgress> =
        (1..total).associate { n -> "s01m%03d:EASY".format((n - 1) % 600 + 1) + "#$n" to VariantProgress(stars = 1) }

    @Test
    fun catalogHasTwelveBossPaintsFourRankPaintsAndTheDefault() {
        assertEquals(12, PaintCatalog.bossPaints.size)
        assertEquals(4, PaintCatalog.rankPaints.size)
        assertEquals(16, PaintCatalog.unlockable)
        assertEquals(17, PaintCatalog.all.size)
        assertEquals(PaintCatalog.all.map { it.id }.toSet().size, PaintCatalog.all.size)
    }

    @Test
    fun freshProfileOnlyHasTheDefaultPaint() {
        assertEquals(setOf(PaintCatalog.DEFAULT_ID), PaintCatalog.unlockedIds(Profile()))
    }

    @Test
    fun aBossPaintUnlocksOnAnyDifficulty() {
        val coral = PaintCatalog.bossPaintId("coral")
        assertFalse(PaintCatalog.isUnlocked(Profile(), coral))
        assertTrue(PaintCatalog.isUnlocked(Profile(defeatedAdmirals = mapOf(Difficulty.EASY to setOf("coral"))), coral))
        assertTrue(PaintCatalog.isUnlocked(Profile(defeatedAdmirals = mapOf(Difficulty.HARD to setOf("coral"))), coral))
    }

    @Test
    fun rankPaintsFollowTheDerivedRank() {
        val midshipman = PaintCatalog.rankPaintId(Rank.MIDSHIPMAN)
        assertFalse(PaintCatalog.isUnlocked(Profile(variants = stars(299)), midshipman))
        assertTrue(PaintCatalog.isUnlocked(Profile(variants = stars(300)), midshipman))
    }

    @Test
    fun grandAdmiralFinishNeedsTwelveBossesOnHardNotStars() {
        val ids = Admirals.all.map { it.id }
        val manyStars = Profile(variants = stars(5400))
        assertFalse(PaintCatalog.grandAdmiralFinishUnlocked(manyStars))
        assertEquals(12, PaintCatalog.hardBossesMissing(manyStars))

        val easyOnly = Profile(defeatedAdmirals = mapOf(Difficulty.EASY to ids.toSet()), variants = stars(5400))
        assertFalse(PaintCatalog.grandAdmiralFinishUnlocked(easyOnly))

        val elevenHard = Profile(defeatedAdmirals = mapOf(Difficulty.HARD to ids.dropLast(1).toSet()))
        assertFalse(PaintCatalog.grandAdmiralFinishUnlocked(elevenHard))

        val twelveHard = Profile(defeatedAdmirals = mapOf(Difficulty.HARD to ids.toSet()))
        assertTrue(PaintCatalog.grandAdmiralFinishUnlocked(twelveHard))
        assertEquals(0, PaintCatalog.hardBossesMissing(twelveHard))
    }

    @Test
    fun paintLookupFindsKnownIdsOnly() {
        assertEquals(Paint.Kind.DEFAULT, PaintCatalog.find(PaintCatalog.DEFAULT_ID)?.kind)
        assertEquals(null, PaintCatalog.find("boss-nobody"))
    }
}
