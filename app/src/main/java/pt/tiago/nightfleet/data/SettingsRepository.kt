package pt.tiago.nightfleet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Settings on DataStore (core API, one JSON file). Each update is a transaction, so readers never
 * see half of it. The core API is used because `androidx.datastore:datastore` does not expose
 * Preferences, and build files are not changed for this.
 */
class SettingsRepository(private val store: DataStore<Settings>) : SettingsSource {

    override val settings: Flow<Settings> = store.data

    override suspend fun current(): Settings = store.data.first()

    override suspend fun update(transform: (Settings) -> Settings) {
        store.updateData { transform(it).sanitized() }
    }

    suspend fun setSfxVolume(value: Float) = update { it.copy(sfxVolume = value) }
    suspend fun setMusicVolume(value: Float) = update { it.copy(musicVolume = value) }
    suspend fun setVibration(value: Boolean) = update { it.copy(vibration = value) }
    suspend fun setConfirmShot(value: Boolean) = update { it.copy(confirmShot = value) }
    suspend fun setAdmiralSpeed(value: AdmiralSpeed) = update { it.copy(admiralSpeed = value) }
    suspend fun setSwapSeasOnAdmiralTurn(value: Boolean) = update { it.copy(swapSeasOnAdmiralTurn = value) }
    suspend fun setScreenShake(value: Boolean) = update { it.copy(screenShake = value) }
    suspend fun setLeftHanded(value: Boolean) = update { it.copy(leftHanded = value) }
    suspend fun setTheme(value: ThemeChoice) = update { it.copy(theme = value) }
    suspend fun setGrandAdmiralFinish(value: Boolean) = update { it.copy(grandAdmiralFinish = value) }
    suspend fun setFleetPaint(value: String) = update { it.copy(fleetPaint = value) }
    suspend fun setWeatherEffects(value: Boolean) = update { it.copy(weatherEffects = value) }
    suspend fun setPalette(value: PaletteChoice) = update { it.copy(palette = value) }
    suspend fun setReducedMotion(value: MotionPreference) = update { it.copy(reducedMotion = value) }
    suspend fun setLargerText(value: Boolean) = update { it.copy(largerText = value) }
    suspend fun setHighContrastGrid(value: Boolean) = update { it.copy(highContrastGrid = value) }
    suspend fun setLanguage(value: LanguageChoice) = update { it.copy(language = value) }

    companion object {
        fun create(context: Context): SettingsRepository = SettingsRepository(
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                produceFile = {
                    File(context.applicationContext.filesDir, "progress/settings.json").also { it.parentFile?.mkdirs() }
                },
            ),
        )
    }
}

/**
 * JSON form of [Settings]. Unknown or invalid enum values fall back to their defaults
 * (coerceInputValues), and numbers are sanitized after reading.
 */
object SettingsSerializer : Serializer<Settings> {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings = runCatching {
        json.decodeFromString(Settings.serializer(), input.readBytes().toString(Charsets.UTF_8)).sanitized()
    }.getOrDefault(defaultValue)

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(json.encodeToString(Settings.serializer(), t.sanitized()).toByteArray(Charsets.UTF_8))
    }
}
