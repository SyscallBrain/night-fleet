package pt.tiago.nightfleet.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class AdmiralSpeed { NORMAL, FAST }

@Serializable
enum class ThemeChoice { CHART, SONAR, PAPER, HOLO }

@Serializable
enum class PaletteChoice { NORMAL, DEUTERANOPIA, PROTANOPIA, TRITANOPIA }

@Serializable
enum class MotionPreference { FOLLOW_SYSTEM, ON, OFF }

@Serializable
enum class LanguageChoice { SYSTEM, EN, PT_PT }

/** Every setting and its default value. */
@Serializable
data class Settings(
    val sfxVolume: Float = 0.8f,
    val musicVolume: Float = 0.4f,
    val vibration: Boolean = true,
    val confirmShot: Boolean = false,
    val admiralSpeed: AdmiralSpeed = AdmiralSpeed.NORMAL,
    val swapSeasOnAdmiralTurn: Boolean = true,
    val screenShake: Boolean = true,
    val leftHanded: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.CHART,
    val grandAdmiralFinish: Boolean = false,
    val fleetPaint: String = DEFAULT_PAINT,
    val weatherEffects: Boolean = true,
    val palette: PaletteChoice = PaletteChoice.NORMAL,
    val reducedMotion: MotionPreference = MotionPreference.FOLLOW_SYSTEM,
    val largerText: Boolean = false,
    val highContrastGrid: Boolean = false,
    val language: LanguageChoice = LanguageChoice.SYSTEM,
)

/** Clamps numbers and fixes impossible text. Called on every value read from disk or a backup. */
fun Settings.sanitized(): Settings = copy(
    sfxVolume = if (sfxVolume.isFinite()) sfxVolume.coerceIn(0f, 1f) else Settings().sfxVolume,
    musicVolume = if (musicVolume.isFinite()) musicVolume.coerceIn(0f, 1f) else Settings().musicVolume,
    fleetPaint = fleetPaint.takeIf { it.isNotBlank() && it.length <= 64 } ?: DEFAULT_PAINT,
)

/** Where settings live. [SettingsRepository] implements it on DataStore; tests use an in-memory fake. */
interface SettingsSource {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
}
