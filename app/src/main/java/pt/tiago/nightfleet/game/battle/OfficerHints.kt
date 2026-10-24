package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.game.freeplay.OpponentLevel

/**
 * The officer's hint of the battle screen. The hint shows the best squares of the
 * HARD density; using one caps the stars of the battle at two.
 */
object OfficerHints {

    /** Squares highlighted by one hint. */
    const val CELLS = 3

    /** How long the highlight stays on the board. */
    const val HIGHLIGHT_MILLIS = 1_200L

    /** Highest star count of a battle where a hint was used. */
    const val STAR_CAP = 2

    /**
     * Hints of a battle against an admiral at [level]: two in quick battles and free play, none against the
     * Relentless admiral.
     */
    fun limitFor(level: OpponentLevel): Int = if (level == OpponentLevel.RELENTLESS) 0 else 2

    /** Caps the stars of [summary] when a hint was used. */
    fun capStars(summary: QuickBattle.Summary, hintsUsed: Int): QuickBattle.Summary =
        if (hintsUsed <= 0) summary else summary.copy(stars = minOf(summary.stars, STAR_CAP), usedHint = true)
}
