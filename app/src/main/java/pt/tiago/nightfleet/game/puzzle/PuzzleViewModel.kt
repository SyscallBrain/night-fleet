package pt.tiago.nightfleet.game.puzzle

import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.battle.BattleRecords
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.audio.GameAudio
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.catalog.CatalogRepository
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.ProfileStore
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.SavedMatchStore
import pt.tiago.nightfleet.data.withDailyResult
import pt.tiago.nightfleet.game.campaign.CampaignOutcome
import pt.tiago.nightfleet.game.campaign.CampaignProgress
import pt.tiago.nightfleet.game.daily.DailyChallenge
import kotlinx.coroutines.currentCoroutineContext

/** What the puzzle screen shows. */
sealed interface PuzzleScreenState {
    data object Loading : PuzzleScreenState

    data class Ready(val state: PuzzleUiState) : PuzzleScreenState

    /** [NO_SAVED]: resume requested with nothing saved. [GENERATION]: no puzzle could be made. */
    data class Failed(val failure: Failure) : PuzzleScreenState {
        enum class Failure { NO_SAVED, GENERATION }
    }
}

/**
 * Holds one puzzle on screen. Every action goes through [PuzzleReducer]; the result is saved after each change to the
 * slot of its source: ENDLESS for generated puzzles, CAMPAIGN for a campaign PUZZLE mission and DAILY for the daily
 * puzzle. A solve is committed to the profile once, in the way of that source. [audio] is optional: without it the
 * screen plays no sounds and no haptics. [catalog] is needed for campaign and daily requests.
 */
