package pt.tiago.nightfleet.ui.screens.duo

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleRender
import pt.tiago.nightfleet.game.battle.BattleUi
import pt.tiago.nightfleet.game.battle.PlacementViewModel
import pt.tiago.nightfleet.game.duo.CurtainReason
import pt.tiago.nightfleet.game.duo.DuoFlow
import pt.tiago.nightfleet.game.duo.DuoGame
import pt.tiago.nightfleet.game.duo.DuoPhase
import pt.tiago.nightfleet.game.duo.DuoViewModel
import pt.tiago.nightfleet.game.freeplay.FleetChoice
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.screens.battle.BattleActions
import pt.tiago.nightfleet.ui.screens.battle.BattleContent
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.screens.freeplay.ChoiceRow
import pt.tiago.nightfleet.ui.screens.freeplay.MineStepper
import pt.tiago.nightfleet.ui.screens.freeplay.Section
import pt.tiago.nightfleet.ui.screens.freeplay.ToggleRow
import pt.tiago.nightfleet.ui.screens.freeplay.WeaponChips
import pt.tiago.nightfleet.ui.screens.placement.PlacementActions
import pt.tiago.nightfleet.ui.screens.placement.PlacementScreen
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId

/**
 * The two-player duel. Setup, then one curtain before every seat's part: the placement of each
 * fleet, and each turn. A saved duel comes back behind the curtain of the seat that acts next.
 */
@Composable
fun DuoScreen(
    container: AppContainer,
    themeId: ThemeId,
    onExit: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val vm: DuoViewModel = viewModel(factory = factory { DuoViewModel(container) })
    val game by vm.game.collectAsState()
    val aim by vm.targeting.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var lastConfig by remember { mutableStateOf(FreePlayConfig()) }
    LaunchedEffect(Unit) {
        vm.restoreSaved()
        lastConfig = vm.lastConfig()
        loaded = true
    }
    BackHandler { onExit() }
    if (!loaded) return

    val current = game
    if (current == null) {
        DuoSetup(
            initialConfig = lastConfig,
            onStart = { names, config -> scope.launch { vm.startDuel(names, config) } },
            onBack = onExit,
        )
        return
    }
    when (val phase = current.phase) {
        is DuoPhase.Curtain -> DuoCurtain(
            name = current.names.getOrElse(phase.seat) { "" },
            reason = phase.reason,
            onShow = vm::reveal,
        )
        is DuoPhase.Placing -> DuoPlacement(
            container = container,
            game = current,
            seat = phase.seat,
            themeId = themeId,
            onPlaced = { placements -> vm.placed(phase.seat, placements) },
        )
        is DuoPhase.Turn, is DuoPhase.Result -> {
            val seat = when (phase) {
                is DuoPhase.Turn -> phase.seat
                is DuoPhase.Result -> phase.seat
                else -> 0
            }
            val match = current.match
            if (match != null) {
                val base = BattleUi(match = match, enemy = blank(match), own = blank(match), viewer = seat)
                val ui = vm.battleUi(current, base, aim) ?: return
                BattleContent(
                    ui = ui,
                    settings = settings,
                    themeId = themeId,
                    frame = BattleFrame(
                        opponentName = current.names.getOrElse(1 - seat) { "" },
                        title = stringResource(R.string.duo_title),
                        subtitle = scoreText(current),
                    ),
                    actions = BattleActions(
                        onCellTap = vm::tap,
                        onOwnTap = vm::ownTap,
                        onPickWeapon = vm::pickWeapon,
                        onPickMove = vm::pickMove,
                        onMoveStep = vm::moveStep,
                        onFireSalvo = vm::fireSalvo,
                        onFlipAxis = vm::flipAxis,
                        onFlipMargin = vm::flipMargin,
                        onCancelTargeting = vm::cancelTargeting,
                        onSkip = {},
                    ),
                    onPause = {},
                    onResume = {},
                    onRestart = onExit,
                    onLeave = onExit,
                    onPlayAgain = onExit,
                    onSettings = { _ -> },
                )
            }
        }
        is DuoPhase.Ended -> DuoEnded(
            game = current,
            onNext = vm::nextGame,
            onLeave = onExit,
        )
    }
}

/** An empty sea of the match's size, the base of the battle view (the render replaces it). */
private fun blank(match: BattleMatch): BoardRenderModel =
    BoardRenderModel(match.width, match.height, List(match.width * match.height) { CellVisual.UNKNOWN })

@Composable
private fun scoreText(game: DuoGame): String {
    val first = game.names.getOrElse(0) { "" }
    val second = game.names.getOrElse(1) { "" }
    return stringResource(R.string.duo_score, first, game.session.wins[0], second, game.session.wins[1], game.session.draws)
}

