package pt.tiago.nightfleet.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.DuoSession
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.VariantProgress

class DiagnosticsReportTest {
    private val environment = DiagnosticEnvironment(
        appVersion = "0.1.0",
        versionCode = 1,
        androidVersion = "16",
        apiLevel = 36,
        manufacturer = "Acme",
        model = "Phone 1",
    )

    private val profile = Profile(
        variants = mapOf("s01m001:EASY" to VariantProgress(stars = 2)),
        duoSessions = listOf(DuoSession("Ana", "Bruno", firstWins = 1)),
    )

    @Test
    fun reportHasVersionsCountersAndSettingsButNoPersonalText() {
        val text = DiagnosticsReport.render(environment, profile, Settings(), logbookEntries = 4, notice = null, crashReport = null)

        assertTrue(text.contains("app: 0.1.0 (1)"))
        assertTrue(text.contains("android: 16 (API 36)"))
        assertTrue(text.contains("device: Acme Phone 1"))
        assertTrue(text.contains("stars: 2"))
        assertTrue(text.contains("rank: CABIN_BOY"))
        assertTrue(text.contains("logbook entries: 4"))
        assertTrue(text.contains("theme=CHART"))
        assertFalse(text.contains("Ana"))
        assertFalse(text.contains("Bruno"))
        assertFalse(text.contains("s01m001"))
        assertFalse(text.contains("[last crash]"))
    }

    @Test
    fun recordedDifficultiesAreListed() {
        val text = DiagnosticsReport.render(environment, profile, Settings(), 0, null, null)
        assertTrue(text.contains("easy: sea 1"))
        assertTrue(text.contains("hard: sea 1"))
    }

    @Test
    fun crashReportIsIncludedAsATailOfBoundedSize() {
        val crash = "x".repeat(50_000) + "THE-END"
        val text = DiagnosticsReport.render(environment, profile, Settings(), 0, null, crash)
        assertTrue(text.endsWith("THE-END"))
        assertTrue(text.length < 10_000)
    }
}
