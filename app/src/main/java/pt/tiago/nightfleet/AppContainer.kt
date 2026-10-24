package pt.tiago.nightfleet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.audio.GameAudio
import pt.tiago.nightfleet.backup.BackupManager
import pt.tiago.nightfleet.data.LogbookStore
import pt.tiago.nightfleet.data.ProfileStore
import pt.tiago.nightfleet.data.SavedMatchStore
import pt.tiago.nightfleet.data.SettingsRepository
import java.io.File

/**
 * Manual dependency injection: the one place where the data stores are wired together.
 * Created in [App.onCreate], after CrashLog is installed. Start-up reads the profile in the
 * background and drops saved matches whose result is already committed.
 */
class AppContainer(context: Context) {
    private val progressDirectory = File(context.filesDir, "progress")
    private val startup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: SettingsRepository = SettingsRepository.create(context)
    val matches = SavedMatchStore(progressDirectory)
    val profile = ProfileStore(progressDirectory, matches = matches)
    val logbook = LogbookStore(progressDirectory)
    /** The patrol and tournament runs between battles (see game/modes). */
    val modeRuns = pt.tiago.nightfleet.game.modes.ModeRunStore(progressDirectory)
    /** The last free play setup and the game counter (see game/freeplay). */
    val freePlay = pt.tiago.nightfleet.game.freeplay.FreePlayStore(progressDirectory)
    val backup = BackupManager(profile, logbook, matches, settings, runs = modeRuns)

    /** Sound effects, ambient music and vibration, shared by every screen. */
    val audio = GameAudio(context)

    /** The campaign content shipped in the APK assets (SHA-256 checked on every read). */
    val catalog: pt.tiago.nightfleet.catalog.CatalogRepository = pt.tiago.nightfleet.catalog.CatalogRepository.fromAssets(context)

    init {
        // Best effort: a failure here must not crash start-up. The profile is loaded again on first use.
        startup.launch {
            runCatching { matches.dropCommitted(profile.load()) }
        }
    }

    companion object {
        fun from(context: Context): AppContainer = (context.applicationContext as App).container
    }
}
