package pt.tiago.nightfleet.game.battle

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.audio.AudioSettings
import pt.tiago.nightfleet.audio.EffectThrottle
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.MusicTrack
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.meta.GameEvent
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.AnalysisSummary
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.withDailyResult
import pt.tiago.nightfleet.game.campaign.CampaignScoring
import pt.tiago.nightfleet.game.daily.DailyStake
import pt.tiago.nightfleet.game.analysis.AnalysisInput
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.data.MotionPreference
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.RecordCount
import pt.tiago.nightfleet.game.campaign.BattleResultRules
import pt.tiago.nightfleet.game.campaign.BossProgress
import pt.tiago.nightfleet.game.campaign.CampaignOutcome
import pt.tiago.nightfleet.game.campaign.CampaignProgress
import pt.tiago.nightfleet.game.campaign.CampaignStake
import pt.tiago.nightfleet.game.campaign.ConvoyOutcome
import pt.tiago.nightfleet.game.campaign.NextStar
import pt.tiago.nightfleet.game.campaign.ResultExtras
import pt.tiago.nightfleet.game.campaign.SeriesScore
import pt.tiago.nightfleet.game.modes.ModeResults
import pt.tiago.nightfleet.game.modes.ModeStake
import pt.tiago.nightfleet.game.modes.PatrolRules
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.meta.AchievementDef
import pt.tiago.nightfleet.data.BossSeries
import pt.tiago.nightfleet.data.variantKey
import java.time.LocalDate
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.ui.board.systemReducedMotion

/** Where a battle comes from: a new quick battle, a saved one, or a battle built from a setup (free play). */
sealed class BattleSource {
    data class New(val placements: List<Placement>, val attemptSeed: Long) : BattleSource()
    data class Restored(val match: BattleMatch, val attemptSeed: Long, val placements: List<Placement>) : BattleSource()

    /**
     * A battle built from [setup]. [restored] is the saved match of the same setup, or null for a new battle.
     * [opponentName] is the name the log uses for the admiral.
     */
    data class FromSetup(
        val setup: MatchSetup,
        val opponentName: String,
        val restored: BattleMatch? = null,
        /** Set for a campaign mission: the result becomes campaign progress (see game/campaign). */
        val campaign: CampaignStake? = null,
        /** Set for the daily hunt: the result becomes a daily result (see game/daily). */
        val daily: DailyStake? = null,
        /** Set for a patrol sea or a tournament duel: the result moves the mode's record (see game/modes). */
        val stake: ModeStake? = null,
    ) : BattleSource()
}

/**
 * The battle view model: owns the [BattleController] and connects it to the stores, the audio and the
 * settings. The rules live in the pure classes of this package (see [BattleController]).
 */
