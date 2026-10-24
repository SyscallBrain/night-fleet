package pt.tiago.nightfleet.ui.screens.modes

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleSource
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.RestoreResult
import pt.tiago.nightfleet.game.battle.SetupSave
import pt.tiago.nightfleet.game.modes.ModeStake
import pt.tiago.nightfleet.game.modes.PatrolRules
import pt.tiago.nightfleet.game.modes.PatrolRun
import pt.tiago.nightfleet.game.modes.PatrolSea
import pt.tiago.nightfleet.game.modes.SeaEnd
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.screens.battle.BattleScreen
import pt.tiago.nightfleet.ui.screens.freeplay.ChoiceRow
import pt.tiago.nightfleet.ui.screens.freeplay.Section
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId

/**
 * The patrol from "More modes": the setup with the record, then one hunt per sea that shares the
 * reserve of the run, then the result. The run is saved between seas and the sea in progress after every shot, so a
 * patrol can be continued after the app is closed.
 */
@Composable
fun PatrolFlow(
    container: AppContainer,
    themeId: ThemeId,
    onExit: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    var config by remember { mutableStateOf<PatrolConfigDto?>(null) }
    var phase by remember { mutableStateOf<PatrolPhase>(PatrolPhase.Loading) }
    var counter by remember { mutableIntStateOf(0) }
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.MEDIUM) }

    LaunchedEffect(Unit) {
        val loaded = (container.catalog.patrol() as? CatalogResult.Ok)?.value
        config = loaded
        if (loaded == null) {
            phase = PatrolPhase.Broken
        } else {
            val saved = container.modeRuns.patrol()
            difficulty = saved?.difficulty ?: difficulty
            phase = PatrolPhase.Setup(saved = saved, best = container.profile.load().patrolBest)
        }
    }

    /** Starts a new run, or a sea of the run, from the start: its match replaces any saved one. */
    fun begin(run: PatrolRun) {
        val cfg = config ?: return
        scope.launch {
            val setup = PatrolSea.setup(run, cfg)
            if (setup == null) {
                phase = PatrolPhase.Setup(saved = container.modeRuns.patrol(), best = container.profile.load().patrolBest, notice = R.string.patrol_setup_failed)
                return@launch
            }
            container.matches.clear(MatchKind.PATROL)
            container.modeRuns.savePatrol(run)
            val best = container.profile.load().patrolBest[run.difficulty] ?: 0
            phase = PatrolPhase.Sea(++counter, run, setup, restored = null, bestBefore = best)
        }
    }

    /** Continues the saved run: the sea in progress is restored from its save when it can be, else played again. */
    fun resume(run: PatrolRun) {
        val cfg = config ?: return
        scope.launch {
            val restored = restoreSaved(container, MatchKind.PATROL, run.matchId)
            val setup = restored?.first ?: PatrolSea.setup(run, cfg)
            if (setup == null) {
                phase = PatrolPhase.Setup(saved = run, best = container.profile.load().patrolBest, notice = R.string.patrol_setup_failed)
                return@launch
            }
            if (restored == null) container.matches.clear(MatchKind.PATROL)
            val best = container.profile.load().patrolBest[run.difficulty] ?: 0
            phase = PatrolPhase.Sea(++counter, run, setup, restored?.second, bestBefore = best)
        }
    }

    /** The sea is over: a cleared sea goes on with the refunded reserve, a lost one ends the run. */
    fun finishSea(current: PatrolPhase.Sea, won: Boolean, shots: Int, shipsSunk: Int) {
        scope.launch {
            val next = current.run.after(if (won) SeaEnd.Cleared(shots, shipsSunk) else SeaEnd.Lost)
            if (next == null) {
                container.modeRuns.clearPatrol()
                val cleared = current.run.seasCleared + if (won) 1 else 0
                val best = container.profile.load().patrolBest[current.run.difficulty] ?: 0
                phase = PatrolPhase.Over(
                    cleared = cleared,
                    best = best,
                    newRecord = cleared > current.bestBefore,
                )
            } else {
                begin(next)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = phase) {
            PatrolPhase.Loading -> Unit
            PatrolPhase.Broken -> ModeScaffold(title = stringResource(R.string.patrol_title), onBack = onExit) {
                Text(stringResource(R.string.patrol_config_error), style = LocalFleetType.current.bodyText, color = LocalFleetColors.current.text)
            }
            is PatrolPhase.Setup -> PatrolSetupScreen(
                difficulty = difficulty,
                onDifficulty = { difficulty = it },
                best = current.best,
                saved = current.saved,
                notice = current.notice,
                onContinue = { resume(it) },
                onStart = { begin(PatrolRun.start(difficulty, newRunSeed())) },
                onBack = onExit,
            )
            is PatrolPhase.Sea -> PatrolSeaScreen(
                key = current.key,
                container = container,
                context = context,
                themeId = themeId,
                settings = settings,
                run = current.run,
                setup = current.setup,
                restored = current.restored,
                onFinish = { won, shots, shipsSunk -> finishSea(current, won, shots, shipsSunk) },
                onRestart = { begin(current.run) },
                onLeave = onExit,
            )
            is PatrolPhase.Over -> PatrolOverScreen(
                cleared = current.cleared,
                best = current.best,
                newRecord = current.newRecord,
                onAgain = {
                    scope.launch { phase = PatrolPhase.Setup(saved = null, best = container.profile.load().patrolBest) }
                },
                onBack = onExit,
            )
        }
    }
}

