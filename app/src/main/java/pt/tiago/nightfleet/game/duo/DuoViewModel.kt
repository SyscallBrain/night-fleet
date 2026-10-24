package pt.tiago.nightfleet.game.duo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.DuoSession
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.battle.BattleRender
import pt.tiago.nightfleet.game.battle.BattleUi
import pt.tiago.nightfleet.game.battle.MovePlanner
import pt.tiago.nightfleet.game.battle.RestoreResult
import pt.tiago.nightfleet.game.battle.SetupSave
import pt.tiago.nightfleet.game.battle.TargetTap
import pt.tiago.nightfleet.game.battle.Targeting
import pt.tiago.nightfleet.game.battle.TargetingReducer
import pt.tiago.nightfleet.game.battle.WeaponCatalog
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.FreePlayState
import pt.tiago.nightfleet.data.DUO_HISTORY

/**
 * The two-player duel: one phone, two seats. Holds the game, the aiming of the current seat and
 * the saves. The curtain and the result time are driven here; the rules are the engine's, through [DuoFlow].
 */
class DuoViewModel(private val container: AppContainer) : ViewModel() {

    private val mutableGame = MutableStateFlow<DuoGame?>(null)

    /** The game in progress, or null while the setup is shown. */
    val game: StateFlow<DuoGame?> = mutableGame.asStateFlow()

    private val mutableAim = MutableStateFlow<Targeting>(Targeting.Idle)

    /** What the seat to act is aiming at (weapons column). */
    val targeting: StateFlow<Targeting> = mutableAim.asStateFlow()

    private var resultJob: Job? = null

    /** The rules of the last free play setup: the duel uses the same toggles. */
    suspend fun lastConfig(): FreePlayConfig = container.freePlay.load().config

    /** Stores the rules chosen on the duel setup, shared with free play. */
    suspend fun rememberConfig(config: FreePlayConfig) {
        val current = container.freePlay.load()
        container.freePlay.save(FreePlayState(config = config, attempts = current.attempts))
    }

    /** Resumes a saved duel, with the curtain up for the seat that acts next. False when nothing is saved. */
    suspend fun restoreSaved(): Boolean {
        val saved = container.matches.load(MatchKind.DUO) ?: return false
        val setupText = saved.setup ?: return discard()
        val setup = SetupSave.decodeSetup(setupText) ?: return discard()
        val restored = SetupSave.restore(saved) as? RestoreResult.Restored ?: return discard()
        val session = sessionFor(setup.names)
        mutableGame.value = DuoFlow.restored(
            names = setup.names,
            config = lastConfig(),
            gameIndex = session.games,
            session = session.score,
            terrain = setup.rules.terrain,
            attemptSeed = setup.attemptSeed,
            setup = setup,
            match = restored.match,
        )
        return true
    }

    private suspend fun discard(): Boolean {
        runCatching { container.matches.clear(MatchKind.DUO) }
        return false
    }

    /** Starts a duel of [names] with [config]: the games of the session so far set the starter. */
    suspend fun startDuel(names: List<String>, config: FreePlayConfig) {
        rememberConfig(config)
        val session = sessionFor(names)
        runCatching { container.matches.clear(MatchKind.DUO) }
        val seed = gameSeed(session.games)
        mutableGame.value = DuoFlow.newGame(
            names = names,
            config = config,
            gameIndex = session.games,
            session = session.score,
            terrain = FreePlayBuilder.terrainFor(config, seed),
            attemptSeed = seed,
        )
        mutableAim.value = Targeting.Idle
    }

    /** The next game of the session. */
    fun nextGame() {
        val current = mutableGame.value ?: return
        val seed = gameSeed(current.gameIndex + 1)
        mutableGame.value = DuoFlow.nextGame(current, seed, FreePlayBuilder.terrainFor(current.config, seed))
        mutableAim.value = Targeting.Idle
    }

