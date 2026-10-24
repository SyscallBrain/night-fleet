package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.AdmiralDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.SeaDto
import pt.tiago.nightfleet.core.fleet.PlacementStyle

/** Campaign tables. Everything here is normative for `generate`. */

const val CONTENT_VERSION = 1
const val SEAS_COUNT = 12
const val MISSIONS_PER_SEA = 50

/** Default `--seed` of the generators. Every seed of a mission derives from it and the mission id. */
const val DEFAULT_SEED = 20_260_101L

/** Sea data. Names and keys are final for v1. */
internal val SEAS: List<SeaDto> = listOf(
    SeaDto(1, "sea.s01.name", "lighthouse-keeper-iris", "calm", "bells-slow", emptyList()),
    SeaDto(2, "sea.s02.name", "commodore-haze", "light-haze", "drone-soft", listOf("intro.lighthouse-puzzle")),
    SeaDto(3, "sea.s03.name", "admiral-coral", "sunset-gulls", "pizzicato-strings", emptyList()),
    SeaDto(4, "sea.s04.name", "captain-frost", "aurora", "ice-pads", listOf("intro.sonar", "intro.timed")),
    SeaDto(5, "sea.s05.name", "sapper-iron", "moonless-night", "low-pulse", listOf("intro.mines", "intro.recon")),
    SeaDto(6, "sea.s06.name", "master-atoll", "full-moon", "marimba", listOf("intro.school")),
    SeaDto(7, "sea.s07.name", "corsair-wolf", "light-rain", "distant-drums", listOf("intro.convoy", "intro.decoy")),
    SeaDto(8, "sea.s08.name", "sargasso-witch", "green-fog", "modal-flute", listOf("intro.torpedo")),
    SeaDto(9, "sea.s09.name", "captain-shade", "snow", "dark-drone", listOf("intro.diving", "intro.repair")),
    SeaDto(10, "sea.s10.name", "admiral-tempest", "storm", "percussion", listOf("intro.salvo", "intro.cross")),
    SeaDto(11, "sea.s11.name", "the-leviathan", "deep-sea", "deep-bass", listOf("intro.large", "intro.fog", "intro.moving")),
    SeaDto(12, "sea.s12.name", "grand-admiral-nox", "clear-stars", "full-theme", emptyList()),
)

/**
 * Admirals. `placementStyle` names a [PLACEMENT_STYLES] entry; `search` and
 * `weaponPolicy` are descriptive names of the AI policies, used by core/ai later.
 */
internal val ADMIRALS: List<AdmiralDto> = listOf(
    AdmiralDto("lighthouse-keeper-iris", 1, "admiral.lighthouse-keeper-iris.name", "SPREAD", "RANDOM_PATIENT", "RARE_SONAR"),
    AdmiralDto("commodore-haze", 2, "admiral.commodore-haze.name", "EDGES", "CHECKERBOARD", "LATE_SONAR"),
    AdmiralDto("admiral-coral", 3, "admiral.admiral-coral.name", "NEAR_ISLANDS", "ISLANDS_FIRST", "MODERATE"),
    AdmiralDto("captain-frost", 4, "admiral.captain-frost.name", "CENTRE", "CENTRE_SPIRAL", "EARLY_SONAR"),
    AdmiralDto("sapper-iron", 5, "admiral.sapper-iron.name", "CLUSTER", "EDGES_FIRST", "RECON_DEFENSIVE"),
    AdmiralDto("master-atoll", 6, "admiral.master-atoll.name", "CLUSTER", "DIAGONALS", "MODERATE"),
    AdmiralDto("corsair-wolf", 7, "admiral.corsair-wolf.name", "CLUSTER", "AGGRESSIVE", "DECOY_AND_TORPEDO_EARLY"),
    AdmiralDto("sargasso-witch", 8, "admiral.sargasso-witch.name", "SPREAD", "DENSITY", "ALL_EARLY"),
    AdmiralDto("captain-shade", 9, "admiral.captain-shade.name", "BALANCED", "CHANGES_MID_BATTLE", "REPAIR"),
    AdmiralDto("admiral-tempest", 10, "admiral.admiral-tempest.name", "SPREAD", "DENSITY_BURSTS", "CROSS_AND_SALVO"),
    AdmiralDto("the-leviathan", 11, "admiral.the-leviathan.name", "EDGES", "DENSITY_NEAR_PERFECT", "ALL_OPTIMAL"),
    AdmiralDto("grand-admiral-nox", 12, "admiral.grand-admiral-nox.name", "BALANCED", "DENSITY", "ALL"),
)