/** The names and the rules of the duel. The rules are the free play ones; the starter alternates on its own. */
@Composable
private fun DuoSetup(
    initialConfig: FreePlayConfig,
    onStart: (List<String>, FreePlayConfig) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var config by remember { mutableStateOf(initialConfig) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val defaultFirst = stringResource(R.string.duo_default_first)
    val defaultSecond = stringResource(R.string.duo_default_second)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.freeplay_back), style = type.button, color = colors.text) }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.duo_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            }
            Section(R.string.duo_name_first) {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.take(NAME_MAX) },
                    placeholder = { Text(stringResource(R.string.duo_default_first)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Section(R.string.duo_name_second) {
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.take(NAME_MAX) },
                    placeholder = { Text(stringResource(R.string.duo_default_second)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.duo_names_note), style = type.caption, color = colors.textMuted)
            Section(R.string.freeplay_section_grid) {
                ChoiceRow(FreePlayConfig.GRID_SIZES, config.grid, { stringResource(R.string.freeplay_grid_label, it, it) }) { grid ->
                    config = config.copy(grid = grid)
                }
            }
            Section(R.string.freeplay_section_fleet) {
                ChoiceRow(
                    FleetChoice.entries,
                    config.fleet,
                    { stringResource(if (it == FleetChoice.CLASSIC) R.string.freeplay_fleet_classic else R.string.freeplay_fleet_school) },
                ) { fleet -> config = config.copy(fleet = fleet) }
            }
            Section(R.string.freeplay_section_rules) {
                ToggleRow(R.string.freeplay_islands, config.islands) { on -> config = config.copy(islands = on) }
                ToggleRow(R.string.freeplay_salvo, config.salvo) { on -> config = config.copy(salvo = on) }
                ToggleRow(R.string.freeplay_diving, config.diving) { on -> config = config.copy(diving = on) }
                ToggleRow(R.string.freeplay_fog, config.fog) { on -> config = config.copy(fog = on) }
                ToggleRow(R.string.freeplay_moving, config.movingShips) { on -> config = config.copy(movingShips = on) }
            }
            Section(R.string.freeplay_section_mines) {
                MineStepper(config.minesPerSide) { count -> config = config.copy(minesPerSide = count) }
            }
            Section(R.string.freeplay_section_weapons) {
                WeaponChips(config.weapons) { weapon, on ->
                    config = config.copy(weapons = if (on) config.weapons + weapon else config.weapons - weapon)
                }
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(LocalFleetShapes.current.button),
                color = colors.accent,
                contentColor = colors.onAccent,
                modifier = Modifier.fillMaxWidth().clickable {
                    onStart(listOf(first.ifBlank { defaultFirst }, second.ifBlank { defaultSecond }), config)
                },
            ) {
                Text(
                    stringResource(R.string.duo_start),
                    style = type.button,
                    color = colors.onAccent,
                    modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The curtain: opaque, with the name and the reason only. Nothing of either sea is drawn behind it. */
@Composable
private fun DuoCurtain(name: String, reason: CurtainReason, onShow: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                if (reason == CurtainReason.PLACE) {
                    stringResource(R.string.duo_curtain_place, name)
                } else {
                    stringResource(R.string.duo_curtain_turn, name)
                },
                style = type.screenTitle.copy(fontSize = 26.sp),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Surface(
                shape = RoundedCornerShape(LocalFleetShapes.current.button),
                color = colors.accent,
                contentColor = colors.onAccent,
                modifier = Modifier.clickable(onClick = onShow),
            ) {
                Text(
                    stringResource(R.string.duo_show_sea),
                    style = type.button,
                    color = colors.onAccent,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** The placement of one seat's fleet. Only the seat's own placement screen is shown. */
@Composable
private fun DuoPlacement(
    container: AppContainer,
    game: DuoGame,
    seat: Int,
    themeId: ThemeId,
    onPlaced: (List<Placement>) -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val placementVm: PlacementViewModel = viewModel(
        key = "duo-placement-${game.gameIndex}-$seat-${game.attemptSeed}",
        factory = factory { PlacementViewModel(container, FreePlayBuilder.baseRules(game.config, game.terrain)) },
    )
    val placement by placementVm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.duo_placing, game.names.getOrElse(seat) { "" }),
            style = type.caption,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (game.refusal == DuoFlow.REFUSAL_SETUP) {
            Text(
                stringResource(R.string.duo_setup_refused),
                style = type.caption,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Box(Modifier.weight(1f)) {
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
                    onReady = { placementVm.readyPlacements()?.let(onPlaced) },
                    onBack = {},
                ),
            )
        }
    }
}

/** The end of a game: the winner (or the draw), the score, and the next game. Both fleets are revealed here. */
@Composable
private fun DuoEnded(game: DuoGame, onNext: () -> Unit, onLeave: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val winner = (game.phase as? DuoPhase.Ended)?.winner
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                if (winner == null) {
                    stringResource(R.string.duo_draw)
                } else {
                    stringResource(R.string.duo_winner, game.names.getOrElse(winner) { "" })
                },
                style = type.screenTitle,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Text(scoreText(game), style = type.caption, color = colors.textMuted, textAlign = TextAlign.Center)
            Surface(
                shape = RoundedCornerShape(LocalFleetShapes.current.button),
                color = colors.accent,
                contentColor = colors.onAccent,
                modifier = Modifier.clickable(onClick = onNext),
            ) {
                Text(stringResource(R.string.duo_next_game), style = type.button, color = colors.onAccent, modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp))
            }
            OutlinedButton(onClick = onLeave) {
                Text(stringResource(R.string.duo_leave), style = type.button, color = colors.text)
            }
        }
    }
}

private const val NAME_MAX = 24

private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}