class PuzzleViewModel(
    private val matches: SavedMatchStore,
    private val profile: ProfileStore,
    private val request: PuzzleRequest,
    private val audio: GameAudio? = null,
    private val catalog: CatalogRepository? = null,
    private val seed: () -> Long = { System.nanoTime() xor System.currentTimeMillis() },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val mutableScreen = MutableStateFlow<PuzzleScreenState>(PuzzleScreenState.Loading)
    val screen: StateFlow<PuzzleScreenState> = mutableScreen.asStateFlow()

    /** Persistence jobs run in order on one coroutine; conflated, so only the latest save is written. */
    private val jobs = Channel<PersistJob>(Channel.CONFLATED)

    private var sessionStart = 0L
    private var elapsedBase = 0L

    /** Campaign and daily plays: the match id of this play, unique per play so that each play is committed once. */
    private var playId: String? = null

    private sealed interface PersistJob {
        val state: PuzzleUiState

        data class Save(override val state: PuzzleUiState) : PersistJob
        data class Commit(override val state: PuzzleUiState) : PersistJob
    }

    init {
        viewModelScope.launch { persistLoop() }
        viewModelScope.launch { load() }
        viewModelScope.launch { ticker() }
    }

    fun dispatch(action: PuzzleAction) {
        val current = (mutableScreen.value as? PuzzleScreenState.Ready)?.state ?: return
        val next = PuzzleReducer.reduce(current, action)
        if (next == current) return
        mutableScreen.value = PuzzleScreenState.Ready(next)
        if (action is PuzzleAction.Tick) return
        if (next.solved) {
            if (!current.solved) onSolved()
            jobs.trySend(PersistJob.Commit(next))
            return
        }
        if (action.isPlayerEdit()) audio?.sounds?.play(SoundEffect.UI_TAP)
        jobs.trySend(PersistJob.Save(next))
    }

    private suspend fun load() {
        val state = when (request) {
            PuzzleRequest.Resume -> {
                val saved = matches.activeMatch(MatchKind.ENDLESS, profile.load())?.let(PuzzleSnapshotCodec::fromSavedMatch)
                if (saved == null) {
                    mutableScreen.value = PuzzleScreenState.Failed(PuzzleScreenState.Failed.Failure.NO_SAVED)
                    return
                }
                saved
            }
            is PuzzleRequest.New -> {
                val created = withContext(Dispatchers.Default) { PuzzleFactory.create(request.size, request.grade, seed()) }
                if (created == null) {
                    mutableScreen.value = PuzzleScreenState.Failed(PuzzleScreenState.Failed.Failure.GENERATION)
                    return
                }
                created
            }
            is PuzzleRequest.Campaign, is PuzzleRequest.Daily -> loadCatalogPuzzle() ?: run {
                mutableScreen.value = PuzzleScreenState.Failed(PuzzleScreenState.Failed.Failure.GENERATION)
                return
            }
        }
        elapsedBase = state.elapsedMillis
        sessionStart = clock()
        mutableScreen.value = PuzzleScreenState.Ready(state)
        jobs.trySend(PersistJob.Save(state))
    }

    /**
     * The catalog puzzle of a campaign or daily request. A save of the same mission (or day) is resumed with its grid;
     * otherwise the puzzle is read from the catalog and a new play id is made.
     */
    private suspend fun loadCatalogPuzzle(): PuzzleUiState? {
        val repository = catalog ?: return null
        val kind = slotKind()
        val saved = matches.activeMatch(kind, profile.load())
        if (saved != null && saved.missionId == slotKey()) {
            PuzzleSnapshotCodec.fromSavedMatch(saved, kind)?.let { state ->
                playId = saved.matchId
                return state
            }
        }
        val fresh = withContext(Dispatchers.IO) { CatalogPuzzles.load(repository, request) } ?: return null
        playId = "${slotKey()}#${java.lang.Long.toHexString(seed())}"
        return fresh
    }

    /** Advances the clock once a second while the puzzle is open and unsolved. */
    private suspend fun ticker() {
        while (currentCoroutineContext().isActive) {
            delay(TICK_MILLIS)
            val ready = mutableScreen.value as? PuzzleScreenState.Ready ?: continue
            if (ready.state.solved) continue
            dispatch(PuzzleAction.Tick(elapsedBase + (clock() - sessionStart)))
        }
    }

    private suspend fun persistLoop() {
        for (job in jobs) {
            val state = job.state
            try {
                when (job) {
                    is PersistJob.Save -> matches.save(snapshotOf(state))
                    is PersistJob.Commit -> commit(state)
                }
            } catch (e: Exception) {
                // Best effort: the screen keeps working and the next action saves again.
                Log.w(TAG, "could not save the puzzle", e)
            }
        }
    }

    private suspend fun commit(state: PuzzleUiState) {
        when (request) {
            is PuzzleRequest.Campaign -> profile.commitResult(playId ?: state.definition.id, MatchKind.CAMPAIGN) {
                CampaignProgress.withResult(
                    BattleRecords.withPuzzleSolved(it, request.difficulty),
                    CampaignOutcome(
                        missionId = request.missionId,
                        difficulty = request.difficulty,
                        won = true,
                        shots = 0,
                        usedHint = state.hintsUsed > 0,
                        thresholds = null,
                        stars = PuzzleStars.of(state),
                    ),
                )
            }
            is PuzzleRequest.Daily -> profile.commitResult(playId ?: state.definition.id, MatchKind.DAILY) {
                BattleRecords.withPuzzleSolved(it, request.difficulty)
                    .withDailyResult(request.date, request.difficulty, ChallengeKind.PUZZLE, PuzzleStars.of(state))
            }
            else -> profile.commitResult(state.definition.id, MatchKind.ENDLESS) {
                PuzzleProgress.recordSolved(BattleRecords.withPuzzleSolved(it, statsDifficultyOf(state.grade)), state.definition.width)
            }
        }
    }

    /** The save of [state] in the slot of its source. */
    private fun snapshotOf(state: PuzzleUiState): SavedMatch {
        val now = System.currentTimeMillis()
        return when (request) {
            is PuzzleRequest.Campaign -> PuzzleSnapshotCodec.toSavedMatch(
                state, now, MatchKind.CAMPAIGN, playId ?: state.definition.id, request.missionId, request.difficulty,
            )
            is PuzzleRequest.Daily -> PuzzleSnapshotCodec.toSavedMatch(
                state, now, MatchKind.DAILY, playId ?: state.definition.id, slotKey(), request.difficulty,
            )
            else -> PuzzleSnapshotCodec.toSavedMatch(state, now)
        }
    }

    private fun slotKind(): MatchKind = when (request) {
        is PuzzleRequest.Campaign -> MatchKind.CAMPAIGN
        is PuzzleRequest.Daily -> MatchKind.DAILY
        else -> MatchKind.ENDLESS
    }

    /** What identifies the saved play of a campaign mission or of a daily day. */
    private fun slotKey(): String = when (val r = request) {
        is PuzzleRequest.Campaign -> r.missionId
        is PuzzleRequest.Daily -> DailyChallenge.matchId(r.date, r.difficulty, ChallengeKind.PUZZLE)
        else -> ""
    }

    private fun onSolved() {
        val game = audio ?: return
        game.sounds.play(SoundEffect.STAR)
        game.haptics.play(HapticPattern.HIT)
        viewModelScope.launch {
            delay(VICTORY_DELAY_MILLIS)
            game.sounds.play(SoundEffect.VICTORY)
        }
    }

    private fun PuzzleAction.isPlayerEdit(): Boolean = this is PuzzleAction.Tap ||
        this is PuzzleAction.LongPress ||
        this is PuzzleAction.PaintWater ||
        this is PuzzleAction.Mark

    private companion object {
        const val TAG = "NightFleetPuzzle"
        const val TICK_MILLIS = 1000L
        const val VICTORY_DELAY_MILLIS = 350L
    }
}

/** The statistics tab of an endless puzzle: its grade. */
internal fun statsDifficultyOf(grade: PuzzleGrade): Difficulty = when (grade) {
    PuzzleGrade.EASY -> Difficulty.EASY
    PuzzleGrade.MEDIUM -> Difficulty.MEDIUM
    PuzzleGrade.HARD -> Difficulty.HARD
}
