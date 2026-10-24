package pt.tiago.nightfleet.ui.screens.daily

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.content.DailyHuntDto
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleSource
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.PlacementViewModel
import pt.tiago.nightfleet.game.battle.RestoreResult
import pt.tiago.nightfleet.game.battle.SetupSave
import pt.tiago.nightfleet.game.campaign.CampaignSetup
import pt.tiago.nightfleet.game.daily.DailyChallenge
import pt.tiago.nightfleet.game.daily.DailyStake
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.game.campaign.CampaignSetup.Plan
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.screens.battle.BattleScreen
import pt.tiago.nightfleet.ui.screens.freeplay.admiralNameRes
import pt.tiago.nightfleet.ui.screens.placement.PlacementActions
import pt.tiago.nightfleet.ui.screens.placement.PlacementScreen
import pt.tiago.nightfleet.ui.theme.ThemeId
import java.time.LocalDate

/**
 * Plays today's daily hunt: the hunt of [date] in [difficulty] from `daily/hunts.json`, placement and
 * then the battle in HUNT mode with the calibrated budget. The result becomes a daily result, never campaign progress.
 * A saved play of the same day resumes; a new play gets its own id, so every play is committed once.
 */
@Composable
fun DailyHuntFlow(
    container: AppContainer,
    themeId: ThemeId,
    date: LocalDate,
    difficulty: Difficulty,
    onExit: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    var phase by remember(date, difficulty) { mutableStateOf<DailyPhase>(DailyPhase.Loading) }
    var counter by remember(date, difficulty) { mutableIntStateOf(0) }

    fun show(next: DailyPhase) {
        phase = if (next is DailyPhase.Battle) next.copy(key = ++counter) else next
    }

    fun startPlay() {
        scope.launch {
            phase = DailyPhase.Loading
            show(planPlay(container, date, difficulty))
        }
    }

    LaunchedEffect(date, difficulty) {
        val resumed = resumeSaved(container, date, difficulty)
        if (resumed != null) show(resumed) else startPlay()
    }

    when (val current = phase) {
        DailyPhase.Loading -> Text(stringResource(R.string.campaign_loading), Modifier.padding(16.dp))
        is DailyPhase.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.daily_unavailable))
            TextButton(onClick = onExit) { Text(stringResource(R.string.campaign_back)) }
        }
        is DailyPhase.Placement -> {
            val placementVm: PlacementViewModel = viewModel(
                key = "daily-placement-${current.key}",
                factory = factory { PlacementViewModel(container, current.plan.variantRules) },
            )
            val placement by placementVm.state.collectAsState()
            PlacementScreen(
                state = placement,
                themeId = themeId,
                actions = PlacementActions(
                    onPickShip = placementVm::pickShip,
                    onTapSea = placementVm::tapSea,
                    onLongPressSea = placementVm::longPressSea,
                    onAuto = placementVm::auto,
                    onClear = placementVm::clear,
                    onSaveFormation = placementVm::saveFormation,
                    onLoadFormation = placementVm::loadFormation,
                    onDeleteFormation = placementVm::deleteFormation,
                    onReady = {
                        placementVm.readyPlacements()?.let { placements ->
                            val setup = CampaignSetup.finish(current.plan, placements, FreePlayAdmirals.DEFAULT_ID)
                            if (setup != null && setup.create() is SetupResult.Created) {
                                scope.launch {
                                    runCatching { container.matches.clear(MatchKind.DAILY) }
                                    show(DailyPhase.Battle(0, setup, current.stake))
                                }
                            }
                        }
                    },
                    onBack = onExit,
                ),
            )
        }
        is DailyPhase.Battle -> {
            val admiral = context.getString(admiralNameRes(FreePlayAdmirals.DEFAULT_ID))
            val battleVm: BattleViewModel = viewModel(
                key = "daily-battle-${current.key}",
                factory = factory {
                    BattleViewModel(
                        container,
                        context,
                        BattleSource.FromSetup(current.setup, admiral, restored = current.restored, daily = current.stake),
                    )
                },
            )
            BattleScreen(
                viewModel = battleVm,
                settings = settings,
                themeId = themeId,
                onLeave = onExit,
                onRestart = { startPlay() },
                onPlayAgain = { startPlay() },
                frame = BattleFrame(
                    opponentName = admiral,
                    title = stringResource(R.string.daily_hunt_title),
                    subtitle = stringResource(difficultyLabel(difficulty)) + " · " + date.toString(),
                ),
            )
        }
    }
}

