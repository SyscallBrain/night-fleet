package pt.tiago.nightfleet.game.duo

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig

/** Session score of the duel: wins per seat and draws, across the games of the session. */
@Serializable
data class DuoScore(val wins: List<Int> = listOf(0, 0), val draws: Int = 0)

/** Why the curtain is up: before a seat places its fleet, or before a seat's turn. */
enum class CurtainReason { PLACE, TURN }

/**
 * Where the duel is. The curtain comes before every seat's part: it is opaque, and the
 * seat's sea appears only after "Show my sea". [Result] keeps the shot visible for 1.2 s before the curtain.
 */
sealed class DuoPhase {
    /** Seat [seat] places its fleet. */
    data class Placing(val seat: Int) : DuoPhase()

    /** The curtain is up for [seat]: nothing of either sea is shown. */
    data class Curtain(val seat: Int, val reason: CurtainReason) : DuoPhase()

    /** Seat [seat] plays its turn. */
    data class Turn(val seat: Int) : DuoPhase()

    /** The shot of seat [seat] is on the screen for the result time; input is locked. */
    data class Result(val seat: Int) : DuoPhase()

    /** The game is over. [winner] is null for a draw. */
    data class Ended(val winner: Int?) : DuoPhase()
}

/**
 * One game of the duel: names, the setup as it is known so far, the match once both fleets are placed,
 * the phase and the session score. Immutable: every transition returns a new value.
 */
data class DuoGame(
    val names: List<String>,
    val config: FreePlayConfig,
    /** Games of the session before this one. Who starts alternates with it. */
    val gameIndex: Int,
    val session: DuoScore,
    val terrain: List<Terrain>,
    val attemptSeed: Long,
    val placements: List<List<Placement>?> = listOf(null, null),
    val setup: MatchSetup? = null,
    val match: BattleMatch? = null,
    val phase: DuoPhase,
    /** Engine or setup reason of the last refused action, or null. */
    val refusal: String? = null,
) {
    /** The seat that opens this game: seat 0 in even games, seat 1 in odd ones. */
    val firstSeat: Int get() = gameIndex % 2
}

/** What one seat is shown. The curtain and the placement carry no board: only the turn views carry a sea. */
sealed class DuoView {
    data class Curtain(val seat: Int, val name: String, val reason: CurtainReason) : DuoView()

    data class Placing(val seat: Int, val name: String) : DuoView()

    /** The seat's own observation: its sea, what it knows of the other sea, and whether the result is on screen. */
    data class Turn(val seat: Int, val name: String, val observation: Observation, val resultShowing: Boolean) : DuoView()

    /** The end: the winner (null for a draw) and the seat's observation, with the fleets revealed at the end. */
    data class Ended(val winner: Int?, val observation: Observation) : DuoView()
}

/** The state machine of the two-player duel. Every function is pure. */
object DuoFlow {

    /** Game of [gameIndex] with [names]: the first seat places its fleet behind the first curtain. */
    fun newGame(
        names: List<String>,
        config: FreePlayConfig,
        gameIndex: Int,
        session: DuoScore,
        terrain: List<Terrain>,
        attemptSeed: Long,
    ): DuoGame = DuoGame(
        names = names,
        config = config,
        gameIndex = gameIndex,
        session = session,
        terrain = terrain,
        attemptSeed = attemptSeed,
        phase = DuoPhase.Curtain(0, CurtainReason.PLACE),
    )

    /** "Mostrar o meu mar": lifts the curtain for its seat. Any other phase stays as it is. */
    fun reveal(game: DuoGame): DuoGame {
        val phase = game.phase as? DuoPhase.Curtain ?: return game
        val next = when (phase.reason) {
            CurtainReason.PLACE -> DuoPhase.Placing(phase.seat)
            CurtainReason.TURN -> DuoPhase.Turn(phase.seat)
        }
        return game.copy(phase = next, refusal = null)
    }

    /**
     * Seat [seat] has placed [fleet]. The first seat's fleet is followed by the curtain for the second; the
     * second seat's fleet builds the match, and the curtain goes up for whoever acts first. A refused fleet
     * (no valid setup) stays on the placement with a refusal.
     */
    fun placed(game: DuoGame, seat: Int, fleet: List<Placement>): DuoGame {
        if (game.phase != DuoPhase.Placing(seat)) return game
        val placements = game.placements.toMutableList().also { it[seat] = fleet }
        if (seat == 0) {
            return game.copy(placements = placements, phase = DuoPhase.Curtain(1, CurtainReason.PLACE), refusal = null)
        }
        val first = placements[0] ?: return game
        val setup = FreePlayBuilder.duel(
            config = game.config,
            terrain = game.terrain,
            attemptSeed = game.attemptSeed,
            first = first,
            second = fleet,
            firstPlayer = game.firstSeat,
            names = game.names,
        ) ?: return game.copy(placements = placements, phase = DuoPhase.Placing(1), refusal = REFUSAL_SETUP)
        val match = BattleMatch(setupState(setup))
        return game.copy(
            placements = placements,
            setup = setup,
            match = match,
            phase = DuoPhase.Curtain(match.state.current, CurtainReason.TURN),
            refusal = null,
        )
    }

