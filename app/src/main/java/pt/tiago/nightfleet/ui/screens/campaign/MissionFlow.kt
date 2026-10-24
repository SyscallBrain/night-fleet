package pt.tiago.nightfleet.ui.screens.campaign

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.data.BossSeries
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleSource
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.PlacementViewModel
import pt.tiago.nightfleet.game.battle.RestoreResult
import pt.tiago.nightfleet.game.battle.SetupSave
import pt.tiago.nightfleet.game.campaign.BossProgress
import pt.tiago.nightfleet.game.campaign.CampaignProgress
import pt.tiago.nightfleet.game.campaign.CampaignRules
import pt.tiago.nightfleet.game.campaign.CampaignSetup
import pt.tiago.nightfleet.game.campaign.CampaignStake
import pt.tiago.nightfleet.game.puzzle.PuzzleRequest
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.board.WeatherKind
import pt.tiago.nightfleet.ui.board.seaWeatherOf
import pt.tiago.nightfleet.ui.screens.battle.BattleScreen
import pt.tiago.nightfleet.ui.screens.freeplay.admiralNameRes
import pt.tiago.nightfleet.ui.screens.placement.PlacementActions
import pt.tiago.nightfleet.ui.screens.placement.PlacementScreen
import pt.tiago.nightfleet.ui.screens.puzzle.PuzzleRoute
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.tutorial.LocalTutorialAnchors
import pt.tiago.nightfleet.ui.tutorial.TutorialActions
import pt.tiago.nightfleet.ui.tutorial.TutorialAnchorRegistry
import pt.tiago.nightfleet.ui.tutorial.TutorialController
import pt.tiago.nightfleet.ui.tutorial.TutorialOverlay
import pt.tiago.nightfleet.ui.tutorial.TutorialScripts

/**
 * Plays one campaign mission: the placement of the fleet, then the battle, or the puzzle of
 * a PUZZLE mission. A boss is a best-of-three series: after each duel a sheet shows the score and who opens the next one.
 *
 * Opening a mission first looks for the saved battle or puzzle of the campaign: a battle is replayed
 * from its setup and checked against its hash; when that fails, the save is discarded and the sheet says so.
 * The result is committed by the battle view model (see BattleViewModel and game/campaign).
 */
