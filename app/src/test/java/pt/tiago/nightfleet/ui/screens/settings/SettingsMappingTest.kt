package pt.tiago.nightfleet.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.LanguageChoice
import pt.tiago.nightfleet.data.MotionPreference
import pt.tiago.nightfleet.data.PaletteChoice
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.ThemeChoice
import pt.tiago.nightfleet.ui.theme.ColorblindPalette
import pt.tiago.nightfleet.ui.theme.ThemeId

class SettingsMappingTest {

    @Test
    fun themeChoiceAndThemeIdRoundTripForEveryTheme() {
        for (choice in ThemeChoice.entries) {
            assertEquals(choice, choice.toThemeId().toChoice())
        }
        for (id in ThemeId.entries) {
            assertEquals(id, id.toChoice().toThemeId())
        }
    }

    @Test
    fun paletteChoicesMapOneToOne() {
        assertEquals(ColorblindPalette.NORMAL, PaletteChoice.NORMAL.toPalette())
        assertEquals(ColorblindPalette.DEUTERANOPIA, PaletteChoice.DEUTERANOPIA.toPalette())
        assertEquals(ColorblindPalette.PROTANOPIA, PaletteChoice.PROTANOPIA.toPalette())
        assertEquals(ColorblindPalette.TRITANOPIA, PaletteChoice.TRITANOPIA.toPalette())
    }

    @Test
    fun reducedMotionFollowsTheSystemUnlessChosen() {
        assertTrue(MotionPreference.FOLLOW_SYSTEM.resolveReducedMotion(systemReduced = true))
        assertFalse(MotionPreference.FOLLOW_SYSTEM.resolveReducedMotion(systemReduced = false))
        assertTrue(MotionPreference.ON.resolveReducedMotion(systemReduced = false))
        assertFalse(MotionPreference.OFF.resolveReducedMotion(systemReduced = true))
    }

    @Test
    fun largerTextScalesByOnePointOneFive() {
        assertEquals(1f, Settings().textScale(), 0f)
        assertEquals(LARGER_TEXT_SCALE, Settings(largerText = true).textScale(), 0f)
        assertEquals(1.15f, LARGER_TEXT_SCALE, 0.0001f)
    }

    @Test
    fun grandAdmiralFinishNeedsTheUnlockNotJustTheSwitch() {
        val stored = Settings(grandAdmiralFinish = true)
        assertFalse(stored.grandAdmiralActive(Profile()))
        val twelveHard = Profile(
            defeatedAdmirals = mapOf(pt.tiago.nightfleet.data.Difficulty.HARD to pt.tiago.nightfleet.meta.Admirals.all.map { it.id }.toSet()),
        )
        assertTrue(stored.grandAdmiralActive(twelveHard))
        assertFalse(Settings(grandAdmiralFinish = false).grandAdmiralActive(twelveHard))
    }

    @Test
    fun shakeAndWeatherStopWithReducedMotion() {
        assertTrue(Settings(screenShake = true).screenShakeActive(reducedMotion = false))
        assertFalse(Settings(screenShake = true).screenShakeActive(reducedMotion = true))
        assertFalse(Settings(weatherEffects = true).weatherActive(reducedMotion = true))
        assertTrue(Settings(weatherEffects = true).weatherActive(reducedMotion = false))
    }

    @Test
    fun volumePercentClampsAndRounds() {
        assertEquals(40, volumePercent(0.4f))
        assertEquals(0, volumePercent(-1f))
        assertEquals(100, volumePercent(3f))
        assertEquals(80, volumePercent(0.8f))
    }

    @Test
    fun languageChoiceGivesTheLanguageTag() {
        assertNull(LanguageChoice.SYSTEM.languageTag())
        assertEquals("en", LanguageChoice.EN.languageTag())
        assertEquals("pt-PT", LanguageChoice.PT_PT.languageTag())
    }
}

class AppLocaleTest {

    @Test
    fun platformLocaleStartsAtApiThirtyThree() {
        assertFalse(AppLocale.usesPlatformApi(32))
        assertTrue(AppLocale.usesPlatformApi(33))
        assertTrue(AppLocale.usesPlatformApi(36))
    }

    @Test
    fun activityRecreatesOnlyBelowApiThirtyThree() {
        assertTrue(AppLocale.needsRecreate(26))
        assertFalse(AppLocale.needsRecreate(33))
    }

    @Test
    fun parseReadsTheStoredNameAndFallsBackToTheSystem() {
        assertEquals(LanguageChoice.PT_PT, AppLocale.parse("PT_PT"))
        assertEquals(LanguageChoice.EN, AppLocale.parse("EN"))
        assertEquals(LanguageChoice.SYSTEM, AppLocale.parse("SYSTEM"))
        assertEquals(LanguageChoice.SYSTEM, AppLocale.parse(null))
        assertEquals(LanguageChoice.SYSTEM, AppLocale.parse("FR"))
    }
}
