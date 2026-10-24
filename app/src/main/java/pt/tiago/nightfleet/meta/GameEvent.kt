package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind

/**
 * Summary of one finished battle or puzzle, built by the game layer when its result is committed.
 * The meta layer only reads it, so nothing here depends on the engine. Every counter defaults to
 * zero or false: a mode fills in what it can produce and leaves the rest out.
 */
data class GameEvent(
    val kind: MatchKind,
    val result: LogResult,
    /** Null for modes without a difficulty (duels, free play against the Relentless admiral). */
    val difficulty: Difficulty? = null,
    val isPuzzle: Boolean = false,
    val boardSize: Int = 10,
    val shots: Int = 0,
    val hits: Int = 0,
    val hintUsed: Boolean = false,
    /** The Relentless admiral of free play. */
    val relentless: Boolean = false,
    /** Longest run of misses in a row during the match. */
    val missStreakMax: Int = 0,
    val shipsSunk: Int = 0,
    val torpedoSinks: Int = 0,
    val salvoSinks: Int = 0,
    /** Sonar readings in this match that found a ship. */
    val sonarHits: Int = 0,
    /** The most hits of one cross barrage. */
    val crossHits: Int = 0,
    /** The admiral was fooled by a decoy. */
    val decoyFooled: Boolean = false,
    val submergedFound: Boolean = false,
    /** A ship was repaired during the match. Only counts together with a win. */
    val repairedAndWon: Boolean = false,
    /** The player moved a ship and the admiral fired where it had been. */
    val movedAndEscaped: Boolean = false,
    /** A mine was triggered and the ship survived. Only counts together with a win. */
    val survivedMine: Boolean = false,
    /** Escort won with all three cargo ships intact. */
    val escortPerfect: Boolean = false,
    /** Hunt won with at most half of the shot budget used. */
    val huntHalfBudget: Boolean = false,
    /** Endless puzzles solved in a row, counting this one. */
    val endlessStreak: Int = 0,
    /** Seas cleared in this patrol run. */
    val patrolSeas: Int = 0,
    val tournamentWon: Boolean = false,
    val turnLimitDraw: Boolean = false,
) {
    /** Hits over shots, 0 when no shot was fired. */
    val precision: Float get() = if (shots > 0) hits.coerceIn(0, shots).toFloat() / shots else 0f
}
