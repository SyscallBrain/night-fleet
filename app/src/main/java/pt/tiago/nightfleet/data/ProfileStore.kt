package pt.tiago.nightfleet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Set when the stored profile had to be repaired at load time; the UI shows it once. */
data class RecoveryNotice(
    /** The main file was unreadable and the recovery copy was restored. */
    val restoredFromBackup: Boolean,
    /** Nothing was readable: the player starts a new profile and the damaged files are kept. */
    val startedFresh: Boolean,
    val movedAside: Int,
)

/**
 * The profile, held in memory as a [StateFlow] and persisted atomically on every change.
 * All mutations go through [update] or [commitResult], which are serialized by a mutex.
 * File access runs on Dispatchers.IO.
 */
class ProfileStore(
    directory: File,
    private val codec: ProfileCodec = ProfileCodec(),
    private val matches: SavedMatchStore? = null,
) {
    private val file = DurableFile(directory, "profile")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(Profile())
    private val mutableNotice = MutableStateFlow<RecoveryNotice?>(null)
    private var loaded = false

    val state: StateFlow<Profile> = mutableState.asStateFlow()
    val notice: StateFlow<RecoveryNotice?> = mutableNotice.asStateFlow()

    /** Loads the profile if it is not loaded yet. Safe to call repeatedly. */
    suspend fun load(): Profile = mutex.withLock { ensureLoaded() }

    /** Applies [transform] to the current profile and persists the clamped result. */
    suspend fun update(transform: (Profile) -> Profile): Profile = mutex.withLock {
        val current = ensureLoaded()
        val next = ProfileValidator.clamp(transform(current))
        if (next != current) writeLocked(next)
        next
    }

    /**
     * Records the result of a finished match and clears its saved slot.
     *
     * Order matters: the profile is written first, carrying [matchId] in committedMatchIds, and
     * the slot is cleared last. If the process dies in between, the slot still holds the match,
     * but [SavedMatchStore.activeMatch] sees the id in the profile and treats the match as
     * finished. A second call with the same id changes nothing, so stars are never counted twice.
     */
    suspend fun commitResult(matchId: String, clearSlot: MatchKind?, transform: (Profile) -> Profile): Profile {
        val committed = mutex.withLock {
            val current = ensureLoaded()
            if (matchId in current.committedMatchIds) {
                current
            } else {
                val applied = ProfileValidator.clamp(transform(current))
                val next = applied.copy(committedMatchIds = (applied.committedMatchIds + matchId).takeLast(COMMITTED_MATCH_HISTORY))
                writeLocked(next)
                next
            }
        }
        if (clearSlot != null) matches?.clear(clearSlot)
        return committed
    }

    /** Starts the campaign again (see [resetCampaign] in ProfileRules) and clears the campaign slot. */
    suspend fun resetCampaign(): Profile {
        val reset = update { it.resetCampaign() }
        matches?.clear(MatchKind.CAMPAIGN)
        return reset
    }

    /** Replaces the whole profile after the user confirmed an import. The old one is kept as before-import. */
    suspend fun replaceAll(profile: Profile): Profile = mutex.withLock {
        val current = ensureLoaded()
        file.writeCopy(BEFORE_IMPORT_NAME, codec.encode(current))
        val next = ProfileValidator.clamp(profile)
        writeLocked(next)
        next
    }

    private suspend fun ensureLoaded(): Profile {
        if (loaded) return mutableState.value
        val result = withContext(Dispatchers.IO) { file.read(codec::decode) }
        val profile = result.value ?: Profile()
        if (result.recoveredFromBackup || result.movedAside > 0) {
            mutableNotice.value = RecoveryNotice(
                restoredFromBackup = result.recoveredFromBackup,
                startedFresh = result.value == null,
                movedAside = result.movedAside,
            )
        }
        if (result.recoveredFromBackup) writeLocked(profile)
        loaded = true
        mutableState.value = profile
        return profile
    }

    private suspend fun writeLocked(profile: Profile) {
        withContext(Dispatchers.IO) { file.write(codec.encode(profile)) }
        mutableState.value = profile
    }

    companion object {
        const val BEFORE_IMPORT_NAME = "profile.before-import"
    }
}
