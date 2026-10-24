package pt.tiago.nightfleet.game.puzzle

import pt.tiago.nightfleet.core.puzzle.PUZZLE_SIZES
import pt.tiago.nightfleet.core.puzzle.PUZZLE_VERSION
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleCertifier
import pt.tiago.nightfleet.core.puzzle.PuzzleCodec
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGenerator
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.SavedMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Which puzzle the screen should show: resume the saved one, generate a new one (endless), or load the certified
 * puzzle of the catalog that a campaign mission or a daily challenge names.
 */
sealed interface PuzzleRequest {
    data object Resume : PuzzleRequest

    data class New(val size: Int, val grade: PuzzleGrade) : PuzzleRequest

    /** A PUZZLE mission of the campaign: [missionId] (`s02m056`) and the difficulty of its variant. */
    data class Campaign(val missionId: String, val difficulty: Difficulty) : PuzzleRequest

    /** The daily puzzle of [date] (local date, `yyyy-MM-dd`) in [difficulty]. */
    data class Daily(val date: LocalDate, val difficulty: Difficulty) : PuzzleRequest

    /** Text form used as the ViewModel key and in saved instance state. */
    fun encode(): String = when (this) {
        Resume -> "resume"
        is New -> "new:$size:${grade.name}"
        is Campaign -> "campaign:$missionId:${difficulty.name}"
        is Daily -> "daily:$date:${difficulty.name}"
    }

    companion object {
        private val CAMPAIGN_ID = Regex("s\\d{2}m\\d{3}")

        fun decode(text: String): PuzzleRequest? {
            if (text == "resume") return Resume
            val parts = text.split(":")
            return when (parts.firstOrNull()) {
                "new" -> {
                    if (parts.size != 3) return null
                    val size = parts[1].toIntOrNull()?.takeIf { it in PUZZLE_SIZES } ?: return null
                    val grade = PuzzleGrade.entries.firstOrNull { it.name == parts[2] } ?: return null
                    New(size, grade)
                }
                "campaign" -> {
                    if (parts.size != 3 || !CAMPAIGN_ID.matches(parts[1])) return null
                    Campaign(parts[1], Difficulty.entries.firstOrNull { it.name == parts[2] } ?: return null)
                }
                "daily" -> {
                    if (parts.size != 3) return null
                    val date = runCatching { LocalDate.parse(parts[1]) }.getOrNull()?.takeIf { it.toString() == parts[1] }
                        ?: return null
                    Daily(date, Difficulty.entries.firstOrNull { it.name == parts[2] } ?: return null)
                }
                else -> null
            }
        }
    }
}

/**
 * Stars of a solved puzzle (campaign puzzles and the daily puzzle): every hint and the Verify button
 * count as one help each; 3 stars without help, 2 with one, 1 with more. Wrong cells shown by Verify are part of
 * that check and do not count again.
 */
object PuzzleStars {
    fun of(state: PuzzleUiState): Int = of(state.hintsUsed, state.verifyUsed, state.errorsShown)

    @Suppress("UNUSED_PARAMETER")
    fun of(hintsUsed: Int, verifyUsed: Boolean, errorsShown: Int): Int = when (hintsUsed + if (verifyUsed) 1 else 0) {
        0 -> 3
        1 -> 2
        else -> 1
    }
}

/** Generates puzzles on the fly for the endless puzzles. */
object PuzzleFactory {

    /** Islands per side length; each stays well under the quarter-of-the-grid limit of the generator. */
    fun islandsFor(size: Int): Int = when (size) {
        6 -> 2
        8 -> 4
        10 -> 6
        else -> throw IllegalArgumentException("Puzzle side must be 6, 8 or 10, was $size")
    }

    /**
     * A new puzzle with its certified solution, or null when the generator gave up. CPU heavy:
     * call it off the main thread.
     */
    fun create(size: Int, grade: PuzzleGrade, seed: Long): PuzzleUiState? {
        val definition = PuzzleGenerator.generate(size, grade, islandsFor(size), SplitMix64(seed)) ?: return null
        val solution = PuzzleCertifier.certify(definition)?.solution ?: return null
        return PuzzleReducer.initial(definition, solution, grade)
    }
}

/** Derived grouping of the fleet requirement row and the ships placed by the player. */
object PuzzleFleet {

    /** One group of the requirement row: ships of [length], [have] placed out of [need]. */
    data class Group(val length: Int, val need: Int, val have: Int) {
        val complete: Boolean get() = have >= need
    }

    /** Groups from the largest ship to the smallest. */
    fun groups(definition: PuzzleDefinition, grid: PlayerGrid): List<Group> {
        val placed = straightShipLengths(grid)
        return definition.fleet
            .groupingBy { it }
            .eachCount()
            .toSortedMap(compareByDescending { it })
            .map { (length, need) -> Group(length, need, placed.count { it == length }) }
    }

