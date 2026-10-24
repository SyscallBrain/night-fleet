package pt.tiago.nightfleet.backup

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.BuildConfig
import pt.tiago.nightfleet.crash.CrashLog
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.RecoveryNotice
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.rank
import pt.tiago.nightfleet.data.totalStars
import pt.tiago.nightfleet.diagnostics.DebugChecks

/** Plain facts about the device and build. Passed in so that [DiagnosticsReport.render] stays pure. */
data class DiagnosticEnvironment(
    val appVersion: String,
    val versionCode: Int,
    val androidVersion: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
)

/**
 * The text report for a bug report. It contains versions, device model, settings values and
 * profile counters. It never contains player names, the raw profile, the saved matches or the
 * logbook contents.
 */
object DiagnosticsReport {
    private const val CRASH_TAIL_CHARS = 8 * 1024

    suspend fun build(context: Context): String {
        val container = AppContainer.from(context)
        val profile = container.profile.load()
        val settings = container.settings.current()
        val logbookEntries = container.logbook.load().size
        val crash = withContext(Dispatchers.IO) {
            if (CrashLog.hasReport(context)) CrashLog.latestReport(context).toString(Charsets.UTF_8) else null
        }
        val environment = DiagnosticEnvironment(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
        // Debug builds only: the results of the last on-device check run (see DebugChecks), if any.
        val debugChecks = if (BuildConfig.DEBUG) DebugChecks.lastRun?.let { DebugChecks.render(it) } else null
        return render(environment, profile, settings, logbookEntries, container.profile.notice.value, crash, debugChecks)
    }

    fun render(
        environment: DiagnosticEnvironment,
        profile: Profile,
        settings: Settings,
        logbookEntries: Int,
        notice: RecoveryNotice?,
        crashReport: String?,
        debugChecks: String? = null,
    ): String = buildString {
        appendLine("Night Fleet diagnostics")
        appendLine("app: ${environment.appVersion} (${environment.versionCode})")
        appendLine("android: ${environment.androidVersion} (API ${environment.apiLevel})")
        appendLine("device: ${environment.manufacturer} ${environment.model}")
        appendLine()
        appendLine("[profile]")
        appendLine("schema: ${profile.schemaVersion}")
        appendLine("stars: ${profile.totalStars()}")
        appendLine("rank: ${profile.rank().name}")
        appendLine("hard bosses won: ${profile.defeatedAdmirals[Difficulty.HARD]?.size ?: 0}")
        for (difficulty in Difficulty.entries) {
            val stats = profile.stats[difficulty]
            appendLine(
                "${difficulty.name.lowercase()}: sea ${profile.unlockedSeas[difficulty] ?: 1}, " +
                    "battles ${stats?.battles ?: 0}, wins ${stats?.wins ?: 0}, shots ${stats?.shots ?: 0}",
            )
        }
        appendLine("variants: ${profile.variants.size}")
        appendLine("achievements unlocked: ${profile.achievements.count { it.value.unlockedAtEpochDay != null }}")
        appendLine("formations: ${profile.formations.values.sumOf { it.size }}")
        appendLine("daily days recorded: ${profile.dailyHistory.size}")
        appendLine("logbook entries: $logbookEntries")
        appendLine("recovery: ${notice?.let { "restored=${it.restoredFromBackup}, fresh=${it.startedFresh}, moved=${it.movedAside}" } ?: "none"}")
        appendLine()
        appendLine("[settings]")
        appendLine("sfx=${settings.sfxVolume} music=${settings.musicVolume} vibration=${settings.vibration}")
        appendLine("confirmShot=${settings.confirmShot} admiralSpeed=${settings.admiralSpeed} weather=${settings.weatherEffects}")
        appendLine("theme=${settings.theme} palette=${settings.palette} reducedMotion=${settings.reducedMotion}")
        appendLine("largerText=${settings.largerText} highContrastGrid=${settings.highContrastGrid} language=${settings.language}")
        if (crashReport != null) {
            appendLine()
            appendLine("[last crash]")
            append(crashReport.takeLast(CRASH_TAIL_CHARS))
        }
        if (debugChecks != null) {
            appendLine()
            append(debugChecks)
        }
    }
}