class BattleViewModel(
    private val container: AppContainer,
    context: Context,
    source: BattleSource,
) : ViewModel() {

    /** The application context only: the view model outlives the activity (lint StaticFieldLeak). */
    private val appContext: Context = context.applicationContext

    private val kind: MatchKind
    private val attemptSeed: Long
    private val placements: List<Placement>
    private val setup: MatchSetup?
    private val opponentName: String
    private val campaign: CampaignStake?
    private val daily: DailyStake?
    private val mode: ModeStake?
    /** Id of this battle in the logbook, the commit and the save: the same in all three. */
    private val matchId: String
    private val controller: BattleController
    /** The state the battle started from: replaying the actions from it gives the battle details (see BattleDetails). */
    private val startState: BattleState
    private val sounds = EffectThrottle<SoundEffect>(SOUND_GAP_MS)

    val ui: StateFlow<BattleUi>

    private val resultExtras = MutableStateFlow<ResultExtras?>(null)

    /** Time this battle spent on screen, for the statistics: counted from start or foreground to background or end. */
    private var activeMillis = 0L
    private var activeSince: Long? = android.os.SystemClock.elapsedRealtime()

    private fun pauseActiveClock() {
        activeSince?.let { activeMillis += android.os.SystemClock.elapsedRealtime() - it }
        activeSince = null
    }

    /** What the result sheet shows beyond the summary (campaign stars, achievements, rank). Null until committed. */
    val extras: StateFlow<ResultExtras?> = resultExtras.asStateFlow()

    init {
        val initial: BattleMatch
        var shotClock: Int? = null
        when (source) {
            is BattleSource.New -> {
                kind = MatchKind.FREE_PLAY
                attemptSeed = source.attemptSeed
                placements = source.placements
                setup = null
                campaign = null
                daily = null
                mode = null
                opponentName = QuickBattle.ADMIRAL_NAME
                initial = when (val created = BattleMatch.create(placements, attemptSeed)) {
                    is SetupResult.Created -> BattleMatch(created.state)
                    is SetupResult.Rejected -> error("Invalid quick battle: ${created.reasons}")
                }
            }
            is BattleSource.Restored -> {
                kind = MatchKind.FREE_PLAY
                attemptSeed = source.attemptSeed
                placements = source.placements
                setup = null
                campaign = null
                daily = null
                mode = null
                opponentName = QuickBattle.ADMIRAL_NAME
                initial = source.match
            }
            is BattleSource.FromSetup -> {
                campaign = source.campaign
                daily = source.daily
                mode = source.stake
                kind = when {
                    source.campaign != null -> MatchKind.CAMPAIGN
                    source.daily != null -> MatchKind.DAILY
                    source.stake != null -> source.stake.kind
                    else -> MatchKind.FREE_PLAY
                }
                setup = source.setup
                attemptSeed = source.setup.attemptSeed
                placements = source.setup.placements[BattleMatch.PLAYER]
                opponentName = source.opponentName
                shotClock = source.setup.rules.shotClockSeconds
                initial = source.restored ?: when (val created = source.setup.create()) {
                    is SetupResult.Created -> BattleMatch(created.state)
                    is SetupResult.Rejected -> error("Invalid battle setup: ${created.reasons}")
                }
            }
        }
        val currentSetup = setup
        val currentDaily = daily
        startState = when (source) {
            is BattleSource.FromSetup -> (source.setup.create() as? SetupResult.Created)?.state
            else -> (BattleMatch.create(placements, attemptSeed) as? SetupResult.Created)?.state
        } ?: initial.state
        matchId = when {
            mode != null -> mode.matchId
            currentSetup == null -> BattleSave.matchIdOf(attemptSeed)
            currentDaily != null -> currentDaily.playId
            else -> SetupSave.freeMatchId(attemptSeed)
        }
        // The admiral of the battle: Coral on MEDIUM for quick battles, the free play choice otherwise.
        val opponentSetup = (source as? BattleSource.FromSetup)?.setup
        val level = opponentSetup?.admiralLevel ?: OpponentLevel.MEDIUM
        // The duel has no admiral: no brain worth speaking of, no taunts and no hints.
        val isDuel = opponentSetup != null && opponentSetup.opponentId == null
        val admiralId = if (isDuel) null else opponentSetup?.opponentId ?: QuickBattle.ADMIRAL_ID
        val brain = AdmiralOpponent.forLevel(admiralId ?: QuickBattle.ADMIRAL_ID, level, attemptSeed, opponentSetup?.admiralAi)
        val hintLimit = when {
            isDuel || mode != null -> 0
            campaign != null -> CampaignScoring.hintsFor(campaign!!.difficulty)
            else -> OfficerHints.limitFor(level)
        }
        controller = BattleController(
            scope = viewModelScope,
            ports = Ports(),
            brain = brain,
            hintLimit = hintLimit,
            admiralId = admiralId,
            admiralRng = { turn -> BattleMatch.admiralRng(attemptSeed, turn) },
            saveOf = { match ->
                val now = System.currentTimeMillis()
                when {
                    currentSetup == null -> BattleSave.toSaved(match, attemptSeed, placements, now)
                    mode != null -> SetupSave.toSaved(kind, matchId, currentSetup, match, now, difficulty = mode.difficulty)
                    currentDaily != null -> SetupSave.toSaved(
                        kind, matchId, currentSetup, match, now,
                        difficulty = currentDaily.difficulty,
                        missionId = currentDaily.dayKey,
                    )
                    else -> SetupSave.toSaved(
                        kind, matchId, currentSetup, match, now,
                        difficulty = campaign?.difficulty,
                        missionId = campaign?.missionId,
                        bossSeries = campaign?.series,
                    )
                }
            },
            initial = initial,
            shotClockSeconds = shotClock,
        )
        ui = controller.ui
        viewModelScope.launch {
            val s = container.settings.current()
            container.audio.update(AudioSettings(s.sfxVolume, s.musicVolume, s.vibration))
            container.audio.music.playTrack(MusicTrack.SEA_03)
        }
        controller.start()
    }

    fun tap(index: Int) = controller.tap(index)

    fun ownTap(index: Int) = controller.ownTap(index)

    fun pickWeapon(weapon: Weapon) = controller.pickWeapon(weapon)

    fun pickMove() = controller.pickMove()

    fun moveStep(delta: Int) = controller.moveStep(delta)

    fun fireSalvo() = controller.fireSalvo()

    fun flipAxis() = controller.flipAxis()

    fun flipMargin() = controller.flipMargin()

    fun cancelTargeting() = controller.cancelTargeting()

    fun skip() = controller.skip()

    /** The officer's hint: lights the best squares of the player's sea for a while. */
    fun useHint() = controller.useHint()

    /** Pause sheet and lifecycle: the clock and the music stop together. */
    fun openPause(open: Boolean) = controller.openPause(open)

    fun setPaused(paused: Boolean) = controller.setPaused(paused)

    /** Resumes the match after the app came back to the front. */
    fun onForeground() {
        if (activeSince == null) activeSince = android.os.SystemClock.elapsedRealtime()
        if (!ui.value.pauseOpen) controller.setPaused(false)
        container.audio.resume()
    }

    fun onBackground() {
        pauseActiveClock()
        controller.setPaused(true)
        container.audio.pause()
    }

    /** Quick settings of the pause sheet. */
    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            container.settings.update(transform)
            val s = container.settings.current()
            container.audio.update(AudioSettings(s.sfxVolume, s.musicVolume, s.vibration))
        }
    }

    /**
     * What the analysis and the replay of this battle need: the logbook id and the replay payload. Null while the
     * battle is running.
     */
    suspend fun analysisInput(): AnalysisInput? {
        val match = ui.value.match
        if (!match.finished) return null
        val stored = runCatching { container.logbook.load().firstOrNull { it.id == matchId }?.analysis }.getOrNull()
        return AnalysisInput(matchId, BattleRecords.replayPayload(attemptSeed, placements, match.actions, setup), stored)
    }

    /** Keeps the analysis in the logbook entry of the battle, so that it is not recomputed. */
    suspend fun saveAnalysis(logId: String, analysis: AnalysisSummary) {
        runCatching {
            val entries = container.logbook.load()
            container.logbook.replaceAll(entries.map { if (it.id == logId) it.copy(analysis = analysis) else it })
        }.onFailure { Log.w(TAG, "Could not save the analysis", it) }
    }

    /** "Recomeçar": drops the saved match so the next battle starts from the start screen. */
    fun abandon(onDone: () -> Unit) {
        viewModelScope.launch {
            controller.stop()
            runCatching { container.matches.clear(kind) }
            onDone()
        }
    }

    override fun onCleared() {
        controller.stop()
    }

    private inner class Ports : BattlePorts {
        override suspend fun settings(): Settings = container.settings.current()

        override fun reducedMotion(settings: Settings): Boolean = when (settings.reducedMotion) {
            MotionPreference.ON -> true
            MotionPreference.OFF -> false
            MotionPreference.FOLLOW_SYSTEM -> systemReducedMotion(appContext)
        }

        override suspend fun persist(saved: SavedMatch) {
            runCatching { container.matches.save(saved) }
                .onFailure { Log.w(TAG, "Could not save the battle", it) }
        }

        override suspend fun finish(match: BattleMatch, summary: QuickBattle.Summary, ui: BattleUi) {
            val currentSetup = setup
            val now = System.currentTimeMillis()
            val shotCells = match.state.sides[1].incoming.entries
                .filter { it.attacker == BattleMatch.PLAYER && it.result !is ShotResult.AutoWater }
                .map { it.cell }
            val entry = BattleRecords.logEntry(
                matchId = matchId,
                attemptSeed = attemptSeed,
                placements = placements,
                match = match,
                summary = summary,
                ownMap = ui.own,
                enemyMap = ui.enemy,
                nowMillis = now,
                setup = currentSetup,
                kind = mode?.kind ?: MatchKind.FREE_PLAY,
                difficulty = mode?.difficulty ?: QuickBattle.DIFFICULTY,
            )
            val opponentId = currentSetup?.opponentId
            val stake = campaign
            val convoy = ConvoyOutcome.of(match.state)
            val details = BattleDetails.of(startState, match.actions)
            pauseActiveClock()
            val statsDifficulty = stake?.difficulty ?: mode?.difficulty ?: daily?.difficulty
                ?: currentSetup?.admiralLevel?.let(::statsDifficultyOf) ?: QuickBattle.DIFFICULTY
            var committed: ResultExtras? = null
            runCatching {
                container.profile.commitResult(matchId, kind) { committedProfile ->
                    // Every mode counts in the statistics of its difficulty (the duel of two players has its own screen).
                    val profile = BattleRecords.withBattle(
                        committedProfile,
                        statsDifficulty,
                        summary,
                        shotCells,
                        match.width,
                        weaponsUsed = details.weaponsUsed,
                        playSeconds = (activeMillis / 1000).coerceAtMost(MAX_BATTLE_SECONDS),
                    )
                    daily?.let { d ->
                        val stars = CampaignScoring.stars(summary.won, summary.shots, d.thresholds, usedHint = summary.usedHint)
                        return@commitResult profile.withDailyResult(d.date, d.difficulty, ChallengeKind.HUNT, stars)
                    }
                    val modeStake = mode
                    val base = when {
                        stake != null -> campaignProgress(profile, stake, summary, convoy)
                        modeStake != null -> ModeResults.afterBattle(profile, modeStake, summary.won)
                        else -> quickProgress(profile, opponentId, summary)
                    }
                    val facts = BattleFacts(
                        boardSize = match.width,
                        relentless = currentSetup?.admiralLevel == OpponentLevel.RELENTLESS,
                        huntHalfBudget = stake != null && match.state.mission == MissionType.HUNT &&
                            match.state.shotBudget?.let { summary.shots * 2 <= it } == true,
                    )
                    val event = BattleResultRules.gameEvent(kind, stake?.difficulty ?: modeStake?.difficulty, summary, convoy)
                        .withDetails(details, facts)
                        .let { modeEvent(it, modeStake, summary.won) }
                    val (withAchievements, unlocked) = BattleResultRules.evaluateAchievements(base, event, LocalDate.now())
                    committed = extrasOf(profile, withAchievements, stake, modeStake, summary, convoy, unlocked)
                    withAchievements
                }
                container.logbook.add(entry)
                committed?.let { resultExtras.value = it }
            }.onFailure { Log.w(TAG, "Could not record the battle", it) }
        }

        override fun sound(effect: SoundEffect) {
            if (sounds.accept(effect, android.os.SystemClock.elapsedRealtime())) container.audio.sounds.play(effect)
        }

        override fun haptic(pattern: HapticPattern) {
            container.audio.haptics.play(pattern)
        }
    }

    /** Progress of a free play or quick battle: the admiral's record when it is a free play (statistics are added for every mode). */
    private fun quickProgress(profile: Profile, opponentId: String?, summary: QuickBattle.Summary): Profile =
        if (opponentId == null || summary.reason == EndReason.TURN_LIMIT) profile else profile.copy(
            freePlayRecord = profile.freePlayRecord + (opponentId to recordAfter(profile.freePlayRecord[opponentId], summary.won)),
        )

    /** The campaign progress of one finished battle: a boss duel goes to its series, the rest to the variant. */
    private fun campaignProgress(profile: Profile, stake: CampaignStake, summary: QuickBattle.Summary, convoy: ConvoyOutcome): Profile =
        if (stake.isBossDuel) {
            BossProgress.withDuel(
                profile,
                stake,
                won = summary.won,
                shots = summary.shots,
                draw = summary.reason == EndReason.TURN_LIMIT,
                usedHint = summary.usedHint,
            )
        } else {
            CampaignProgress.withResult(
                profile,
                CampaignOutcome(
                    missionId = stake.missionId,
                    difficulty = stake.difficulty,
                    won = summary.won,
                    shots = summary.shots,
                    usedHint = summary.usedHint,
                    thresholds = stake.thresholds,
                    admiralId = stake.admiralId,
                    stars = BattleResultRules.campaignStars(stake, summary, convoy),
                ),
            )
        }

    /**
     * The facts of the result sheet, read from the profile before the battle ([before]) and the committed one ([after]).
     */
    private fun extrasOf(
        before: Profile,
        after: Profile,
        stake: CampaignStake?,
        mode: ModeStake?,
        summary: QuickBattle.Summary,
        convoy: ConvoyOutcome,
        unlocked: List<AchievementDef>,
    ): ResultExtras {
        val (rankBefore, rankAfter) = BattleResultRules.rankChange(before, after)
        val paint = BattleResultRules.newPaint(before, after)
        fun extras(stars: Int, next: NextStar, replay: Boolean, series: SeriesScore?) = ResultExtras(
            stars = stars,
            nextStar = next,
            achievements = unlocked,
            rankBefore = rankBefore,
            rankAfter = rankAfter,
            paintUnlocked = paint,
            drawReplay = replay,
            series = series,
        )
        if (mode != null) {
            return extras(0, NextStar.None, replay = false, series = null).copy(
                showStars = false,
                modeLine = modeLine(mode, summary),
                primaryLabel = modeLabel(mode, summary.won),
            )
        }
        if (stake == null) return extras(summary.stars, NextStar.None, replay = false, series = null)
        if (!stake.isBossDuel) {
            val stars = BattleResultRules.campaignStars(stake, summary, convoy)
            return extras(stars, BattleResultRules.nextStar(stake, stars, summary.won, convoy), replay = false, series = null)
        }
        val key = variantKey(stake.missionId, stake.difficulty)
        val series = before.bossSeries[key] ?: BossSeries()
        val draw = summary.reason == EndReason.TURN_LIMIT
        if (draw && !series.replayPending) {
            return extras(0, NextStar.None, replay = true, series = SeriesScore(series.wins, series.losses, decided = false))
        }
        val next = BossProgress.advance(series, summary.won, draw, summary.shots, summary.usedHint)
        val decided = BossProgress.isDecided(next)
        val stars = if (decided) BossProgress.stars(next, stake.thresholds) else 0
        return extras(stars, NextStar.None, replay = false, series = SeriesScore(next.wins, next.losses, decided = decided))
    }

    /** The achievement event of a mode battle: the seas cleared so far, or whether the whole tournament was won. */
    private fun modeEvent(event: GameEvent, mode: ModeStake?, won: Boolean): GameEvent = when (mode) {
        null -> event
        is ModeStake.Patrol -> event.copy(patrolSeas = ModeResults.seasClearedAfter(mode, won))
        is ModeStake.Tournament -> event.copy(tournamentWon = ModeResults.wonTournament(mode, won))
    }

    /** The run status under the result of a mode battle. */
    private fun modeLine(mode: ModeStake, summary: QuickBattle.Summary): String? = when (mode) {
        is ModeStake.Patrol -> if (summary.won) {
            val left = mode.reserve - summary.shots + PatrolRules.refund(summary.enemyShipsSunk)
            appContext.resources.getQuantityString(R.plurals.patrol_line_cleared, left, mode.sea, left)
        } else {
            appContext.getString(R.string.patrol_line_lost, mode.sea)
        }
        is ModeStake.Tournament -> {
            val won = ModeResults.roundsWonAfter(mode, summary.won)
            if (ModeResults.wonTournament(mode, summary.won)) null else appContext.getString(R.string.tournament_line, won, mode.rounds)
        }
    }

    /** The main button of a mode result: the next sea or duel when the run goes on, otherwise the way back. */
    private fun modeLabel(mode: ModeStake, won: Boolean): String = when (mode) {
        is ModeStake.Patrol -> appContext.getString(if (won) R.string.patrol_next_sea else R.string.mode_see_result)
        is ModeStake.Tournament -> appContext.getString(
            if (won && mode.round < mode.rounds) R.string.tournament_next_duel else R.string.mode_see_result,
        )
    }

    companion object {
        private const val TAG = "Battle"
        /** Minimum gap between two plays of the same sound effect. */
        const val SOUND_GAP_MS = 60L

        /** One battle adds at most this much play time, so a phone left on the battle screen does not inflate it. */
        const val MAX_BATTLE_SECONDS = 2L * 60 * 60

        /** The statistics tab of a free play admiral: the Relentless admiral counts as Hard. */
        fun statsDifficultyOf(level: OpponentLevel): Difficulty = when (level) {
            OpponentLevel.EASY -> Difficulty.EASY
            OpponentLevel.MEDIUM -> Difficulty.MEDIUM
            OpponentLevel.HARD, OpponentLevel.RELENTLESS -> Difficulty.HARD
        }

        /** Win or loss record of one admiral in free play. A draw leaves the record as it is. */
        fun recordAfter(record: RecordCount?, won: Boolean): RecordCount {
            val current = record ?: RecordCount()
            return if (won) current.copy(wins = current.wins + 1) else current.copy(losses = current.losses + 1)
        }
    }
}
