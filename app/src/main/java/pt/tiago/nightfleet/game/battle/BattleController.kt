package pt.tiago.nightfleet.game.battle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.OfficerHint
import pt.tiago.nightfleet.core.engine.SubmarinePhase
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.isFireable
import pt.tiago.nightfleet.ui.board.EffectEvent
import pt.tiago.nightfleet.ui.board.ShakeLevel
import kotlin.random.Random

/** Everything the battle screen draws. Pure data, rebuilt after every engine action. */
data class BattleUi(
    val match: BattleMatch,
    val enemy: BoardRenderModel,
    val own: BoardRenderModel,
    val selected: Int? = null,
    /** An animation plan or the admiral's turn is running: input is locked. */
    val busy: Boolean = false,
    val thinking: Boolean = false,
    val paused: Boolean = false,
    val enemyEffects: List<EffectEvent> = emptyList(),
    val ownEffects: List<EffectEvent> = emptyList(),
    val ownSeaLarge: Boolean = false,
    val shakeTrigger: Int = 0,
    val shakeLevel: ShakeLevel? = null,
    val lastLog: LogLine? = null,
    /** The enemy fleet as the player knows it: every class of the fleet, and the classes already sunk. */
    val enemyFleet: List<ShipClass> = emptyList(),
    val enemySunkClasses: List<ShipClass> = emptyList(),
    /** The last tap hit a square that was already shot at. */
    val refusedTap: Int? = null,
    /** Set once the battle is over and its animation has ended: the result sheet may open. */
    val summary: QuickBattle.Summary? = null,
    val pauseOpen: Boolean = false,
    /** What the player is doing with the weapons column. */
    val targeting: Targeting = Targeting.Idle,
    /** Weapons with a button in the column (enabled in the rules). */
    val weapons: List<Weapon> = emptyList(),
    /** Squares of a salvo for the current turn, or null when the salvo rule is off. */
    val salvoRequired: Int? = null,
    val clock: ShotClock = ShotClock(null, null),
    /** The last notice of the animation, shown in the log line while its step plays. */
    val notice: TimelineNotice? = null,
    /** Reason code of the last refused action (an engine reason), or null. */
    val refusal: String? = null,
    /** Misses of the enemy sea that fade during the step playing now. */
    val fadingCells: Set<Int> = emptySet(),
    val submarinePhase: SubmarinePhase? = null,
    val roundsUntilSubmarinePhaseChange: Int? = null,
    val convoy: ConvoyHud? = null,
    /** Ships of the player that can move this turn (empty when the move is not available). */
    val movableShips: List<Int> = emptyList(),
    /** Officer's hints left in this battle. */
    val hintsLeft: Int = 0,
    /** Resource name of the admiral's taunt for the last admiral turn, or null (see [Taunts]). */
    val taunt: String? = null,
    /** Squares of the last officer's hint, lit for [OfficerHints.HIGHLIGHT_MILLIS]. */
    val hintCells: Set<Int> = emptySet(),
    /** The side whose seas these are: the player in a battle, the seat in a two-player duel. */
    val viewer: Int = BattleMatch.PLAYER,
) {
    /** True when the viewer is the side to act and the battle is not over. */
    val myTurn: Boolean get() = !match.finished && match.state.current == viewer
}

/**
 * The render models of a battle as one side sees it: its own sea, the enemy sea with what the side knows, the
 * weapons and the mission values. Pure: the battle controller and the two-player duel both use it. The hidden
 * enemy fleet appears only once the battle has ended.
 */
object BattleRender {

