package pt.tiago.nightfleet.ui.screens.settings

import pt.tiago.nightfleet.data.LanguageChoice
import pt.tiago.nightfleet.data.MotionPreference
import pt.tiago.nightfleet.data.PaletteChoice
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.ThemeChoice
import pt.tiago.nightfleet.meta.PaintCatalog
import pt.tiago.nightfleet.ui.theme.ColorblindPalette
import pt.tiago.nightfleet.ui.theme.ThemeId
import kotlin.math.roundToInt

/*
 * Pure mapping from the stored settings to what the screens and the theme need. No Android types here,
 * so every rule is covered by the JVM tests.
 */

/** Text scale of "texto maior". */
const val LARGER_TEXT_SCALE = 1.15f

fun ThemeChoice.toThemeId(): ThemeId = when (this) {
    ThemeChoice.CHART -> ThemeId.CHART
    ThemeChoice.SONAR -> ThemeId.SONAR
    ThemeChoice.PAPER -> ThemeId.PAPER
    ThemeChoice.HOLO -> ThemeId.HOLO
}

fun ThemeId.toChoice(): ThemeChoice = when (this) {
    ThemeId.CHART -> ThemeChoice.CHART
    ThemeId.SONAR -> ThemeChoice.SONAR
    ThemeId.PAPER -> ThemeChoice.PAPER
    ThemeId.HOLO -> ThemeChoice.HOLO
}

fun PaletteChoice.toPalette(): ColorblindPalette = when (this) {
    PaletteChoice.NORMAL -> ColorblindPalette.NORMAL
    PaletteChoice.DEUTERANOPIA -> ColorblindPalette.DEUTERANOPIA
    PaletteChoice.PROTANOPIA -> ColorblindPalette.PROTANOPIA
    PaletteChoice.TRITANOPIA -> ColorblindPalette.TRITANOPIA
}

/** Follow the system unless the player chose on or off. */
fun MotionPreference.resolveReducedMotion(systemReduced: Boolean): Boolean = when (this) {
    MotionPreference.FOLLOW_SYSTEM -> systemReduced
    MotionPreference.ON -> true
    MotionPreference.OFF -> false
}

/** Text scale for the whole app: 1.15 with "texto maior", otherwise 1. */
fun Settings.textScale(): Float = if (largerText) LARGER_TEXT_SCALE else 1f

/**
 * The Grand Admiral finish is shown only when the player chose it AND has unlocked it.
 * A stored `true` without the unlock is ignored, so a copied or imported file cannot force the finish.
 */
fun Settings.grandAdmiralActive(profile: Profile): Boolean =
    grandAdmiralFinish && PaintCatalog.grandAdmiralFinishUnlocked(profile)

/** The screen shake and the weather need motion, so reduced motion turns them off. */
fun Settings.screenShakeActive(reducedMotion: Boolean): Boolean = screenShake && !reducedMotion

fun Settings.weatherActive(reducedMotion: Boolean): Boolean = weatherEffects && !reducedMotion

/** Whole percentage for a 0..1 volume, for the value labels. */
fun volumePercent(volume: Float): Int = (volume.coerceIn(0f, 1f) * 100).roundToInt()

/** Language tag handed to the platform. Null means "follow the system". */
fun LanguageChoice.languageTag(): String? = when (this) {
    LanguageChoice.SYSTEM -> null
    LanguageChoice.EN -> "en"
    LanguageChoice.PT_PT -> "pt-PT"
}
