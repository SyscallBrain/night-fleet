package pt.tiago.nightfleet.game.battle

/**
 * The admirals' taunts: short lines in the log, at most one every [EVERY_TURNS] turns, never
 * with hidden information. The lines are string resources named `taunt_<admiral id>_<n>` (n = 1..3); the first
 * two are the profile's taunt keys of core/ai. The choice depends only on the turn, so a restored battle shows
 * the same lines.
 */
object Taunts {

    /** Minimum distance, in turns, between two taunts. */
    const val EVERY_TURNS = 5

    /** Resource names of the three lines of [admiralId]. */
    fun keysOf(admiralId: String): List<String> = (1..LINES).map { "taunt_${admiralId}_$it" }

    /** The line due on the admiral's turn at [turn] (the battle's turn counter), or null when none is due. */
    fun due(admiralId: String, turn: Int): String? {
        if (turn < 0 || turn % EVERY_TURNS != 0) return null
        val keys = keysOf(admiralId)
        return keys[(turn / EVERY_TURNS) % keys.size]
    }

    const val LINES = 3
}
