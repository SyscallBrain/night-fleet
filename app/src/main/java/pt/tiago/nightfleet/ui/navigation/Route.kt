package pt.tiago.nightfleet.ui.navigation

import pt.tiago.nightfleet.data.Difficulty
import java.time.LocalDate

/**
 * Every screen of the app. A route is a plain value: it is saved in the back
 * stack as text (see [encode] and [decode]) so that the stack survives rotation and process death.
 * Ids are strings (mission ids, log ids) and never carry personal text.
 */
sealed interface Route {
    data object Home : Route

    /** Sea map of [sea] (1..12) in [difficulty]. */
    data class SeaMap(val sea: Int, val difficulty: Difficulty) : Route

    /** Mission sheet of a campaign mission (ordinal 1..600). */
    data class MissionSheet(val mission: Int, val difficulty: Difficulty) : Route

    /** The campaign mission being played: placement, then battle or puzzle. */
    data class Mission(val mission: Int, val difficulty: Difficulty) : Route

    data object Daily : Route

    /** Today's daily hunt. [date] is the challenge date, fixed when the hunt opens. */
    data class DailyHunt(val date: LocalDate, val difficulty: Difficulty) : Route
    data class FreePlay(val admiralId: String? = null) : Route
    data object Duel : Route
    data object EndlessPuzzles : Route

    /** The "More modes" list: endless puzzles, patrol and the admirals' tournament. */
    data object MoreModes : Route
    data object Patrol : Route
    data object Tournament : Route
    data object Profile : Route
    data object Gallery : Route
    data object Achievements : Route
    data object Statistics : Route
    data object Logbook : Route
    data class Analysis(val logId: String) : Route
    data class Replay(val logId: String) : Route
    data object Settings : Route
    data object About : Route

    /** Debug builds only. */
    data object BoardGallery : Route

    /** Debug builds only: the on-device checks (AI golden moves, decision timings, engine hash). */
    data object Diagnostics : Route

    /** A puzzle from the chooser, as [pt.tiago.nightfleet.game.puzzle.PuzzleRequest] text (its own `:` separators). */
    data class Puzzle(val request: String) : Route

    companion object {
        private const val SEP = "/"

        /** Text form of a route: `name/arg/...`. */
        fun encode(route: Route): String = when (route) {
            Home -> "home"
            is SeaMap -> listOf("sea", route.sea, route.difficulty.name).joinToString(SEP)
            is MissionSheet -> listOf("sheet", route.mission, route.difficulty.name).joinToString(SEP)
            is Mission -> listOf("mission", route.mission, route.difficulty.name).joinToString(SEP)
            Daily -> "daily"
            is DailyHunt -> listOf("dailyhunt", route.date.toString(), route.difficulty.name).joinToString(SEP)
            is FreePlay -> listOfNotNull("freeplay", route.admiralId).joinToString(SEP)
            Duel -> "duel"
            EndlessPuzzles -> "endless"
            MoreModes -> "modes"
            Patrol -> "patrol"
            Tournament -> "tournament"
            Profile -> "profile"
            Gallery -> "gallery"
            Achievements -> "achievements"
            Statistics -> "stats"
            Logbook -> "logbook"
            is Analysis -> listOf("analysis", route.logId).joinToString(SEP)
            is Replay -> listOf("replay", route.logId).joinToString(SEP)
            Settings -> "settings"
            About -> "about"
            BoardGallery -> "boards"
            Diagnostics -> "diagnostics"
            is Puzzle -> "puzzle/${route.request}"
        }

        /** Parses [encode]'s output. Anything unknown or malformed is null, and the caller falls back to Home. */
        fun decode(text: String): Route? {
            val parts = text.split(SEP)
            return when (parts.firstOrNull()) {
                "home" -> Home
                "sea" -> difficultyArg(parts, 2)?.let { d -> parts.getOrNull(1)?.toIntOrNull()?.let { SeaMap(it, d) } }
                "sheet" -> difficultyArg(parts, 2)?.let { d -> parts.getOrNull(1)?.toIntOrNull()?.let { MissionSheet(it, d) } }
                "mission" -> difficultyArg(parts, 2)?.let { d -> parts.getOrNull(1)?.toIntOrNull()?.let { Mission(it, d) } }
                "daily" -> Daily
                "dailyhunt" -> {
                    val date = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (date == null) null else difficultyArg(parts, 2)?.let { DailyHunt(date, it) }
                }
                "freeplay" -> FreePlay(parts.getOrNull(1)?.takeIf { it.isNotBlank() })
                "duel" -> Duel
                "endless" -> EndlessPuzzles
                "modes" -> MoreModes
                "patrol" -> Patrol
                "tournament" -> Tournament
                "profile" -> Profile
                "gallery" -> Gallery
                "achievements" -> Achievements
                "stats" -> Statistics
                "logbook" -> Logbook
                "analysis" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Analysis(it) }
                "replay" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Replay(it) }
                "settings" -> Settings
                "about" -> About
                "boards" -> BoardGallery
                "diagnostics" -> Diagnostics
                "puzzle" -> parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Puzzle(it) }
                else -> null
            }
        }

        private fun difficultyArg(parts: List<String>, index: Int): Difficulty? =
            parts.getOrNull(index)?.let { name -> Difficulty.entries.firstOrNull { it.name == name } }
    }
}