@Composable
fun MissionFlow(
    container: AppContainer,
    themeId: ThemeId,
    mission: Int,
    difficulty: Difficulty,
    onExit: () -> Unit,
    /** Puzzle missions: "Next mission" from the solved sheet. Defaults to leaving the mission. */
    onNextMission: (Int) -> Unit = { onExit() },
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    val seaWeather by produceState(WeatherKind.CALM, mission) {
        value = withContext(Dispatchers.IO) { seaWeatherOf(container.catalog, CampaignRules.seaOf(mission)) }
    }
    var phase by remember(mission, difficulty) { mutableStateOf<MissionPhase>(MissionPhase.Loading) }
    var counter by remember(mission, difficulty) { mutableIntStateOf(0) }
    /** The guided tutorial of missions 1 to 10 (sea 1). Null elsewhere. */
    val script = if (mission in 1..10) TutorialScripts.forMission(mission) else null
    val anchors = remember(mission, difficulty) { TutorialAnchorRegistry() }
    val tutorial = remember(mission, difficulty) {
        TutorialController(onCompleted = { id ->
            scope.launch { runCatching { container.profile.update { it.copy(completedTutorials = it.completedTutorials + id) } } }
        })
    }
    LaunchedEffect(mission, difficulty) {
        val guided = script ?: return@LaunchedEffect
        val saved = runCatching { container.profile.load() }.getOrNull() ?: return@LaunchedEffect
        if (guided.id !in saved.completedTutorials) tutorial.start(guided)
    }

    fun show(next: MissionPhase) {
        phase = if (next is MissionPhase.Battle) next.copy(key = ++counter) else next
    }

    /** Starts a new attempt (or the next duel): counts it in the profile and plans the battle. */
    fun startAttempt() {
        scope.launch {
            phase = MissionPhase.Loading
            show(
                when (val loaded = container.catalog.mission(missionId(mission))) {
                    is CatalogResult.Failed -> MissionPhase.Failed(loaded.reason)
                    is CatalogResult.Ok -> planAttempt(container, loaded.value, mission, difficulty)
                },
            )
        }
    }

    /** Opens the mission: the saved play of it if there is one, otherwise a new attempt. */
    LaunchedEffect(mission, difficulty) {
        phase = MissionPhase.Loading
        when (val loaded = container.catalog.mission(missionId(mission))) {
            is CatalogResult.Failed -> phase = MissionPhase.Failed(loaded.reason)
            is CatalogResult.Ok -> {
                val resumed = resumeSaved(container, loaded.value, mission, difficulty)
                if (resumed != null) show(resumed) else startAttempt()
            }
        }
    }

    CompositionLocalProvider(LocalTutorialAnchors provides anchors) {
    Box(Modifier.fillMaxSize()) {
    when (val current = phase) {
        MissionPhase.Loading -> Text(stringResource(R.string.campaign_loading), Modifier.padding(16.dp))
        is MissionPhase.Failed -> ErrorText(current.reason, onExit)
        is MissionPhase.Discarded -> DiscardedSheet(
            onContinue = { startAttempt() },
            onLeave = onExit,
        )
        is MissionPhase.Puzzle -> PuzzleRoute(
            request = current.request,
            onBack = onExit,
            onNext = { },
            onNextMission = if (mission < CampaignRules.TOTAL_MISSIONS) {
                { onNextMission(mission + 1) }
            } else {
                null
            },
            showNext = true,
        )
        is MissionPhase.Placement -> {
            val placementVm: PlacementViewModel = viewModel(
                key = "campaign-placement-${current.key}",
                factory = factory { PlacementViewModel(container, current.plan.variantRules) },
            )
            val placement by placementVm.state.collectAsState()
            var placementSnapshot by remember { mutableStateOf<TutorialActions.PlacementSnapshot?>(null) }
            LaunchedEffect(placement) {
                val now = TutorialActions.PlacementSnapshot(placement.placed, placement.formations.size)
                TutorialActions.placement(placementSnapshot, now).forEach { tutorial.onAction(it) }
                placementSnapshot = now
            }
            PlacementScreen(
                state = placement,
                themeId = themeId,
                actions = PlacementActions(
                    onPickShip = placementVm::pickShip,
                    onTapSea = placementVm::tapSea,
                    onLongPressSea = placementVm::longPressSea,
                    onAuto = {
                        placementVm.auto()
                        tutorial.onAction(TutorialActions.AUTO_PRESSED)
                    },
                    onClear = placementVm::clear,
                    onSaveFormation = placementVm::saveFormation,
                    onLoadFormation = placementVm::loadFormation,
                    onDeleteFormation = placementVm::deleteFormation,
                    onReady = {
                        placementVm.readyPlacements()?.let { placements ->
                            val setup = CampaignSetup.finish(current.plan, placements, current.stake.admiralId.orEmpty())
                            if (setup != null && setup.create() is SetupResult.Created) {
                                tutorial.onAction(TutorialActions.READY_PRESSED)
                                scope.launch {
                                    // The new attempt replaces the saved campaign battle: the slot holds one battle.
                                    runCatching { container.matches.clear(MatchKind.CAMPAIGN) }
                                    show(MissionPhase.Battle(0, setup, current.stake))
                                }
                            }
                        }
                    },
                    onBack = onExit,
                ),
            )
        }
        is MissionPhase.Battle -> {
            val admiral = admiralName(context, current.stake.admiralId)
            var battleSnapshot by remember { mutableStateOf<TutorialActions.BattleSnapshot?>(null) }
            val battleVm: BattleViewModel = viewModel(
                key = "campaign-battle-${current.key}",
                factory = factory {
                    BattleViewModel(
                        container,
                        context,
                        BattleSource.FromSetup(current.setup, admiral, restored = current.restored, campaign = current.stake),
                    )
                },
            )
            val battleUi by battleVm.ui.collectAsState()
            LaunchedEffect(battleUi) {
                val now = TutorialActions.snapshotOf(battleUi)
                TutorialActions.battle(battleSnapshot, now).forEach { tutorial.onAction(it) }
                battleSnapshot = now
            }
            BattleScreen(
                viewModel = battleVm,
                settings = settings,
                themeId = themeId,
                onLeave = onExit,
                onRestart = { startAttempt() },
                onPlayAgain = {
                    scope.launch {
                        val series = if (current.stake.isBossDuel) {
                            BossProgress.running(container.profile.load(), current.stake.missionId, current.stake.difficulty)
                        } else {
                            null
                        }
                        when {
                            // A duel of a series that is not decided yet: the sheet asks for the next duel.
                            series != null -> phase = MissionPhase.SeriesBreak(series.wins, series.losses, current.stake)
                            current.stake.isBossDuel -> phase = MissionPhase.Done
                            else -> startAttempt()
                        }
                    }
                },
                frame = BattleFrame(
                    opponentName = admiral,
                    title = stringResource(R.string.campaign_battle_title, mission),
                    subtitle = stringResource(
                        R.string.campaign_battle_subtitle,
                        stringResource(Seas.nameRes(CampaignRules.seaOf(mission))),
                        stringResource(difficultyRes(difficulty)),
                    ),
                    weather = seaWeather,
                ),
            )
        }
        is MissionPhase.SeriesBreak -> SeriesBreakSheet(
            wins = current.wins,
            losses = current.losses,
            onNextDuel = { startAttempt() },
            onLeave = onExit,
        )
        MissionPhase.Done -> LaunchedEffect(Unit) { onExit() }
    }
    if (script != null) {
        TutorialOverlay(
            state = tutorial.state,
            registry = anchors,
            onAdvance = tutorial::onTap,
            onSkip = tutorial::skip,
        )
    }
    }
    }
}