    fun reveal() {
        mutableGame.update { it?.let(DuoFlow::reveal) }
        mutableAim.value = Targeting.Idle
    }

    /** The seat has placed its fleet. The second seat's fleet opens the battle, which is saved at once. */
    fun placed(seat: Int, fleet: List<Placement>) {
        val current = mutableGame.value ?: return
        val next = DuoFlow.placed(current, seat, fleet)
        mutableGame.value = next
        if (next.match != null) persist(next)
    }

    fun tap(index: Int) {
        val game = mutableGame.value ?: return
        val turn = game.phase as? DuoPhase.Turn ?: return
        val match = game.match ?: return
        val width = match.width
        val seat = turn.seat
        val obs = Observation.of(match.state, seat)
        val free = obs.enemySea.count { it.isFireable }
        val afloat = obs.ownSea.ships.count { !it.sunk }
        val salvo = if (match.state.rules.salvo) minOf(afloat, free) else null
        val cell = Cell(index / width, index % width)
        val open = obs.enemySea.getOrNull(index)?.isFireable == true
        when (val tap = TargetingReducer.enemyTap(mutableAim.value, cell, width, match.height, open, salvo)) {
            is TargetTap.Stay -> stay(tap.targeting, tap.refusal)
            is TargetTap.Fire -> act(Action.Fire(cell))
            is TargetTap.Send -> act(tap.action)
        }
    }

    fun ownTap(index: Int) {
        val game = mutableGame.value ?: return
        val turn = game.phase as? DuoPhase.Turn ?: return
        val match = game.match ?: return
        val width = match.width
        val cell = Cell(index / width, index % width)
        val state = match.state
        val seat = turn.seat
        val shipAt: (Cell) -> Int? = { c ->
            state.sides[seat].ships.indexOfFirst { ship -> ship.placement.cells().any { it == c } }.takeIf { it >= 0 }
        }
        when (val tap = TargetingReducer.ownTap(mutableAim.value, cell, shipAt) { MovePlanner.legalDeltas(state, it) }) {
            is TargetTap.Stay -> stay(tap.targeting, tap.refusal)
            is TargetTap.Send -> act(tap.action)
            is TargetTap.Fire -> Unit
        }
    }

    fun pickWeapon(weapon: Weapon) {
        val game = mutableGame.value ?: return
        val match = game.match ?: return
        val seat = (game.phase as? DuoPhase.Turn)?.seat ?: return
        if (WeaponCatalog.charges(match.state.sides[seat].charges, weapon) <= 0) {
            mutableGame.update { it?.copy(refusal = "NO_CHARGE") }
            return
        }
        mutableAim.update { TargetingReducer.pickWeapon(it, weapon) }
    }

    fun pickMove() = mutableAim.update { TargetingReducer.pickMove(it) }

    fun moveStep(delta: Int) {
        when (val tap = TargetingReducer.moveStep(mutableAim.value, delta)) {
            is TargetTap.Send -> act(tap.action)
            is TargetTap.Stay -> stay(tap.targeting, tap.refusal)
            is TargetTap.Fire -> Unit
        }
    }

    fun fireSalvo() {
        val game = mutableGame.value ?: return
        val salvo = mutableAim.value as? Targeting.Salvo ?: return
        val width = game.match?.width ?: return
        act(Action.Salvo(salvo.picked.map { Cell(it / width, it % width) }))
    }

    fun flipAxis() = mutableAim.update { (it as? Targeting.Line)?.let { line -> TargetingReducer.flipAxis(line, width(), height()) } ?: it }

    fun flipMargin() = mutableAim.update { (it as? Targeting.Line)?.let(TargetingReducer::flipMargin) ?: it }

    fun cancelTargeting() = mutableAim.update { Targeting.Idle }

