package pt.tiago.nightfleet.game.modes

import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile

/**
 * The battle of a mode (patrol or tournament). It tells the battle view model which slot the match uses, that no
 * officer hint is allowed, and how the result moves the mode's record. The run itself is kept by the flow, not here.
 */
sealed interface ModeStake {
    val kind: MatchKind
    val difficulty: Difficulty

    /** The id of this battle in the saved match slot, the logbook and the profile commit. */
    val matchId: String

    /** One sea of a patrol: [sea] is 1-based and [seasClearedBefore] the seas cleared before it in the run. */
    data class Patrol(
        override val difficulty: Difficulty,
        val runSeed: Long,
        val sea: Int,
        val seasClearedBefore: Int,
        /** Shots in the reserve when this sea starts (the budget of the hunt). */
        val reserve: Int,
    ) : ModeStake {
        override val kind: MatchKind get() = MatchKind.PATROL
        override val matchId: String get() = PatrolRun.matchId(runSeed, sea)
    }

    /** One duel of a tournament: [round] is 1-based out of [rounds], and [roundsWonBefore] the duels won before it. */
    data class Tournament(
        override val difficulty: Difficulty,
        val seed: Long,
        val round: Int,
        val rounds: Int,
        val roundsWonBefore: Int,
    ) : ModeStake {
        override val kind: MatchKind get() = MatchKind.TOURNAMENT
        override val matchId: String get() = TournamentRun.matchId(seed, round - 1)
    }
}

/** How a mode battle changes the profile: the record of the mode, and the facts the achievements read. */
object ModeResults {

    /** Seas cleared in the run once this battle is over. */
    fun seasClearedAfter(stake: ModeStake.Patrol, won: Boolean): Int = stake.seasClearedBefore + if (won) 1 else 0

    /** Duels won in the tournament once this battle is over. */
    fun roundsWonAfter(stake: ModeStake.Tournament, won: Boolean): Int = stake.roundsWonBefore + if (won) 1 else 0

    /** The profile after a battle of a mode. Records only ever go up, so a loss never lowers them. */
    fun afterBattle(profile: Profile, stake: ModeStake, won: Boolean): Profile = when (stake) {
        is ModeStake.Patrol -> profile.copy(
            patrolBest = profile.patrolBest + (stake.difficulty to maxOf(profile.patrolBest[stake.difficulty] ?: 0, seasClearedAfter(stake, won))),
        )
        is ModeStake.Tournament -> profile.copy(
            tournamentBest = profile.tournamentBest + (stake.difficulty to maxOf(profile.tournamentBest[stake.difficulty] ?: 0, roundsWonAfter(stake, won))),
        )
    }

    /** Whether this battle won the whole tournament: the last duel, won. */
    fun wonTournament(stake: ModeStake.Tournament, won: Boolean): Boolean = won && stake.round == stake.rounds
}