/** Placement style name (admirals.json) -> weights of [pt.tiago.nightfleet.core.fleet.PlacementStyle]. */
internal val PLACEMENT_STYLES: Map<String, PlacementStyle> = mapOf(
    "BALANCED" to PlacementStyle.BALANCED,
    "SPREAD" to PlacementStyle(edges = 0, centre = 0, nearIslands = 0, spread = 3, cluster = -3),
    "EDGES" to PlacementStyle(edges = 3, centre = -1, nearIslands = 0, spread = 0, cluster = 0),
    "NEAR_ISLANDS" to PlacementStyle(edges = 0, centre = 0, nearIslands = 3, spread = 0, cluster = 0),
    "CENTRE" to PlacementStyle(edges = -2, centre = 3, nearIslands = 0, spread = 0, cluster = 0),
    "CLUSTER" to PlacementStyle(edges = 0, centre = 0, nearIslands = 0, spread = -2, cluster = 3),
)

internal fun placementStyleNamed(name: String): PlacementStyle =
    PLACEMENT_STYLES[name] ?: throw IllegalArgumentException("unknown placement style $name")

/** Mission counts per sea without the boss: duels, puzzles, hunts, escorts. */
internal data class TypeCounts(val duel: Int, val puzzle: Int, val hunt: Int, val convoy: Int) {
    val total: Int get() = duel + puzzle + hunt + convoy
}

internal val TYPE_COUNTS: Map<Int, TypeCounts> = mapOf(
    1 to TypeCounts(40, 0, 9, 0),
    2 to TypeCounts(27, 22, 0, 0),
    3 to TypeCounts(30, 19, 0, 0),
    4 to TypeCounts(30, 19, 0, 0),
    5 to TypeCounts(30, 19, 0, 0),
    6 to TypeCounts(26, 14, 9, 0),
    7 to TypeCounts(14, 12, 10, 13),
    8 to TypeCounts(21, 9, 12, 7),
    9 to TypeCounts(21, 9, 12, 7),
    10 to TypeCounts(21, 9, 12, 7),
    11 to TypeCounts(20, 9, 13, 7),
    12 to TypeCounts(20, 9, 13, 7),
)

/** Mechanics, in the order they are introduced. */
internal enum class Mech { SONAR, TIMED, MINES, RECON, SCHOOL, CONVOY, DECOY, TORPEDO, DIVING, REPAIR, SALVO, CROSS, LARGE, FOG, MOVING, PUZZLE }

/** Mechanics introduced by each sea. The k-th one appears at mission 1, 3 or 6 of its sea. */
internal val INTROS: Map<Int, List<Mech>> = mapOf(
    1 to emptyList(),
    2 to listOf(Mech.PUZZLE),
    3 to emptyList(),
    4 to listOf(Mech.SONAR, Mech.TIMED),
    5 to listOf(Mech.MINES, Mech.RECON),
    6 to listOf(Mech.SCHOOL),
    7 to listOf(Mech.CONVOY, Mech.DECOY),
    8 to listOf(Mech.TORPEDO),
    9 to listOf(Mech.DIVING, Mech.REPAIR),
    10 to listOf(Mech.SALVO, Mech.CROSS),
    11 to listOf(Mech.LARGE, Mech.FOG, Mech.MOVING),
    12 to emptyList(),
)

/** Positions (1-based, inside the sea) of the introductions: the k-th mechanic of a sea appears at INTRO_POSITIONS[k]. */
internal val INTRO_POSITIONS: List<Int> = listOf(1, 3, 6)