/** The series sheet between two duels of a boss: the score, and that the next duel opens for whoever is due. */
@Composable
private fun SeriesBreakSheet(wins: Int, losses: Int, onNextDuel: () -> Unit, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.campaign_boss_score, wins, losses))
        Text(stringResource(R.string.campaign_boss_next_rule))
        Button(onClick = onNextDuel) { Text(stringResource(R.string.campaign_boss_next)) }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.campaign_back)) }
    }
}

/** Shown once when a saved battle cannot be resumed: the save is gone, and the mission starts again. */
@Composable
private fun DiscardedSheet(onContinue: () -> Unit, onLeave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.campaign_resume_discarded))
        Button(onClick = onContinue) { Text(stringResource(R.string.campaign_resume_new)) }
        TextButton(onClick = onLeave) { Text(stringResource(R.string.campaign_back)) }
    }
}

@Composable
private fun ErrorText(reason: String, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(reason)
        TextButton(onClick = onExit) { Text(stringResource(R.string.campaign_back)) }
    }
}

private fun missionId(mission: Int): String = Seas.missionId(CampaignRules.seaOf(mission), mission)

/**
 * The saved campaign play of [dto], when the campaign slot holds one for this mission and difficulty. A puzzle resumes
 * its grid; a battle is replayed from its setup. A battle that does not replay, or whose boss series is not the one of
 * the profile any more, is discarded (the slot is cleared) and the sheet says so. Null when there is nothing to resume.
 */
private suspend fun resumeSaved(container: AppContainer, dto: MissionDto, mission: Int, difficulty: Difficulty): MissionPhase? {
    val profile = container.profile.load()
    val saved = container.matches.activeMatch(MatchKind.CAMPAIGN, profile) ?: return null
    if (saved.missionId != dto.id || saved.difficulty != difficulty) return null
    if (saved.setup == null) return MissionPhase.Puzzle(PuzzleRequest.Campaign(dto.id, difficulty))
    val setup = SetupSave.decodeSetup(saved.setup)
    val restored = SetupSave.restore(saved)
    val series = BossProgress.running(profile, dto.id, difficulty)
    val replayed = restored as? RestoreResult.Restored
    if (setup == null || replayed == null || saved.bossSeries != series) {
        Log.w(TAG, "Discarded the saved campaign battle: ${(restored as? RestoreResult.Discarded)?.reason ?: "SERIES"}")
        runCatching { container.matches.clear(MatchKind.CAMPAIGN) }
        return MissionPhase.Discarded
    }
    val stake = stakeOf(container, dto, difficulty, series) ?: return MissionPhase.Discarded
    return MissionPhase.Battle(0, setup, stake, restored = replayed.match)
}

