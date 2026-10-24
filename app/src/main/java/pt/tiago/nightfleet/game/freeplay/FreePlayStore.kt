package pt.tiago.nightfleet.game.freeplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.data.DurableFile
import java.io.File

/** What the free play remembers between games: the last setup and the attempt counter that seeds the next game. */
@Serializable
data class FreePlayState(
    val config: FreePlayConfig = FreePlayConfig(),
    /** Games started in free play so far. Each game draws its seed from this counter. */
    val attempts: Int = 0,
) {
    /** Seed of the next game (free play takes its seed from a local counter). */
    val nextAttemptSeed: Long get() = SeedStreams.attemptSeed(LEVEL_SEED, attempts)

    companion object {
        /** Fixed level seed of the free play; the counter makes every game different. */
        const val LEVEL_SEED: Long = 0x4E16_F8EE_0000_0007L
    }
}

/** The free play state in a small durable file (DurableFile: atomic writes with a backup). */
class FreePlayStore(directory: File) {
    private val file = DurableFile(directory, "free-play")
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): FreePlayState = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.read { text -> runCatching { json.decodeFromString(FreePlayState.serializer(), text) }.getOrNull() }.value
                ?: FreePlayState()
        }
    }

    suspend fun save(state: FreePlayState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.write(json.encodeToString(FreePlayState.serializer(), state))
        }
    }
}
