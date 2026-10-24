package pt.tiago.nightfleet.leveltool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialCatalogTest {

    private fun error(file: String, message: String) = Problem(file, "$", message)

    @Test
    fun campaignSizeAndPackGapsAreAcceptedOnlyWhenPartial() {
        val gaps = listOf(
            error("catalog.json", "\$.seas: strict: 12 seas required, found 2"),
            error("daily/pool.json", "daily pool is missing"),
            error("endless/p06.json", "endless pack is missing"),
        )
        assertEquals(gaps, gaps.withPartialCatalog(partial = false))
        assertTrue(gaps.withPartialCatalog(partial = true).isEmpty())
    }

    @Test
    fun everyOtherErrorStillFails() {
        val broken = error("missions/s01/s01m001.json", "decode failed")
        val uncalibrated = error("certificates/s01m001-E.json", "UNCALIBRATED variant")
        val remaining = listOf(broken, uncalibrated).withPartialCatalog(partial = true)
        assertEquals(listOf(broken, uncalibrated), remaining)
    }

    @Test
    fun aMissingMissionFileIsNotAPackGap() {
        val missing = error("missions/s01/s01m002.json", "mission file is missing")
        assertFalse(missing.isPartialCatalogGap())
    }

    @Test
    fun warningsAreNeverGaps() {
        val warning = Problem("catalog.json", "\$", "strict: note", Severity.WARNING)
        assertFalse(warning.isPartialCatalogGap())
    }
}
