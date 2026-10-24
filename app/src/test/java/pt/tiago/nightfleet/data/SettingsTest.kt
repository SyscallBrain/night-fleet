package pt.tiago.nightfleet.data

import androidx.datastore.core.DataStoreFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SettingsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = newTempDirectory("settings")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun defaultsMatchPlanSectionThirteen() {
        val s = Settings()
        assertEquals(0.8f, s.sfxVolume, 0f)
        assertEquals(0.4f, s.musicVolume, 0f)
        assertTrue(s.vibration)
        assertEquals(false, s.confirmShot)
        assertEquals(AdmiralSpeed.NORMAL, s.admiralSpeed)
        assertTrue(s.swapSeasOnAdmiralTurn)
        assertTrue(s.screenShake)
        assertEquals(false, s.leftHanded)
        assertEquals(ThemeChoice.CHART, s.theme)
        assertEquals(false, s.grandAdmiralFinish)
        assertEquals(DEFAULT_PAINT, s.fleetPaint)
        assertTrue(s.weatherEffects)
        assertEquals(PaletteChoice.NORMAL, s.palette)
        assertEquals(MotionPreference.FOLLOW_SYSTEM, s.reducedMotion)
        assertEquals(false, s.largerText)
        assertEquals(false, s.highContrastGrid)
        assertEquals(LanguageChoice.SYSTEM, s.language)
    }

    @Test
    fun sanitizedClampsVolumesAndReplacesNotANumber() {
        val loud = Settings(sfxVolume = 3f, musicVolume = -1f).sanitized()
        assertEquals(1f, loud.sfxVolume, 0f)
        assertEquals(0f, loud.musicVolume, 0f)
        val broken = Settings(sfxVolume = Float.NaN).sanitized()
        assertEquals(Settings().sfxVolume, broken.sfxVolume, 0f)
    }

    @Test
    fun unknownEnumValuesFallBackToDefaultsWhenReadFromDisk() = runBlocking {
        val text = """{"theme":"NEON","palette":"TRITANOPIA","sfxVolume":0.5}"""
        val read = SettingsSerializer.readFrom(ByteArrayInputStream(text.toByteArray()))
        assertEquals(ThemeChoice.CHART, read.theme)
        assertEquals(PaletteChoice.TRITANOPIA, read.palette)
        assertEquals(0.5f, read.sfxVolume, 0f)
    }

    @Test
    fun unreadableSettingsFileGivesDefaults() = runBlocking {
        val read = SettingsSerializer.readFrom(ByteArrayInputStream("{not json".toByteArray()))
        assertEquals(Settings(), read)
    }

    @Test
    fun repositoryAppliesEveryUpdateAndKeepsTheOthers() = runBlocking {
        // DataStore allows one active instance per file, so the same repository is used throughout.
        val file = File(dir, "settings.json")
        val repository = SettingsRepository(
            DataStoreFactory.create(serializer = SettingsSerializer, produceFile = { file }),
        )

        repository.setTheme(ThemeChoice.HOLO)
        repository.setSfxVolume(0.25f)
        repository.update { it.copy(largerText = true, fleetPaint = "gold") }

        val settings = repository.current()
        assertTrue(file.isFile)
        assertEquals(ThemeChoice.HOLO, settings.theme)
        assertEquals(0.25f, settings.sfxVolume, 0f)
        assertTrue(settings.largerText)
        assertEquals("gold", settings.fleetPaint)
    }

    @Test
    fun serializerWritesWhatItReads() = runBlocking {
        val original = Settings(theme = ThemeChoice.PAPER, reducedMotion = MotionPreference.ON, weatherEffects = false)
        val output = ByteArrayOutputStream()
        SettingsSerializer.writeTo(original, output)
        assertEquals(original, SettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }
}