    /** Lengths of the 4-connected straight groups of SHIP cells. Bent groups are not ships and are skipped. */
    fun straightShipLengths(grid: PlayerGrid): List<Int> {
        val w = grid.width
        val h = grid.height
        val seen = BooleanArray(grid.cells.size)
        val lengths = ArrayList<Int>()
        for (start in grid.cells.indices) {
            if (seen[start] || grid[start] != PlayerCell.SHIP) continue
            val members = ArrayList<Int>()
            val stack = ArrayList<Int>()
            stack.add(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeAt(stack.size - 1)
                members.add(cell)
                val r = cell / w
                val c = cell % w
                val neighbours = listOf(
                    if (r > 0) cell - w else -1,
                    if (r < h - 1) cell + w else -1,
                    if (c > 0) cell - 1 else -1,
                    if (c < w - 1) cell + 1 else -1,
                )
                for (nb in neighbours) {
                    if (nb >= 0 && !seen[nb] && grid[nb] == PlayerCell.SHIP) {
                        seen[nb] = true
                        stack.add(nb)
                    }
                }
            }
            val rows = members.map { it / w }.toSet()
            val cols = members.map { it % w }.toSet()
            if (rows.size == 1 || cols.size == 1) lengths.add(members.size)
        }
        return lengths
    }
}

/** The payload stored in the ENDLESS slot of [SavedMatchStore]: the puzzle as JSON plus the player's state. */
@Serializable
data class PuzzleSnapshot(
    val puzzle: String,
    val solution: PlayerGrid,
    val grade: PuzzleGrade,
    val grid: PlayerGrid,
    val hintsUsed: Int = 0,
    val verifyUsed: Boolean = false,
    val errorsShown: Int = 0,
    val elapsedMillis: Long = 0,
)

/** Converts puzzle states to and from [SavedMatch] (the undo history is not saved). */
object PuzzleSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: PuzzleUiState): String = json.encodeToString(
        PuzzleSnapshot.serializer(),
        PuzzleSnapshot(
            puzzle = PuzzleCodec.encode(state.definition),
            solution = state.solution,
            grade = state.grade,
            grid = state.grid,
            hintsUsed = state.hintsUsed,
            verifyUsed = state.verifyUsed,
            errorsShown = state.errorsShown,
            elapsedMillis = state.elapsedMillis,
        ),
    )

    /** The state in [text], or null when the payload is not a valid puzzle snapshot. */
    fun decode(text: String): PuzzleUiState? = runCatching {
        val snapshot = json.decodeFromString(PuzzleSnapshot.serializer(), text)
        PuzzleUiState(
            definition = PuzzleCodec.decode(snapshot.puzzle),
            solution = snapshot.solution,
            grade = snapshot.grade,
            grid = snapshot.grid,
            hintsUsed = snapshot.hintsUsed,
            verifyUsed = snapshot.verifyUsed,
            errorsShown = snapshot.errorsShown,
            elapsedMillis = snapshot.elapsedMillis,
        )
    }.getOrNull()

    /**
     * The save of [state] in the slot of [kind]. Endless puzzles use the puzzle id as match id; campaign and daily
     * puzzles use a per-play id (so every play can be committed once) and name their mission or day in [missionId].
     */
    fun toSavedMatch(
        state: PuzzleUiState,
        savedAtEpochMillis: Long,
        kind: MatchKind = MatchKind.ENDLESS,
        matchId: String = state.definition.id,
        missionId: String = state.definition.id,
        difficulty: Difficulty? = null,
    ): SavedMatch = SavedMatch(
        matchId = matchId,
        kind = kind,
        engineVersion = PUZZLE_VERSION,
        missionId = missionId,
        difficulty = difficulty,
        actions = listOf(encode(state)),
        savedAtEpochMillis = savedAtEpochMillis,
    )

    /**
     * The state of a save in the slot of [kind] (ENDLESS by default). Null when the save is of another kind or the
     * payload is not a puzzle snapshot.
     */
    fun fromSavedMatch(match: SavedMatch, kind: MatchKind = MatchKind.ENDLESS): PuzzleUiState? {
        if (match.kind != kind) return null
        return decode(match.actions.firstOrNull() ?: return null)
    }
}

/** Profile changes for a solved endless puzzle (solved count per side length). */
object PuzzleProgress {
    fun recordSolved(profile: Profile, size: Int): Profile {
        val before = profile.endlessSolved[size] ?: 0
        return profile.copy(endlessSolved = profile.endlessSolved + (size to before + 1))
    }
}
