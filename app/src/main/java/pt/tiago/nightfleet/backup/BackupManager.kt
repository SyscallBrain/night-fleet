package pt.tiago.nightfleet.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.data.LogbookStore
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.data.ProfileStore
import pt.tiago.nightfleet.data.SavedMatchStore
import pt.tiago.nightfleet.data.SettingsSource
import pt.tiago.nightfleet.game.modes.ModeRunStore
import java.io.InputStream
import java.io.OutputStream

/**
 * Export and import of the whole progress. The Activity code picks the file with the system
 * document picker (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT) and passes the streams here.
 * Import is two steps: [importFrom] verifies and decodes without writing, [applyImport] writes
 * after the user has confirmed.
 */
class BackupManager(
    private val profile: ProfileStore,
    private val logbook: LogbookStore,
    private val matches: SavedMatchStore,
    private val settings: SettingsSource,
    private val codec: ProfileCodec = ProfileCodec(),
    /** The patrol and tournament runs. Null leaves them out of backups (tests of the older format). */
    private val runs: ModeRunStore? = null,
) {
    /** The current state of everything a backup carries. Unfinished matches are included only if resumable. */
    suspend fun snapshot(): BackupPayload = withContext(Dispatchers.IO) {
        val current = profile.load()
        BackupPayload(
            profile = current,
            logbook = logbook.load(),
            savedMatches = MatchKind.entries.mapNotNull { matches.activeMatch(it, current) },
            settings = settings.current(),
            patrolRun = runs?.patrol(),
            tournamentRun = runs?.tournament(),
        )
    }

    suspend fun exportBytes(): ByteArray = withContext(Dispatchers.IO) {
        BackupDocument.encode(snapshot(), codec)
    }

    suspend fun exportTo(output: OutputStream) {
        val bytes = exportBytes()
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    /** Reads and verifies a backup. Writes nothing. Throws on a damaged, foreign or too-new file. */
    suspend fun importFrom(input: InputStream): ImportPreview = withContext(Dispatchers.IO) {
        importBytes(readBounded(input))
    }

    fun importBytes(bytes: ByteArray): ImportPreview = ImportPreview(BackupDocument.decode(bytes, codec))

    /**
     * Replaces the progress with the preview. The previous profile is kept as
     * `profile.before-import.json`. Settings are kept unless [importSettings] is true.
     */
    suspend fun applyImport(preview: ImportPreview, importSettings: Boolean = false) {
        val payload = preview.payload
        profile.replaceAll(payload.profile)
        logbook.replaceAll(payload.logbook)
        MatchKind.entries.forEach { matches.clear(it) }
        payload.savedMatches.forEach { matches.save(it) }
        runs?.let { store ->
            payload.patrolRun?.let { store.savePatrol(it) } ?: store.clearPatrol()
            payload.tournamentRun?.let { store.saveTournament(it) } ?: store.clearTournament()
        }
        if (importSettings) settings.update { payload.settings }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= BackupDocument.MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, BackupDocument.MAX_BYTES + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        require(output.size() <= BackupDocument.MAX_BYTES) { "Backup too large" }
        return output.toByteArray()
    }
}