/**
 * Counts the attempt and builds the plan of the variant. A boss duel starts with the player when the series has no duels
 * yet, and alternates from duel to duel. A PUZZLE mission goes straight to its puzzle.
 */
private suspend fun planAttempt(container: AppContainer, dto: MissionDto, mission: Int, difficulty: Difficulty): MissionPhase {
    val variant = dto.variants[CampaignSetup.difficultyOf(difficulty)] ?: return MissionPhase.Failed("variant missing")
    if (dto.type == MissionKind.PUZZLE || variant.puzzleId != null) {
        return MissionPhase.Puzzle(PuzzleRequest.Campaign(dto.id, difficulty))
    }
    val terrains = (container.catalog.terrains() as? CatalogResult.Ok)?.value?.terrains.orEmpty()
    val pool = variant.layoutPool?.let { id -> (container.catalog.layoutPool(id) as? CatalogResult.Ok)?.value }
    val before = container.profile.load()
    val attempt = before.variants[variantKey(dto.id, difficulty)]?.attemptIndex ?: 0L
    container.profile.update { CampaignProgress.startAttempt(it, dto.id, difficulty).first }
    val plan = CampaignSetup.plan(dto, difficulty, attempt, CampaignSetup.terrainMap(terrains), pool)
        ?: return MissionPhase.Failed("the mission cannot be built from the catalog")
    val series = BossProgress.running(before, dto.id, difficulty)
    val starter = if (dto.type == MissionKind.BOSS) (series?.let { it.wins + it.losses } ?: 0) % 2 else null
    val stake = stakeOf(container, dto, difficulty, series) ?: return MissionPhase.Failed("the sea has no admiral")
    return MissionPhase.Placement(
        key = attempt.toInt(),
        plan = if (starter != null) plan.copy(firstPlayer = starter) else plan,
        stake = stake,
    )
}

/** What the battle of [dto] is for: the thresholds of the variant and the admiral of its sea. */
private suspend fun stakeOf(container: AppContainer, dto: MissionDto, difficulty: Difficulty, series: BossSeries?): CampaignStake? {
    val variant = dto.variants[CampaignSetup.difficultyOf(difficulty)] ?: return null
    val sea = (container.catalog.sea(dto.sea) as? CatalogResult.Ok)?.value ?: return null
    return CampaignStake(
        missionId = dto.id,
        difficulty = difficulty,
        kind = dto.type,
        thresholds = variant.stars,
        admiralId = sea.admiralId,
        series = series,
    )
}

private fun admiralName(context: Context, admiralId: String?): String =
    context.getString(admiralNameRes(admiralId.orEmpty()))

internal fun difficultyRes(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

/** The steps of a mission, from loading to the battle. */
internal sealed class MissionPhase {
    data object Loading : MissionPhase()
    data class Failed(val reason: String) : MissionPhase()
    /** A saved battle could not be resumed and was discarded. */
    data object Discarded : MissionPhase()
    data class Puzzle(val request: PuzzleRequest) : MissionPhase()
    data class Placement(val key: Int, val plan: CampaignSetup.Plan, val stake: CampaignStake) : MissionPhase()
    data class Battle(
        val key: Int,
        val setup: MatchSetup,
        val stake: CampaignStake,
        /** The replayed match of a resumed battle; null for a new one. */
        val restored: BattleMatch? = null,
    ) : MissionPhase()
    data class SeriesBreak(val wins: Int, val losses: Int, val stake: CampaignStake) : MissionPhase()
    data object Done : MissionPhase()
}

/** A factory for a view model with constructor arguments. */
private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}

private const val TAG = "CampaignMission"