/**
 * Plans a new play of the day's hunt. The board of a day is fixed (attempt 0); the play id is new, so the result of
 * every play is committed on its own.
 */
private suspend fun planPlay(container: AppContainer, date: LocalDate, difficulty: Difficulty): DailyPhase {
    val hunt = huntOf(container, date, difficulty) ?: return DailyPhase.Failed
    val variant = hunt.asVariant()
    val terrains = CampaignSetup.terrainMap(listOf(TerrainDto(hunt.rules.terrain.orEmpty(), hunt.terrain)))
    val pool = LayoutPoolDto(
        id = hunt.id,
        fleet = hunt.rules.fleet,
        terrain = hunt.rules.terrain,
        terrainHash = null,
        layouts = hunt.layouts,
    )
    val plan = CampaignSetup.planVariant(MissionKind.HUNT, variant, difficulty, 0L, terrains, pool)
        ?: return DailyPhase.Failed
    val dayKey = DailyChallenge.matchId(date, difficulty, ChallengeKind.HUNT)
    val playId = "$dayKey#${java.lang.Long.toHexString(System.nanoTime() xor System.currentTimeMillis())}"
    return DailyPhase.Placement(
        key = 0,
        plan = plan,
        stake = DailyStake(date, difficulty, dayKey, playId, variant.stars),
    )
}

/** Resumes the saved play of this day's hunt, or returns null when there is none. A save that does not replay is dropped. */
private suspend fun resumeSaved(container: AppContainer, date: LocalDate, difficulty: Difficulty): DailyPhase? {
    val profile = container.profile.load()
    val saved = container.matches.activeMatch(MatchKind.DAILY, profile) ?: return null
    val dayKey = DailyChallenge.matchId(date, difficulty, ChallengeKind.HUNT)
    if (saved.missionId != dayKey || saved.difficulty != difficulty) return null
    val setup: MatchSetup? = saved.setup?.let(SetupSave::decodeSetup)
    val replayed = SetupSave.restore(saved) as? RestoreResult.Restored
    if (setup == null || replayed == null) {
        runCatching { container.matches.clear(MatchKind.DAILY) }
        return null
    }
    val variant = huntOf(container, date, difficulty)?.asVariant() ?: return null
    return DailyPhase.Battle(
        key = 0,
        setup = setup,
        stake = DailyStake(date, difficulty, dayKey, saved.matchId, variant.stars),
        restored = replayed.match,
    )
}

/** The hunt of [date] and [difficulty]: the pool entry of the day, looked up in the hunts file. */
private fun huntOf(container: AppContainer, date: LocalDate, difficulty: Difficulty): DailyHuntDto? {
    val pool = (container.catalog.dailyPool() as? CatalogResult.Ok)?.value ?: return null
    val hunts = (container.catalog.dailyHunts() as? CatalogResult.Ok)?.value ?: return null
    val entry = DailyChallenge.pick(pool, date, DailyKind.HUNT, difficulty) ?: return null
    return DailyChallenge.hunt(hunts, entry)
}

internal fun difficultyLabel(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

/** The steps of the daily hunt. */
internal sealed class DailyPhase {
    data object Loading : DailyPhase()
    data object Failed : DailyPhase()
    data class Placement(val key: Int, val plan: Plan, val stake: DailyStake) : DailyPhase()
    data class Battle(
        val key: Int,
        val setup: MatchSetup,
        val stake: DailyStake,
        val restored: BattleMatch? = null,
    ) : DailyPhase()
}

/** A factory for a view model with constructor arguments. */
private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}
