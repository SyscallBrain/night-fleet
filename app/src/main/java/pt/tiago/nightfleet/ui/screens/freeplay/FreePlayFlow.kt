package pt.tiago.nightfleet.ui.screens.freeplay

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
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleSource
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.PlacementViewModel
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.game.freeplay.FreePlayPreset
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.FreePlayState
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.screens.battle.BattleScreen
import pt.tiago.nightfleet.ui.screens.placement.PlacementActions
import pt.tiago.nightfleet.ui.screens.placement.PlacementScreen
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.ThemeId

/**
 * Free play from Home: the setup screen, then the placement of the fleet, then the battle.
 * A new game takes the next seed of the free play counter and replaces the saved free play battle.
 */
@Composable
fun FreePlayFlow(
    container: AppContainer,
    themeId: ThemeId,
    onExit: () -> Unit,
    /** "Play against" from the gallery: the setup opens with this admiral selected. */
    admiralId: String? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    var state by remember { mutableStateOf<FreePlayState?>(null) }
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
    var counter by remember { mutableIntStateOf(0) }

    LaunchedEffect(admiralId) {
        val loaded = container.freePlay.load()
        val next = FreePlayPreset.withAdmiral(loaded, admiralId)
        if (next != loaded) container.freePlay.save(next)
        state = next
        phase = Phase.Setup(++counter)
    }

    /** Applies a change of the setup and remembers it. */
    fun change(transform: (FreePlayConfig) -> FreePlayConfig) {
        val current = state ?: return
        val next = current.copy(config = transform(current.config))
        state = next
        scope.launch { container.freePlay.save(next) }
    }

    when (val current = phase) {
        Phase.Loading -> Text("", modifier = Modifier.fillMaxSize())
        is Phase.Setup -> {
            val config = state?.config ?: FreePlayConfig()
            FreePlayScreen(
                config = config,
                onChange = { transform -> change(transform) },
                onStart = {
                    val before = state
                    if (before != null) {
                        val seed = before.nextAttemptSeed
                        val next = before.copy(attempts = before.attempts + 1)
                        state = next
                        scope.launch { container.freePlay.save(next) }
                        val terrain = FreePlayBuilder.terrainFor(before.config, seed)
                        phase = Phase.Placement(++counter, before.config, terrain, seed)
                    }
                },
                onBack = onExit,
            )
        }
        is Phase.Placement -> {
            val placementVm: PlacementViewModel = viewModel(
                key = "freeplay-placement-${current.key}",
                factory = factory { PlacementViewModel(container, FreePlayBuilder.baseRules(current.config, current.terrain)) },
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
                            val setup = FreePlayBuilder.finish(current.config, current.terrain, current.seed, placements)
                            if (setup != null) {
                                scope.launch {
                                    // The new game replaces the saved one: the slot holds the battle in play.
                                    runCatching { container.matches.clear(MatchKind.FREE_PLAY) }
                                    phase = Phase.Battle(++counter, setup, current.config)
                                }
                            }
                        }
                    },
                    onBack = { phase = Phase.Setup(++counter) },
                ),
            )
        }
        is Phase.Battle -> {
            val battleVm: BattleViewModel = viewModel(
                key = "freeplay-battle-${current.key}",
                factory = factory { BattleViewModel(container, context, BattleSource.FromSetup(current.setup, opponentName(context, current.setup))) },
            )
            BattleScreen(
                viewModel = battleVm,
                settings = settings,
                themeId = themeId,
                onLeave = onExit,
                onRestart = { phase = Phase.Setup(++counter) },
                onPlayAgain = { phase = Phase.Setup(++counter) },
                frame = BattleFrame(
                    opponentName = opponentName(context, current.setup),
                    title = stringResource(R.string.freeplay_title),
                    subtitle = stringResource(R.string.freeplay_subtitle, opponentName(context, current.setup), levelName(current.config.level)),
                ),
            )
        }
    }
}

/** The name of the admiral of [setup]. */
private fun opponentName(context: Context, setup: MatchSetup): String =
    context.getString(admiralNameRes(setup.opponentId ?: FreePlayAdmirals.DEFAULT_ID))

@Composable
private fun levelName(level: OpponentLevel): String = stringResource(
    when (level) {
        OpponentLevel.EASY -> R.string.freeplay_level_easy
        OpponentLevel.MEDIUM -> R.string.freeplay_level_medium
        OpponentLevel.HARD -> R.string.freeplay_level_hard
        OpponentLevel.RELENTLESS -> R.string.freeplay_level_relentless
    },
)

private sealed class Phase {
    data object Loading : Phase()
    data class Setup(val key: Int) : Phase()
    data class Placement(val key: Int, val config: FreePlayConfig, val terrain: List<Terrain>, val seed: Long) : Phase()
    data class Battle(val key: Int, val setup: MatchSetup, val config: FreePlayConfig) : Phase()
}

/** A factory for a view model with constructor arguments. */
private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}
