package pt.tiago.nightfleet.game.modes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.data.DurableFile
import java.io.File

/**
 * The runs of the patrol and of the tournament, each in its own durable file (atomic writes with a backup). A run is
 * written after every change between battles; the battle in progress is in the saved match slot of its kind. A run
 * whose file cannot be read is treated as no run, so a damaged file never blocks the mode.
 */
class ModeRunStore(directory: File) {
    private val mutex = Mutex()
    private val patrolFile = DurableFile(directory, "run-patrol")
    private val tournamentFile = DurableFile(directory, "run-tournament")

    suspend fun patrol(): PatrolRun? = read(patrolFile, PatrolRun.serializer())

    suspend fun savePatrol(run: PatrolRun) = write(patrolFile, PatrolRun.serializer(), run)

    suspend fun clearPatrol() = clear(patrolFile)

    suspend fun tournament(): TournamentRun? = read(tournamentFile, TournamentRun.serializer())

    suspend fun saveTournament(run: TournamentRun) = write(tournamentFile, TournamentRun.serializer(), run)

    suspend fun clearTournament() = clear(tournamentFile)

    private suspend fun <T : Any> read(file: DurableFile, serializer: kotlinx.serialization.KSerializer<T>): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.read { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() }.value
        }
    }

    private suspend fun <T : Any> write(file: DurableFile, serializer: kotlinx.serialization.KSerializer<T>, value: T) = mutex.withLock {
        withContext(Dispatchers.IO) { file.write(json.encodeToString(serializer, value)) }
    }

    private suspend fun clear(file: DurableFile) = mutex.withLock {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