/** The phases of the patrol flow. The battle and its setup are kept only while they are on screen. */
private sealed interface PatrolPhase {
    data object Loading : PatrolPhase
    data object Broken : PatrolPhase

    /** The setup: the run saved on disk (if any), the record per difficulty and an optional notice. */
    data class Setup(
        val saved: PatrolRun?,
        val best: Map<Difficulty, Int>,
        val notice: Int? = null,
    ) : PatrolPhase

    data class Sea(
        val key: Int,
        val run: PatrolRun,
        val setup: MatchSetup,
        val restored: BattleMatch?,
        /** Seas cleared on the record before this sea, to tell a new record at the end of the run. */
        val bestBefore: Int,
    ) : PatrolPhase

    data class Over(val cleared: Int, val best: Int, val newRecord: Boolean) : PatrolPhase
}

@Composable
private fun PatrolSetupScreen(
    difficulty: Difficulty,
    onDifficulty: (Difficulty) -> Unit,
    best: Map<Difficulty, Int>,
    saved: PatrolRun?,
    notice: Int?,
    onContinue: (PatrolRun) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    ModeScaffold(title = stringResource(R.string.patrol_title), onBack = onBack) {
        Text(stringResource(R.string.patrol_intro), style = type.bodyText, color = colors.text)
        Section(R.string.mode_difficulty) {
            ChoiceRow(Difficulty.entries, difficulty, label = { difficultyLabel(it) }, onPick = onDifficulty)
        }
        Text(
            pluralStringResource(R.plurals.patrol_start_reserve, PatrolRules.startingReserve(difficulty), PatrolRules.startingReserve(difficulty)),
            style = type.bodyText,
            color = colors.text,
        )
        Text(
            pluralStringResource(R.plurals.patrol_record, best[difficulty] ?: 0, best[difficulty] ?: 0),
            style = type.caption,
            color = colors.textMuted,
        )
        notice?.let { Text(stringResource(it), style = type.caption, color = colors.accent) }
        if (saved != null) {
            ModeButton(pluralStringResource(R.plurals.patrol_continue, saved.reserve, saved.sea, saved.reserve)) { onContinue(saved) }
            ModeButton(stringResource(R.string.patrol_restart), primary = false) { onStart() }
        } else {
            ModeButton(stringResource(R.string.patrol_start)) { onStart() }
        }
    }
}

@Composable
private fun PatrolSeaScreen(
    key: Int,
    container: AppContainer,
    context: Context,
    themeId: ThemeId,
    settings: Settings,
    run: PatrolRun,
    setup: MatchSetup,
    restored: BattleMatch?,
    onFinish: (won: Boolean, shots: Int, shipsSunk: Int) -> Unit,
    onRestart: () -> Unit,
    onLeave: () -> Unit,
) {
    val stake = ModeStake.Patrol(
        difficulty = run.difficulty,
        runSeed = run.runSeed,
        sea = run.sea,
        seasClearedBefore = run.seasCleared,
        reserve = run.reserve,
    )
    val battleVm: BattleViewModel = viewModel(
        key = "patrol-$key",
        factory = factory { BattleViewModel(container, context, BattleSource.FromSetup(setup, context.getString(R.string.patrol_title), restored, stake = stake)) },
    )
    val mines = setup.rules.mines.getOrNull(PatrolSea.ENEMY)?.size ?: 0
    BattleScreen(
        viewModel = battleVm,
        settings = settings,
        themeId = themeId,
        onLeave = onLeave,
        onRestart = onRestart,
        onPlayAgain = {
            val ui = battleVm.ui.value
            val summary = ui.summary
            if (summary != null) onFinish(summary.won, ui.match.playerShots, summary.enemyShipsSunk)
        },
        frame = BattleFrame(
            opponentName = context.getString(R.string.patrol_title),
            title = stringResource(R.string.patrol_sea_title, run.sea),
            subtitle = pluralStringResource(R.plurals.patrol_sea_subtitle, mines, setup.rules.width, mines),
            reserveShown = true,
        ),
    )
}

@Composable
private fun PatrolOverScreen(
    cleared: Int,
    best: Int,
    newRecord: Boolean,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    ModeScaffold(title = stringResource(R.string.patrol_over_title), onBack = onBack) {
        Text(pluralStringResource(R.plurals.patrol_over_text, cleared, cleared, best), style = type.bodyText, color = colors.text)
        if (newRecord) Text(stringResource(R.string.patrol_over_new_record), style = type.bodyText, color = colors.accent)
        ModeButton(stringResource(R.string.patrol_over_again)) { onAgain() }
        ModeButton(stringResource(R.string.mode_back), primary = false) { onBack() }
    }
}

/** The save of [kind] when it is the match [matchId] and it can be restored; a save that cannot is dropped. */
internal suspend fun restoreSaved(container: AppContainer, kind: MatchKind, matchId: String): Pair<MatchSetup, BattleMatch>? {
    val saved = container.matches.activeMatch(kind, container.profile.load()) ?: return null
    if (saved.matchId != matchId) return null
    val restored = SetupSave.decodeSetup(saved.setup.orEmpty())?.let { setup ->
        (SetupSave.restore(saved) as? RestoreResult.Restored)?.let { setup to it.match }
    }
    if (restored == null) container.matches.clear(kind)
    return restored
}

/** A fresh seed for a new run, from the platform random source. Each run starts from its own seed. */
internal fun newRunSeed(): Long = kotlin.random.Random.nextLong()

private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}