    fun of(match: BattleMatch, viewer: Int, base: BattleUi): BattleUi {
        val enemySide = 1 - viewer
        val obs = Observation.of(match.state, viewer)
        var enemy = ObservationMapper.enemySea(
            obs,
            if (viewer == BattleMatch.PLAYER) match.autoWaterOnEnemy else emptySet(),
            if (viewer == BattleMatch.PLAYER) match.lastPlayerShot else null,
            base.selected,
        )
        enemy = ObservationMapper.withReadings(enemy, obs)
        enemy = enemy.copy(fadedMisses = ObservationMapper.fadingMisses(match.state, viewer))
        val free = enemy.cells.count { it.isFireable }
        val afloat = obs.ownSea.ships.count { !it.sunk }
        val salvoRequired = if (match.state.rules.salvo) minOf(afloat, free) else null
        enemy = ObservationMapper.revealEnemy(enemy, match.state, viewer)
        val own = ObservationMapper.ownSea(obs, if (viewer == BattleMatch.PLAYER) match.lastEnemyShot else null)
        return base.copy(
            match = match,
            enemy = enemy,
            own = own,
            viewer = viewer,
            enemyFleet = match.state.rules.fleetFor(enemySide).ships,
            enemySunkClasses = obs.enemySunk.map { it.shipClass },
            weapons = WeaponCatalog.buttons(match.state.rules),
            salvoRequired = salvoRequired,
            submarinePhase = obs.publicState.submarinePhase,
            roundsUntilSubmarinePhaseChange = obs.publicState.roundsUntilSubmarinePhaseChange,
            convoy = if (viewer == BattleMatch.PLAYER) ConvoyHud.of(match.state) else null,
            movableShips = if (match.state.current == viewer) MovePlanner.movableShips(match.state) else emptyList(),
        )
    }
}

/** The side effects of a battle: persistence, audio and vibration. Implemented by the view model. */
interface BattlePorts {
    suspend fun settings(): Settings
    fun reducedMotion(settings: Settings): Boolean
    suspend fun persist(saved: SavedMatch)

    /** Called once when the battle ends: commits the result (profile, logbook) and clears the save slot. */
    suspend fun finish(match: BattleMatch, summary: QuickBattle.Summary, ui: BattleUi)
    fun sound(effect: SoundEffect)
    fun haptic(pattern: HapticPattern)
}

/**
 * Drives one battle against the admiral: the admiral's turns, the animation plans, the tap-to-skip,
 * the pause, the shot clock, the weapons column and the saves after every engine action. Runs on
 * [scope] (the main thread in the app).
 *
 * Order of every engine action: the engine state is updated and saved first,
 * then the plan plays. A crash in the middle of an animation therefore keeps the move.
 */
