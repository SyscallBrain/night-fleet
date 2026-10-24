package pt.tiago.nightfleet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.io.File

/** One finished battle or puzzle. Entries are stored oldest first. */
@Serializable
data class LogEntry(
    val id: String,
    val epochMillis: Long,
    val kind: MatchKind,
    val missionId: String? = null,
    val difficulty: Difficulty? = null,
    val admiralId: String? = null,
    val result: LogResult,
    val stars: Int = 0,
    val shots: Int = 0,
    val hits: Int = 0,
    val boardWidth: Int = 10,
    val boardHeight: Int = 10,
    /** Final state of the player's own sea, boardWidth x boardHeight characters, row-major. */
    val finalMapPlayer: String = "",
    /** Final state of the enemy sea in the same format. */
    val finalMapEnemy: String = "",
    /** Engine replay payload. Opaque to the store. */
    val replay: JsonElement? = null,
    val pinned: Boolean = false,
    /** The officer's hint was used: the stars are capped at two. */
    val usedHint: Boolean = false,
    /** The post-battle analysis, once it has been made. Null until then. */
    val analysis: AnalysisSummary? = null,
)

/** One of the worst shots of an analysis: its shot number, its square and the best square of that moment. */
@Serializable
data class WorstShot(
    val number: Int,
    val cell: Int,
    /** Density of the square shot at, per mille of the best one's scale (0..1000). */
    val permille: Int,
    val bestCell: Int,
    val bestPermille: Int,
)

/** The analysis of a battle, stored in its logbook entry so that it is not recomputed. */
@Serializable
data class AnalysisSummary(
    /** Shots the player fired (a salvo counts each square). */
    val shots: Int,
    /** Shots on squares that no afloat ship could occupy. */
    val wasted: Int,
    /** Numbers of the wasted shots, in play order. */
    val wastedShots: List<Int> = emptyList(),
    /** Hits as a whole percentage of the shots. */
    val precision: Int,
    /** Up to three shots that lost the most density, worst first. */
    val worst: List<WorstShot>,
    /** Shots the officer needed on the same sea, or null when its hunt did not finish. */
    val officerShots: Int? = null,
)

object LogbookRules {
    const val CAPACITY = 200
    const val MAX_PINNED = 20

    /** Appends [entry] and evicts the oldest unpinned entries while over capacity. */
    fun append(entries: List<LogEntry>, entry: LogEntry): List<LogEntry> {
        var result = entries + entry
        while (result.size > CAPACITY) {
            val oldestUnpinned = result.indexOfFirst { !it.pinned }
            if (oldestUnpinned < 0) break
            result = result.toMutableList().apply { removeAt(oldestUnpinned) }
        }
        return result
    }

    /** Returns null when the pin is refused because [MAX_PINNED] entries are already pinned. */
    fun withPinned(entries: List<LogEntry>, id: String, pinned: Boolean): List<LogEntry>? {
        val target = entries.firstOrNull { it.id == id } ?: return entries
        if (target.pinned == pinned) return entries
        if (pinned && entries.count { it.pinned } >= MAX_PINNED) return null
        return entries.map { if (it.id == id) it.copy(pinned = pinned) else it }
    }

    /** Repairs a list read from disk: bad maps are emptied, stars clamped, pins and size limited. */
    fun clamp(entries: List<LogEntry>): List<LogEntry> {
        val distinct = entries.distinctBy { it.id }.map { e ->
            val width = e.boardWidth.coerceIn(1, 12)
            val height = e.boardHeight.coerceIn(1, 12)
            val area = width * height
            e.copy(
                boardWidth = width,
                boardHeight = height,
                finalMapPlayer = e.finalMapPlayer.takeIf { it.length == area }.orEmpty(),
                finalMapEnemy = e.finalMapEnemy.takeIf { it.length == area }.orEmpty(),
                stars = e.stars.coerceIn(0, 3),
                shots = e.shots.coerceAtLeast(0),
                hits = e.hits.coerceIn(0, e.shots.coerceAtLeast(0)),
            )
        }
        var pins = 0
        val unpinnedExcess = distinct.map { e ->
            if (e.pinned && pins < MAX_PINNED) { pins++; e } else e.copy(pinned = false)
        }
        var result = unpinnedExcess
        while (result.size > CAPACITY) {
            val oldestUnpinned = result.indexOfFirst { !it.pinned }
            if (oldestUnpinned < 0) break
            result = result.toMutableList().apply { removeAt(oldestUnpinned) }
        }
        return result
    }
}

/**
 * The logbook: a ring buffer of the last [LogbookRules.CAPACITY] entries, of which up to
 * [LogbookRules.MAX_PINNED] can be pinned. Pinned entries are never evicted. Stored in its own
 * file (`logbook.json`), separate from the profile.
 */
class LogbookStore(directory: File) {
    private val file = DurableFile(directory, "logbook")
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    private var loaded = false

    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()

    suspend fun load(): List<LogEntry> = mutex.withLock { ensureLoaded() }

    /** Adds an entry. Returns false when an entry with the same id already exists. */
    suspend fun add(entry: LogEntry): Boolean = mutex.withLock {
        val current = ensureLoaded()
        if (current.any { it.id == entry.id }) return@withLock false
        writeLocked(LogbookRules.append(current, entry))
        true
    }

    /** Returns false when the entry does not exist or a pin is refused (limit reached). */
    suspend fun setPinned(id: String, pinned: Boolean): Boolean = mutex.withLock {
        val current = ensureLoaded()
        val next = LogbookRules.withPinned(current, id, pinned) ?: return@withLock false
        if (next != current) writeLocked(next)
        next.any { it.id == id && it.pinned == pinned }
    }

    /** Replaces the whole logbook after an import has been confirmed. */
    suspend fun replaceAll(entries: List<LogEntry>): List<LogEntry> = mutex.withLock {
        val clamped = LogbookRules.clamp(entries)
        writeLocked(clamped)
        clamped
    }

    private suspend fun ensureLoaded(): List<LogEntry> {
        if (loaded) return mutableEntries.value
        val result = withContext(Dispatchers.IO) { file.read(::decode) }
        val entries = result.value.orEmpty()
        loaded = true
        mutableEntries.value = entries
        return entries
    }

    private suspend fun writeLocked(entries: List<LogEntry>) {
        val text = ProfileCodec.documentJson.encodeToString(ListSerializer(LogEntry.serializer()), entries)
        withContext(Dispatchers.IO) { file.write(text) }
        mutableEntries.value = entries
    }

    private fun decode(text: String): List<LogEntry>? = runCatching {
        LogbookRules.clamp(ProfileCodec.documentJson.decodeFromString(ListSerializer(LogEntry.serializer()), text))
    }.getOrNull()
}
