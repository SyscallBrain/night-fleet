package pt.tiago.nightfleet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The opaque payload of an unfinished match. The engine owns the meaning of [actions] and [seeds]:
 * the store only persists them. Resuming replays [actions] from [seeds] and checks [expectedHash].
 */
@Serializable
data class SavedMatch(
    val matchId: String,
    val kind: MatchKind,
    /** Version of the rules/AI engine that produced the actions. */
    val engineVersion: Int,
    val missionId: String? = null,
    val difficulty: Difficulty? = null,
    val admiralId: String? = null,
    val seeds: List<Long> = emptyList(),
    /** Engine placements of the player's own fleet (opaque, one string per ship). Quick battles only for now. */
    val placements: List<String> = emptyList(),
    /** Engine actions serialized one by one, in play order. */
    val actions: List<String> = emptyList(),
    /** Engine state hash after the last action, used to detect a replay mismatch. */
    val expectedHash: Long = 0,
    val savedAtEpochMillis: Long = 0,
    /**
     * The resolved setup of a battle created with [pt.tiago.nightfleet.game.battle.MatchSetup] (free play and
     * the duel), as JSON. Null for the quick battle, whose setup is rebuilt from [placements].
     */
    val setup: String? = null,
    /** Officer's hints used in the battle, so a restored battle keeps the star cap. */
    val hintsUsed: Int = 0,
    /**
     * The boss series a campaign duel belongs to, as it was when the duel started (null otherwise). A resume is refused
     * when the profile holds another series, so a duel can never be counted twice.
     */
    val bossSeries: BossSeries? = null,
)

/**
 * One active match slot per [MatchKind], stored as `match-<kind>.json` with a recovery copy.
 * A slot is rewritten after every turn. A match whose id is already in the profile is finished
 * and is never resumed (see [activeMatch]).
 */
class SavedMatchStore(private val directory: File) {
    private val mutex = Mutex()

    suspend fun save(match: SavedMatch) = mutex.withLock {
        withContext(Dispatchers.IO) { slot(match.kind).write(json.encodeToString(SavedMatch.serializer(), match)) }
    }

    suspend fun load(kind: MatchKind): SavedMatch? = mutex.withLock {
        withContext(Dispatchers.IO) { slot(kind).read { decode(it) }.value }
    }

    suspend fun clear(kind: MatchKind) = mutex.withLock {
        withContext(Dispatchers.IO) { slot(kind).delete() }
    }

    /**
     * The match that can be resumed in [kind]'s slot, or null. A slot whose match is already
     * committed to [profile] is cleared here, which finishes the interrupted commit.
     */
    suspend fun activeMatch(kind: MatchKind, profile: Profile): SavedMatch? {
        val match = load(kind) ?: return null
        if (match.matchId in profile.committedMatchIds) {
            clear(kind)
            return null
        }
        return match
    }

    /** Clears every slot whose match was already committed. Call once at start-up. */
    suspend fun dropCommitted(profile: Profile) {
        MatchKind.entries.forEach { activeMatch(it, profile) }
    }

    private fun slot(kind: MatchKind) = DurableFile(directory, "match-${kind.name.lowercase()}")

    private fun decode(text: String): SavedMatch? = runCatching {
        json.decodeFromString(SavedMatch.serializer(), text)
    }.getOrNull()

    private val json = ProfileCodec.documentJson
}
