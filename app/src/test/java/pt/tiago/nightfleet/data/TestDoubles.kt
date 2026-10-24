package pt.tiago.nightfleet.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files

/** In-memory [SettingsSource] for tests that do not need DataStore. */
class FakeSettings(initial: Settings = Settings()) : SettingsSource {
    private val flow = MutableStateFlow(initial)
    override val settings: Flow<Settings> = flow
    override suspend fun current(): Settings = flow.value
    override suspend fun update(transform: (Settings) -> Settings) {
        flow.value = transform(flow.value)
    }
}

/** A fresh temporary directory for one test. Callers delete it in @After. */
fun newTempDirectory(prefix: String): File = Files.createTempDirectory("nf-$prefix").toFile()

/** Runs [block] and fails the test unless it throws [T]. */
inline fun <reified T : Throwable> assertThrowsType(message: String, block: () -> Unit) {
    val thrown: Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }
    org.junit.Assert.assertTrue("$message: expected ${T::class.java.simpleName}, got $thrown", thrown is T)
}