class BattleController(
    private val scope: CoroutineScope,
    private val ports: BattlePorts,
    private val brain: OpponentBrain,
    /** The admiral's random stream for the turn it is playing (derived, so a restore draws the same numbers). */
    private val admiralRng: (turn: Int) -> SplitMix64,
    /** Builds the save file of the match after each action. */
    private val saveOf: (BattleMatch) -> SavedMatch,
    initial: BattleMatch,
    /** Seconds per turn for the player, or null for no shot clock. */
    private val shotClockSeconds: Int? = null,
    /** Officer's hints this battle allows (see [OfficerHints.limitFor]). */
    private val hintLimit: Int = 0,
    /** Id of the admiral whose taunts the log shows, or null for no admiral (the duel). */
    private val admiralId: String? = null,
) {
    private val uiState = MutableStateFlow(render(initial, BattleUi(initial, blankSea(initial), blankSea(initial))))
    val ui: StateFlow<BattleUi> = uiState.asStateFlow()

    private var settings: Settings? = null
    private var job: Job? = null
    private var clockJob: Job? = null
    private var hintJob: Job? = null
    private var skipRequested = false
    private val pausedFlag = MutableStateFlow(false)
    private var finishedReported = false

    private val current: BattleUi get() = uiState.value

    /** Starts (or resumes) the battle loop. Safe to call again: a running loop is not doubled. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            settings = ports.settings()
            if (current.match.finished) {
                reportFinish()
                return@launch
            }
            playLoop()
        }
    }

    /** Stops the loop; a later [start] continues from the saved match. */
    fun stop() {
        job?.cancel()
        job = null
        clockJob?.cancel()
        clockJob = null
    }

    /** Pauses the clock: think time, animations and the shot clock wait. */
    fun setPaused(paused: Boolean) {
        pausedFlag.value = paused
        update { it.copy(paused = paused) }
    }

    fun openPause(open: Boolean) {
        update { it.copy(pauseOpen = open) }
        setPaused(open)
    }

    /** "Tocar para saltar": ends the running wait and animation steps at once. */
    fun skip() {
        if (current.busy) skipRequested = true
    }

    /** A tap on the enemy sea: a weapon's target, a salvo square, or a shot. */
    fun tap(index: Int) {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn) return
        val width = state.match.width
        val cell = Cell(index / width, index % width)
        val open = state.enemy.cells.getOrNull(index)?.isFireable == true
        when (val tap = TargetingReducer.enemyTap(state.targeting, cell, width, state.match.height, open, state.salvoRequired)) {
            is TargetTap.Stay -> update {
                it.copy(
                    targeting = tap.targeting,
                    refusal = tap.refusal,
                    refusedTap = index.takeIf { tap.refusal == TargetingReducer.REFUSAL_ALREADY_FIRED },
                    selected = null,
                )
            }
            is TargetTap.Fire -> fireTap(tap.index)
            is TargetTap.Send -> sendPlayer(tap.action)
        }
    }

    /** A tap on the player's own sea: repairs and moves aim here. */
    fun ownTap(index: Int) {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn) return
        val width = state.match.width
        val cell = Cell(index / width, index % width)
        val matchState = state.match.state
        val shipAt: (Cell) -> Int? = { c ->
            matchState.sides[BattleMatch.PLAYER].ships.indexOfFirst { ship -> ship.placement.cells().any { it == c } }
                .takeIf { it >= 0 }
        }
        when (val tap = TargetingReducer.ownTap(state.targeting, cell, shipAt) { MovePlanner.legalDeltas(matchState, it) }) {
            is TargetTap.Stay -> update { it.copy(targeting = tap.targeting, refusal = tap.refusal) }
            is TargetTap.Send -> sendPlayer(tap.action)
            is TargetTap.Fire -> Unit
        }
    }

    /**
     * "Dica do imediato": lights the [OfficerHints.CELLS] best squares of the HARD density for the player's sea,
     * for [OfficerHints.HIGHLIGHT_MILLIS]. Each use is recorded in the match, so a restore keeps the cap.
     */
    fun useHint() {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn || state.hintsLeft <= 0) return
        val view = AiView.from(Observation.of(state.match.state, BattleMatch.PLAYER))
        val cells = OfficerHint.topCells(view, OfficerHints.CELLS).toSet()
        val match = state.match.copy(hintsUsed = state.match.hintsUsed + 1)
        uiState.value = render(match, state.copy(match = match, hintCells = cells, refusal = null))
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(OfficerHints.HIGHLIGHT_MILLIS)
            update { it.copy(hintCells = emptySet(), enemy = it.enemy.copy(highlight = emptySet())) }
        }
        scope.launch { ports.persist(saveOf(match)) }
    }

    /** A button of the weapons column. Taps again to cancel. Refused with no charge left. */
    fun pickWeapon(weapon: Weapon) {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn) return
        if (WeaponCatalog.charges(state.match.state.sides[BattleMatch.PLAYER].charges, weapon) <= 0) {
            update { it.copy(refusal = REASON_NO_CHARGE) }
            return
        }
        update { it.copy(targeting = TargetingReducer.pickWeapon(it.targeting, weapon), refusal = null, refusedTap = null) }
    }

    /** Button "Move a ship". */
    fun pickMove() {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn) return
        val rules = state.match.state.rules
        val reason = when {
            !rules.movingShips -> REASON_MOVE_NOT_ENABLED
            state.match.state.sides[BattleMatch.PLAYER].moveUsed -> REASON_MOVE_ALREADY_USED
            else -> null
        }
        if (reason != null) {
            update { it.copy(refusal = reason) }
            return
        }
        update { it.copy(targeting = TargetingReducer.pickMove(it.targeting), refusal = null) }
    }

    /** An arrow of the moving ship. */
    fun moveStep(delta: Int) {
        val state = current
        if (state.busy || state.match.finished || !state.match.playerTurn) return
        when (val tap = TargetingReducer.moveStep(state.targeting, delta)) {
            is TargetTap.Send -> sendPlayer(tap.action)
            is TargetTap.Stay -> update { it.copy(refusal = tap.refusal) }
            is TargetTap.Fire -> Unit
        }
    }

    /** "Fire salvo": sends the picked squares. The engine checks the size. */
    fun fireSalvo() {
        val state = current
        val salvo = state.targeting as? Targeting.Salvo ?: return
        if (state.busy || !state.match.playerTurn) return
        val width = state.match.width
        sendPlayer(Action.Salvo(salvo.picked.map { Cell(it / width, it % width) }))
    }

    /** Flips a torpedo or recon line between a row and a column through the same square. */
    fun flipAxis() {
        update { state ->
            val line = state.targeting as? Targeting.Line ?: return@update state
            state.copy(targeting = TargetingReducer.flipAxis(line, state.match.width, state.match.height))
        }
    }

    /** Flips the torpedo's margin between the two edges of its line. */
    fun flipMargin() {
        update { state ->
            val line = state.targeting as? Targeting.Line ?: return@update state
            state.copy(targeting = TargetingReducer.flipMargin(line))
        }
    }

    /** Closes the weapons column without firing. */
    fun cancelTargeting() {
        update { it.copy(targeting = Targeting.Idle, refusal = null, refusedTap = null) }
    }

    // ---- the turn loop -------------------------------------------------------------------------

    private fun fireTap(index: Int) {
        val state = current
        val decision = BattleTaps.decide(
            enemyVisuals = state.enemy.cells,
            selected = state.selected,
            index = index,
            confirmShot = settings?.confirmShot ?: false,
            playerTurn = state.match.playerTurn,
        )
        when (decision) {
            is TapDecision.Ignored -> if (state.enemy.cells.getOrNull(index)?.isFireable != true) {
                update { it.copy(refusedTap = index) }
            }
            is TapDecision.Select -> update { it.copy(selected = decision.index, refusedTap = null) }
            is TapDecision.Fire -> {
                val width = state.match.width
                sendPlayer(Action.Fire(Cell(decision.index / width, decision.index % width)))
            }
        }
    }

    /** Sends one player action: locks the input and runs the action on [scope]. Refused actions unlock and explain. */
    private fun sendPlayer(action: Action) {
        if (current.busy) return
        update { it.copy(busy = true, refusal = null, refusedTap = null, taunt = null) }
        clockJob?.cancel()
        scope.launch { playerAction(action) }
    }

    private suspend fun playerAction(action: Action) {
        val step = current.match.apply(action)
        val refusal = step.events.filterIsInstance<Event.IllegalAction>().firstOrNull()?.reason
        if (refusal != null) {
            update { it.copy(busy = false, refusal = refusal) }
            return
        }
        commit(step.match, step.events, action)
        ports.persist(saveOf(step.match))
        playPlan(step.events, admiralTurn = false, action = action)
        playLoop()
    }

    private suspend fun playLoop() {
        while (true) {
            val match = current.match
            if (match.finished) {
                reportFinish()
                return
            }
            if (match.playerTurn) {
                // The full clock is set before the input unlocks, so no state shows the old turn's clock.
                startClock()
                update { it.copy(busy = false) }
                return
            }
            admiralTurn()
        }
    }

    private suspend fun admiralTurn() {
        val s = settings ?: ports.settings().also { settings = it }
        update { it.copy(busy = true, thinking = true) }
        wait(AdmiralPace.thinkMillis(s.admiralSpeed, Random.nextFloat()))
        update { it.copy(thinking = false) }

        val match = current.match
        val observation = BattleMatch.admiralObservation(match)
        val rng = admiralRng(match.state.turn)
        // The decision is computed off the main thread; the engine state stays on the scope.
        var action: Action = withContext(Dispatchers.Default) { brain.decide(observation, rng) }
        var step = match.apply(action)
        if (!step.accepted) {
            action = withContext(Dispatchers.Default) { brain.fallback(observation, rng) }
            step = match.apply(action)
        }
        if (!step.accepted) {
            action = Action.Timeout
            step = match.apply(action)
        }
        commit(step.match, step.events, action)
        ports.persist(saveOf(step.match))
        val taunt = admiralId?.let { Taunts.due(it, match.state.turn) }
        update { it.copy(taunt = taunt) }
        playPlan(step.events, admiralTurn = true, action = action)
    }

    /** Plays the plan of [events] step by step. The input stays locked until it ends. */
    private suspend fun playPlan(events: List<Event>, admiralTurn: Boolean, action: Action?) {
        val s = settings ?: ports.settings().also { settings = it }
        val match = current.match
        val plan = AnimationTimeline.plan(
            events = events,
            width = match.width,
            viewer = BattleMatch.PLAYER,
            reduced = ports.reducedMotion(s),
            admiralTurn = admiralTurn,
            swapSeas = s.swapSeasOnAdmiralTurn,
            screenShake = s.screenShake,
            seed = match.state.attemptSeed + match.state.turn,
            height = match.height,
            action = action,
        )
        update { it.copy(busy = true) }
        skipRequested = false
        for (step in plan.steps) {
            update { state ->
                state.copy(
                    enemyEffects = step.enemyEffects,
                    ownEffects = step.ownEffects,
                    ownSeaLarge = step.ownSeaLarge ?: state.ownSeaLarge,
                    shakeTrigger = if (step.shake != null) state.shakeTrigger + 1 else state.shakeTrigger,
                    shakeLevel = step.shake ?: state.shakeLevel,
                    notice = step.notices.lastOrNull(),
                    fadingCells = step.fadingCells.toSet(),
                )
            }
            step.sounds.forEach { ports.sound(it) }
            if (s.vibration) step.haptics.forEach { ports.haptic(it) }
            wait(step.durationMillis)
        }
        skipRequested = false
        update { it.copy(busy = false, notice = null, fadingCells = emptySet(), ownSeaLarge = if (admiralTurn) false else it.ownSeaLarge) }
    }

    /**
     * The shot clock of the player's turn. It runs only on the player's turn, not while input is locked,
     * not while paused (the pause sheet or the background). A new turn starts with a full clock.
     */
    private fun startClock() {
        clockJob?.cancel()
        clockJob = null
        update { it.copy(clock = ShotClock.restart(shotClockSeconds)) }
        val seconds = shotClockSeconds ?: return
        if (seconds <= 0) return
        clockJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val state = current
                if (state.busy || state.match.finished || !state.match.playerTurn) return@launch
                if (pausedFlag.value) continue
                val advance = state.clock.advance(TICK_MS, running = true)
                update { it.copy(clock = advance.clock) }
                if (advance.tickSecond) ports.sound(SoundEffect.CLOCK_TICK)
                if (advance.expiredNow) {
                    sendPlayer(Action.Timeout)
                    return@launch
                }
            }
        }
    }

    /** Waits [millis] of battle time: frozen while paused, cut short by a skip. */
    private suspend fun wait(millis: Long) {
        var left = millis
        while (left > 0 && !skipRequested) {
            if (pausedFlag.value) {
                pausedFlag.first { !it }
                continue
            }
            val chunk = minOf(left, TICK_MS)
            delay(chunk)
            left -= chunk
        }
    }

    private suspend fun reportFinish() {
        if (finishedReported) return
        finishedReported = true
        clockJob?.cancel()
        val state = current
        val summary = QuickBattle.summarize(state.match.state)?.let { OfficerHints.capStars(it, state.match.hintsUsed) } ?: return
        val finalUi = state.copy(summary = summary, busy = false)
        uiState.value = finalUi
        ports.finish(state.match, summary, finalUi)
    }

    // ---- state helpers -------------------------------------------------------------------------

    private fun commit(match: BattleMatch, events: List<Event>, action: Action) {
        val line = BattleLog.lineOf(events) ?: current.lastLog
        val base = current.copy(match = match, lastLog = line, selected = null)
        uiState.value = render(match, base)
    }

    private fun update(transform: (BattleUi) -> BattleUi) {
        uiState.value = transform(current)
    }

    private fun blankSea(match: BattleMatch): BoardRenderModel =
        BoardRenderModel(match.width, match.height, List(match.width * match.height) { CellVisual.UNKNOWN })

    /** Rebuilds the render models from the match, for the player. */
    private fun render(match: BattleMatch, base: BattleUi): BattleUi = BattleRender.of(match, BattleMatch.PLAYER, base)
        .let { rendered ->
            rendered.copy(
                hintsLeft = (hintLimit - match.hintsUsed).coerceAtLeast(0),
                enemy = rendered.enemy.copy(highlight = rendered.hintCells),
            )
        }

    companion object {
        private const val TICK_MS = 40L

        /** Reason codes of refusals that the controller itself decides (the engine's own codes come from Engine). */
        const val REASON_NO_CHARGE = "NO_CHARGE"
        const val REASON_MOVE_NOT_ENABLED = "MOVE_NOT_ENABLED"
        const val REASON_MOVE_ALREADY_USED = "MOVE_ALREADY_USED"
    }
}
