package pt.tiago.nightfleet.game.modes

import pt.tiago.nightfleet.meta.Admirals

/**
 * Checks a run read from a backup before it is trusted. The run classes already refuse impossible states in their
 * constructors; this adds the limits of the game (a reserve no patrol can reach, a ladder of known admirals, rules
 * that exist). A run that fails is dropped by the caller: the mode starts again, the import does not fail.
 */
object ModeRunSanitizer {
    /** The reserve of a patrol never grows past the start plus refunds of a long run: this is a safety cap. */
    const val MAX_RESERVE = 1_000
    const val MAX_SEA = 500

    /** The run if it is a state the game can reach, else null. */
    fun patrol(run: PatrolRun): PatrolRun? = run.takeIf { it.reserve <= MAX_RESERVE && it.sea <= MAX_SEA }

    /** The run if its ladder is eight distinct known admirals and every rule is a known mechanic or the classic duel. */
    fun tournament(run: TournamentRun): TournamentRun? {
        val knownRules = Mechanic.entries.map { it.name }.toSet() + ""
        val ladderOk = run.ladder.distinct().size == run.ladder.size && run.ladder.all { Admirals.byId(it) != null }
        val rulesOk = run.rules.all { it in knownRules }
        return run.takeIf { ladderOk && rulesOk }
    }
}