/** Mines per sea: 12 on sea 5, then 5 to 7. */
internal fun minedMissions(sea: Int): Int = if (sea == 5) 12 else 5 + sea % 3

/** Hunt mission count of the sea that introduces a weapon is taken from TYPE_COUNTS; this is only the weapon catalogue. */
internal val WEAPON_OF: Map<Mech, String> = mapOf(
    Mech.SONAR to "SONAR",
    Mech.RECON to "RECON",
    Mech.TORPEDO to "TORPEDO",
    Mech.DECOY to "DECOY",
    Mech.REPAIR to "REPAIR",
    Mech.CROSS to "CROSS_SHELL",
)

/** Board side of a sea: 8 in seas 1–2, 10 from sea 4 (sea 3 alternates), 12 on the Abyssal Trench and the Grand Admiral. */
internal fun boardSide(sea: Int, index: Int): Int = when (sea) {
    1, 2 -> 8
    3 -> if (index % 2 == 0) 10 else 8
    4, 5, 6, 7, 8, 9, 10 -> 10
    11 -> if (index % 3 == 0) 10 else 12
    else -> if (index % 2 == 0) 12 else 10
}

/** Puzzle side of a sea (6×6 from sea 2, 8×8 from sea 4, 10×10 from sea 7). */
internal fun puzzleSide(sea: Int, index: Int): Int = when (sea) {
    2 -> 6
    3 -> if (index % 2 == 0) 6 else 8
    4, 5 -> 8
    6 -> if (index % 2 == 0) 8 else 10
    else -> 10
}

/** Fleet preset for a board side (8×8 `SMALL`, 10×10 `CLASSIC`, 12×12 `LARGE`). */
internal fun fleetForSide(side: Int): String = when (side) {
    8 -> "SMALL"
    10 -> "CLASSIC"
    else -> "LARGE"
}

/**
 * Blunder rate in thousandths at a position of the campaign. [intensity] is the
 * saw-tooth position from [intensity]: 0 is the easiest point (sea 1), 1 the hardest.
 */
internal fun blunderRate(difficulty: Difficulty, intensity: Double): Int = when (difficulty) {
    Difficulty.EASY -> Math.round(420 + (160 - 420) * intensity).toInt()
    Difficulty.MEDIUM -> Math.round(260 + (60 - 260) * intensity).toInt()
    Difficulty.HARD -> Math.round(120 * (1 - intensity / 0.82)).toInt().coerceIn(0, 120)
}

/**
 * Position on the difficulty curve. The sea gives the base (0 in sea 1, 1 in sea 12);
 * inside the sea the value rises through blocks of 10 missions up to the peak at 10, 20, 30 and 40,
 * each block starting a little below the previous peak. The boss (50) is the highest point of the sea.
 */
internal fun intensity(sea: Int, index: Int): Double {
    val seaPart = (sea - 1) / 11.0
    if (index == MISSIONS_PER_SEA) return minOf(1.0, seaPart + 0.10)
    val block = (index - 1) / 10
    val position = (index - 1) % 10
    return (seaPart + 0.08 * (position / 9.0) - 0.06 * block).coerceIn(0.0, 1.0)
}

/** Layouts per variant of a duel, hunt or escort: 16, 24 and 32. */
internal fun layoutsFor(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> 16
    Difficulty.MEDIUM -> 24
    Difficulty.HARD -> 32
}

/** Shot clock by difficulty for the timed missions: 8, 6 and 4 seconds. */
internal fun shotClockFor(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> 8
    Difficulty.MEDIUM -> 6
    Difficulty.HARD -> 4
}

/** Sea number of a sea id (`s03` -> 3). */
internal fun seaNumberOf(seaId: String): Int = seaId.removePrefix("s").toInt()

internal fun seaIdOfNumber(sea: Int): String = "s%02d".format(sea)

internal fun missionIdOf(sea: Int, index: Int): String = "s%02dm%03d".format(sea, (sea - 1) * MISSIONS_PER_SEA + index)

internal fun kindLabel(kind: MissionKind): String = kind.name
