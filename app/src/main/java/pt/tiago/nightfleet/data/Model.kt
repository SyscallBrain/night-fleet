package pt.tiago.nightfleet.data

import kotlinx.serialization.Serializable

/**
 * Persistent data model of the app. It deliberately does not depend on `:core`: missions are
 * referenced by string id ("s03m112"), and saved matches carry opaque, engine-serialized actions.
 */

const val PROFILE_SCHEMA_VERSION = 1

/** Number of cells of the shot heatmap (10 x 10, normalised from any board size). */
const val HEATMAP_CELLS = 100
const val HEATMAP_SIDE = 10

/** Sea count of the campaign (12 seas x 50 missions). */
const val SEAS_TOTAL = 12
const val MAX_FORMATIONS_PER_KEY = 5
const val DAILY_RETENTION_DAYS = 270
const val COMMITTED_MATCH_HISTORY = 64
const val DUO_HISTORY = 20
const val DEFAULT_PAINT = "default"

@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

@Serializable
enum class MatchKind { CAMPAIGN, DAILY, FREE_PLAY, DUO, PATROL, TOURNAMENT, ENDLESS }

@Serializable
enum class LogResult { WIN, LOSS, DRAW, SOLVED, ABANDONED }

@Serializable
enum class ChallengeKind { PUZZLE, HUNT }

/** Key of a campaign variant: "missionId:DIFFICULTY", e.g. "s03m112:HARD". */
fun variantKey(missionId: String, difficulty: Difficulty): String = "$missionId:${difficulty.name}"

@Serializable
data class VariantProgress(
    val stars: Int = 0,
    val bestShots: Int? = null,
    val wins: Int = 0,
    val losses: Int = 0,
    /** Index of the next hunt fleet drawn from the certified set for this variant. */
    val attemptIndex: Long = 0,
    val usedHint: Boolean = false,
)

/** A boss (chefe) duel series in progress; key is "bossMissionId:DIFFICULTY". */
@Serializable
data class BossSeries(
    val wins: Int = 0,
    val losses: Int = 0,
    /** Shots fired in each finished duel of the series, in order. */
    val shotsPerDuel: List<Int> = emptyList(),
    /** Whether each finished duel used the officer's hint (same order as [shotsPerDuel]; missing means no). */
    val hintsPerDuel: List<Boolean> = emptyList(),
    /** A duel ended in a turn-limit draw and is replayed once: the next attempt is its replay. */
    val replayPending: Boolean = false,
)

@Serializable
data class AchievementProgress(
    /** Local epoch day on which the achievement was unlocked, or null while locked. */
    val unlockedAtEpochDay: Long? = null,
    val counters: Map<Difficulty, Int> = emptyMap(),
)

@Serializable
data class DifficultyStats(
    val battles: Int = 0,
    val wins: Int = 0,
    val shots: Int = 0,
    val hits: Int = 0,
    val longestHitStreak: Int = 0,
    val shipsSunkByClass: Map<String, Int> = emptyMap(),
    val weaponsUsed: Map<String, Int> = emptyMap(),
    val puzzlesSolved: Int = 0,
    val playTimeSeconds: Long = 0,
    /** Row-major 10 x 10 counts of the player's shots, stored as a list (IntArray has no value equality). */
    val shotHeatmap: List<Int> = List(HEATMAP_CELLS) { 0 },
)

@Serializable
data class Placement(
    val shipClass: String,
    val row: Int,
    val col: Int,
    /** Quarter turns clockwise, 0..3. */
    val rotation: Int,
)

/** A saved fleet layout for one board size, fleet and terrain. */
@Serializable
data class Formation(val placements: List<Placement>)

/** Best stars of one day: difficulty -> challenge kind -> stars (0..3). */
@Serializable
data class DailyDay(val best: Map<Difficulty, Map<ChallengeKind, Int>> = emptyMap())

@Serializable
data class RecordCount(val wins: Int = 0, val losses: Int = 0)

/** Local two-player session. Names are personal input: never written to diagnostics. */
@Serializable
data class DuoSession(
    val firstPlayer: String,
    val secondPlayer: String,
    val firstWins: Int = 0,
    val secondWins: Int = 0,
    val draws: Int = 0,
)

@Serializable
data class Profile(
    val schemaVersion: Int = PROFILE_SCHEMA_VERSION,
    /** "missionId:DIFFICULTY" -> progress. */
    val variants: Map<String, VariantProgress> = emptyMap(),
    /** Highest unlocked sea (1..12) per difficulty. The three difficulties progress separately. */
    val unlockedSeas: Map<Difficulty, Int> = Difficulty.entries.associateWith { 1 },
    val bossSeries: Map<String, BossSeries> = emptyMap(),
    val achievements: Map<String, AchievementProgress> = emptyMap(),
    val stats: Map<Difficulty, DifficultyStats> = emptyMap(),
    /** "WxH:fleet:terrainHash" -> up to [MAX_FORMATIONS_PER_KEY] saved layouts, oldest first. */
    val formations: Map<String, List<Formation>> = emptyMap(),
    val unlockedPaints: Set<String> = emptySet(),
    val selectedPaint: String = DEFAULT_PAINT,
    /** "yyyy-MM-dd" -> best stars of that day. */
    val dailyHistory: Map<String, DailyDay> = emptyMap(),
    /** Patrol record: seas cleared per difficulty. */
    val patrolBest: Map<Difficulty, Int> = emptyMap(),
    /** Tournament record: rounds won per difficulty. */
    val tournamentBest: Map<Difficulty, Int> = emptyMap(),
    /** Endless puzzles: puzzles solved per board size (6, 8, 10). */
    val endlessSolved: Map<Int, Int> = emptyMap(),
    /** Free play record per admiral id. */
    val freePlayRecord: Map<String, RecordCount> = emptyMap(),
    val duoSessions: List<DuoSession> = emptyList(),
    val metAdmirals: Set<String> = emptySet(),
    val defeatedAdmirals: Map<Difficulty, Set<String>> = emptyMap(),
    /** Ids of the matches whose result is already in this profile (see ProfileStore.commitResult). */
    val committedMatchIds: List<String> = emptyList(),
    /** Guided tutorials already finished or skipped (ids of ui/tutorial TutorialScripts). */
    val completedTutorials: Set<String> = emptySet(),
    /** Mechanic intro cards already seen (ids of ui/intro MechanicIntro). */
    val seenIntroCards: Set<String> = emptySet(),
)