    /** The battle view of the seat to act, with the aiming state. Null outside a turn. */
    fun battleUi(game: DuoGame, base: BattleUi, aim: Targeting): BattleUi? {
        val match = game.match ?: return null
        val seat = when (val phase = game.phase) {
            is DuoPhase.Turn -> phase.seat
            is DuoPhase.Result -> phase.seat
            else -> return null
        }
        return BattleRender.of(match, seat, base).copy(
            busy = game.phase is DuoPhase.Result,
            targeting = aim,
            refusal = game.refusal,
        )
    }

    private fun width(): Int = mutableGame.value?.match?.width ?: 0
    private fun height(): Int = mutableGame.value?.match?.height ?: 0

    private fun stay(targeting: Targeting, refusal: String?) {
        mutableAim.value = targeting
        mutableGame.update { it?.copy(refusal = refusal) }
    }

    /** One accepted or refused action of the seat to act. */
    private fun act(action: Action) {
        val current = mutableGame.value ?: return
        val next = DuoFlow.act(current, action)
        mutableGame.value = next
        if (next.refusal == null) mutableAim.value = Targeting.Idle
        when (next.phase) {
            is DuoPhase.Result -> {
                persist(next)
                scheduleAfterResult()
            }
            is DuoPhase.Ended -> finish(next)
            else -> Unit
        }
    }

    private fun scheduleAfterResult() {
        resultJob?.cancel()
        resultJob = viewModelScope.launch {
            delay(RESULT_MS)
            mutableGame.update { it?.let(DuoFlow::afterResult) }
        }
    }

    /** The game is over: the save is cleared and the session score is written to the profile. */
    private fun finish(game: DuoGame) {
        viewModelScope.launch {
            runCatching { container.matches.clear(MatchKind.DUO) }
            runCatching {
                container.profile.update { profile -> profile.withSession(game.names, game.session) }
            }
        }
    }

    private fun persist(game: DuoGame) {
        val setup = game.setup ?: return
        val match = game.match ?: return
        viewModelScope.launch {
            runCatching {
                container.matches.save(SetupSave.toSaved(MatchKind.DUO, duelMatchId(setup.attemptSeed), setup, match, System.currentTimeMillis()))
            }
        }
    }

    private suspend fun sessionFor(names: List<String>): SessionCount {
        val profile = container.profile.load()
        val entry = profile.duoSessions.lastOrNull { it.firstPlayer == names[0] && it.secondPlayer == names[1] }
            ?: return SessionCount(DuoScore(), 0)
        val score = DuoScore(wins = listOf(entry.firstWins, entry.secondWins), draws = entry.draws)
        return SessionCount(score, score.wins.sum() + score.draws)
    }

    /** Session totals for the starter and the score of the next game. */
    private data class SessionCount(val score: DuoScore, val games: Int)

    private fun gameSeed(gameIndex: Int): Long = SeedStreams.attemptSeed(DUO_LEVEL_SEED, gameIndex)

    companion object {
        /** How long the shot stays on screen before the curtain. */
        const val RESULT_MS = 1_200L
        const val DUO_LEVEL_SEED: Long = 0x4455_4F00_0000_0009L

        /** Id of the duel's save: the seed of the game it is part of. */
        fun duelMatchId(attemptSeed: Long): String = "duo-" + java.lang.Long.toHexString(attemptSeed).padStart(16, '0')
    }
}

/** The profile after a finished game: the session with these names is updated, or added (oldest dropped). */
internal fun Profile.withSession(names: List<String>, score: DuoScore): Profile {
    val entry = DuoSession(
        firstPlayer = names[0],
        secondPlayer = names[1],
        firstWins = score.wins[0],
        secondWins = score.wins[1],
        draws = score.draws,
    )
    val index = duoSessions.indexOfLast { it.firstPlayer == names[0] && it.secondPlayer == names[1] }
    val next = if (index >= 0) duoSessions.toMutableList().also { it[index] = entry } else (duoSessions + entry).takeLast(DUO_HISTORY)
    return copy(duoSessions = next)
}