    /**
     * The shot or salvo of the seat to act. A refused action keeps the game as it is and says why. An accepted
     * one moves to [DuoPhase.Result], or to the end of the game, which is counted in the session score.
     */
    fun act(game: DuoGame, action: Action): DuoGame {
        val phase = game.phase as? DuoPhase.Turn ?: return game.copy(refusal = REFUSAL_NOT_YOUR_TURN)
        val match = game.match ?: return game
        if (match.state.current != phase.seat) return game.copy(refusal = REFUSAL_NOT_YOUR_TURN)
        val step = match.apply(action)
        val refusal = step.events.filterIsInstance<pt.tiago.nightfleet.core.engine.Event.IllegalAction>().firstOrNull()?.reason
        if (refusal != null || !step.accepted) return game.copy(refusal = refusal ?: REFUSAL_REFUSED)
        val result = step.match.state.result
        if (result == null) {
            return game.copy(match = step.match, phase = DuoPhase.Result(phase.seat), refusal = null)
        }
        return game.copy(
            match = step.match,
            phase = DuoPhase.Ended(result.winner),
            session = game.session.add(result.winner),
            refusal = null,
        )
    }

    /** The result time is over: the curtain goes up for the seat that acts next. */
    fun afterResult(game: DuoGame): DuoGame {
        if (game.phase !is DuoPhase.Result) return game
        val match = game.match ?: return game
        return game.copy(phase = DuoPhase.Curtain(match.state.current, CurtainReason.TURN))
    }

    /** The next game of the session: the other seat opens it, the score carries over. */
    fun nextGame(game: DuoGame, attemptSeed: Long, terrain: List<Terrain>): DuoGame =
        newGame(game.names, game.config, game.gameIndex + 1, game.session, terrain, attemptSeed)

    /**
     * Resumes a saved game. Only battles are saved (after both fleets are placed), so the curtain comes up for
     * the seat that acts next, or the game is over.
     */
    fun restored(
        names: List<String>,
        config: FreePlayConfig,
        gameIndex: Int,
        session: DuoScore,
        terrain: List<Terrain>,
        attemptSeed: Long,
        setup: MatchSetup,
        match: BattleMatch,
    ): DuoGame {
        val phase = match.state.result?.let { DuoPhase.Ended(it.winner) }
            ?: DuoPhase.Curtain(match.state.current, CurtainReason.TURN)
        return DuoGame(
            names = names,
            config = config,
            gameIndex = gameIndex,
            session = session,
            terrain = terrain,
            attemptSeed = attemptSeed,
            placements = setup.placements,
            setup = setup,
            match = match,
            phase = phase,
        )
    }

    /** What [seat] may see right now. Only the seat's own sea and its shots are ever in a view. */
    fun view(game: DuoGame, seat: Int): DuoView {
        val name = game.names.getOrElse(seat) { "" }
        return when (val phase = game.phase) {
            is DuoPhase.Curtain -> DuoView.Curtain(phase.seat, game.names.getOrElse(phase.seat) { "" }, phase.reason)
            is DuoPhase.Placing -> DuoView.Placing(phase.seat, game.names.getOrElse(phase.seat) { "" })
            is DuoPhase.Turn -> DuoView.Turn(phase.seat, name, observationOf(game, phase.seat), resultShowing = false)
            is DuoPhase.Result -> DuoView.Turn(phase.seat, name, observationOf(game, phase.seat), resultShowing = true)
            is DuoPhase.Ended -> DuoView.Ended(phase.winner, observationOf(game, seat))
        }
    }

    private fun observationOf(game: DuoGame, seat: Int): Observation =
        Observation.of(checkNotNull(game.match).state, seat)

    private fun setupState(setup: MatchSetup) =
        (setup.create() as pt.tiago.nightfleet.core.engine.SetupResult.Created).state

    const val REFUSAL_SETUP = "SETUP"
    const val REFUSAL_NOT_YOUR_TURN = "NOT_YOUR_TURN"
    const val REFUSAL_REFUSED = "REFUSED"
}

/** The session score after one game: a win for [winner], or a draw. */
fun DuoScore.add(winner: Int?): DuoScore = when (winner) {
    null -> copy(draws = draws + 1)
    else -> copy(wins = wins.mapIndexed { seat, count -> if (seat == winner) count + 1 else count })
}
