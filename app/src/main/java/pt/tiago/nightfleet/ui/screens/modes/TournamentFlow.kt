package pt.tiago.nightfleet.ui.screens.modes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleSource
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.PlacementViewModel
import pt.tiago.nightfleet.game.campaign.campaignHome
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.modes.Mechanic
import pt.tiago.nightfleet.game.modes.ModeStake
import pt.tiago.nightfleet.game.modes.TournamentRules
import pt.tiago.nightfleet.game.modes.TournamentRun
import pt.tiago.nightfleet.ui.screens.battle.BattleFrame
import pt.tiago.nightfleet.ui.screens.battle.BattleScreen
import pt.tiago.nightfleet.ui.screens.freeplay.Section
import pt.tiago.nightfleet.ui.screens.freeplay.ChoiceRow
import pt.tiago.nightfleet.ui.screens.freeplay.admiralNameRes
import pt.tiago.nightfleet.ui.screens.placement.PlacementActions
import pt.tiago.nightfleet.ui.screens.placement.PlacementScreen
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId

/**
 * The Admirals' tournament from "More modes": the choice of the ladder among the admirals already
 * defeated, the bracket with the progress, the placement and the duels, then the result. The run is saved between
 * duels, and the duel in progress after every shot. Restarting a duel forfeits the tournament: a duel is never repeated.
 */
@Composable
fun TournamentFlow(
    container: AppContainer,
    themeId: ThemeId,
    onExit: () -> Unit,
    /** Opens the sea map of the campaign's current sea in [difficulty]. */
    onCampaign: (sea: Int, difficulty: Difficulty) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = Settings())
    var phase by remember { mutableStateOf<TournamentPhase>(TournamentPhase.Loading) }
    var counter by remember { mutableIntStateOf(0) }
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.MEDIUM) }

    LaunchedEffect(Unit) {
        val saved = container.modeRuns.tournament()
        if (saved != null) {
            difficulty = saved.difficulty
            phase = TournamentPhase.Bracket(saved, notice = null)
        } else {
            val profile = container.profile.load()
            val eligible = TournamentRules.eligible(profile)
            phase = TournamentPhase.Setup(
                eligible = eligible,
                chosen = eligible.take(TournamentRules.LADDER_SIZE),
                best = profile.tournamentBest,
            )
        }
    }

    /** Starts a tournament with the chosen admirals; the first bracket is saved at once. */
    fun start(chosen: List<String>) {
        scope.launch {
            container.matches.clear(MatchKind.TOURNAMENT)
            val run = TournamentRun.start(difficulty, newRunSeed(), chosen)
            container.modeRuns.saveTournament(run)
            phase = TournamentPhase.Bracket(run, notice = null)
        }
    }

    /** The duel of the current round: the one in progress if there is one, else a new placement. */
    fun play(run: TournamentRun) {
        scope.launch {
            val restored = restoreSaved(container, MatchKind.TOURNAMENT, run.matchId)
            if (restored != null) {
                phase = TournamentPhase.Duel(++counter, run, restored.first, restored.second)
                return@launch
            }
            val config = TournamentRules.duelConfig(run, run.round)
            val seed = TournamentRules.duelSeed(run.seed, run.round)
            val terrain = FreePlayBuilder.terrainFor(config, seed)
            phase = TournamentPhase.Placement(++counter, run, config, terrain, seed)
        }
    }

    /** The duel is over: a win moves the bracket on, a loss or the last win ends the tournament. */
    fun finishDuel(run: TournamentRun, won: Boolean) {
        scope.launch {
            val next = run.afterDuel(won)
            if (next.ended || next.champion) {
                container.modeRuns.clearTournament()
                phase = TournamentPhase.Over(wins = next.wins, champion = next.champion, forfeited = false)
            } else {
                container.modeRuns.saveTournament(next)
                phase = TournamentPhase.Bracket(next, notice = null)
            }
        }
    }

    /** Restarting a duel is not allowed (no repeats): it forfeits the tournament. */
    fun forfeit(run: TournamentRun) {
        scope.launch {
            container.modeRuns.clearTournament()
            phase = TournamentPhase.Over(wins = run.wins, champion = false, forfeited = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = phase) {
            TournamentPhase.Loading -> Unit
            is TournamentPhase.Setup -> TournamentSetupScreen(
                difficulty = difficulty,
                onDifficulty = { difficulty = it },
                eligible = current.eligible,
                chosen = current.chosen,
                best = current.best,
                onToggle = { id ->
                    val next = if (id in current.chosen) current.chosen - id else current.chosen + id
                    if (next.size <= TournamentRules.LADDER_SIZE) phase = current.copy(chosen = next)
                },
                onStart = { start(current.chosen) },
                onCampaign = {
                    scope.launch {
                        val profile = container.profile.load()
                        onCampaign(campaignHome(profile, difficulty).sea, difficulty)
                    }
                },
                onBack = onExit,
            )
            is TournamentPhase.Bracket -> TournamentBracketScreen(
                run = current.run,
                notice = current.notice,
                onPlay = { play(current.run) },
                onBack = onExit,
            )
            is TournamentPhase.Placement -> {
                val placementVm: PlacementViewModel = viewModel(
                    key = "tournament-placement-${current.key}",
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
                                scope.launch {
                                    val setup = duelSetup(current, placements)
                                    if (setup == null) {
                                        phase = TournamentPhase.Bracket(current.run, notice = R.string.tournament_build_failed)
                                    } else {
                                        container.matches.clear(MatchKind.TOURNAMENT)
                                        phase = TournamentPhase.Duel(++counter, current.run, setup, restored = null)
                                    }
                                }
                            }
                        },
                        onBack = { phase = TournamentPhase.Bracket(current.run, notice = null) },
                    ),
                )
            }
            is TournamentPhase.Duel -> TournamentDuelScreen(
                key = current.key,
                container = container,
                context = context,
                themeId = themeId,
                settings = settings,
                run = current.run,
                setup = current.setup,
                restored = current.restored,
                onFinish = { won -> finishDuel(current.run, won) },
                onRestart = { forfeit(current.run) },
                onLeave = onExit,
            )
            is TournamentPhase.Over -> TournamentOverScreen(
                wins = current.wins,
                champion = current.champion,
                forfeited = current.forfeited,
                onAgain = {
                    scope.launch {
                        val profile = container.profile.load()
                        val eligible = TournamentRules.eligible(profile)
                        phase = TournamentPhase.Setup(eligible, eligible.take(TournamentRules.LADDER_SIZE), profile.tournamentBest)
                    }
                },
                onBack = onExit,
            )
        }
    }
}

/** The battle of the duel of [run]'s current round, from the placement of the player's fleet. */
private fun duelSetupOf(config: FreePlayConfig, terrain: List<Terrain>, seed: Long, placements: List<Placement>): MatchSetup? =
    FreePlayBuilder.finish(config, terrain, seed, placements)

private fun duelSetup(current: TournamentPhase.Placement, placements: List<Placement>): MatchSetup? =
    duelSetupOf(current.config, current.terrain, current.seed, placements)

/** The phases of the tournament flow. */
private sealed interface TournamentPhase {
    data object Loading : TournamentPhase

    /** The ladder choice: the defeated admirals, the ones chosen so far and the record. */
    data class Setup(
        val eligible: List<String>,
        val chosen: List<String>,
        val best: Map<Difficulty, Int>,
    ) : TournamentPhase

    data class Bracket(val run: TournamentRun, val notice: Int?) : TournamentPhase

    data class Placement(
        val key: Int,
        val run: TournamentRun,
        val config: FreePlayConfig,
        val terrain: List<Terrain>,
        val seed: Long,
    ) : TournamentPhase

    data class Duel(
        val key: Int,
        val run: TournamentRun,
        val setup: MatchSetup,
        val restored: BattleMatch?,
    ) : TournamentPhase

    data class Over(val wins: Int, val champion: Boolean, val forfeited: Boolean) : TournamentPhase
}

@Composable
private fun TournamentSetupScreen(
    difficulty: Difficulty,
    onDifficulty: (Difficulty) -> Unit,
    eligible: List<String>,
    chosen: List<String>,
    best: Map<Difficulty, Int>,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
    onCampaign: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val size = TournamentRules.LADDER_SIZE
    ModeScaffold(title = stringResource(R.string.tournament_title), onBack = onBack) {
        Text(stringResource(R.string.tournament_intro), style = type.bodyText, color = colors.text)
        Section(R.string.mode_difficulty) {
            ChoiceRow(Difficulty.entries, difficulty, label = { difficultyLabel(it) }, onPick = onDifficulty)
        }
        Text(
            stringResource(R.string.tournament_record, best[difficulty] ?: 0),
            style = type.caption,
            color = colors.textMuted,
        )
        if (eligible.size < size) {
            Text(
                pluralStringResource(R.plurals.tournament_need, size, eligible.size, size),
                style = type.bodyText,
                color = colors.text,
            )
            ModeButton(stringResource(R.string.tournament_to_campaign)) { onCampaign() }
        } else {
            Section(R.string.tournament_eligible_title) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    eligible.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { id ->
                                FilterChip(
                                    selected = id in chosen,
                                    onClick = { onToggle(id) },
                                    label = { Text(stringResource(admiralNameRes(id))) },
                                )
                            }
                        }
                    }
                }
                Text(stringResource(R.string.tournament_pick_hint), style = type.caption, color = colors.textMuted)
            }
            Text(
                stringResource(R.string.tournament_choose, chosen.size),
                style = type.caption,
                color = colors.textMuted,
            )
            if (chosen.size == size) ModeButton(stringResource(R.string.tournament_start)) { onStart() }
        }
    }
}

@Composable
private fun TournamentBracketScreen(
    run: TournamentRun,
    notice: Int?,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    ModeScaffold(title = stringResource(R.string.tournament_bracket_title), onBack = onBack) {
        Text(
            stringResource(R.string.tournament_line, run.wins, TournamentRules.LADDER_SIZE),
            style = type.bodyText,
            color = colors.text,
        )
        run.ladder.forEachIndexed { index, id ->
            val status = when {
                index < run.wins -> R.string.tournament_status_won
                index == run.round -> R.string.tournament_status_next
                else -> R.string.tournament_status_ahead
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}. " + stringResource(admiralNameRes(id)),
                        style = type.cardTitle,
                        color = colors.text,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                    Text(stringResource(status), style = type.caption, color = colors.accent)
                }
                Text(
                    stringResource(R.string.tournament_rule_label, ruleName(run.rules[index])),
                    style = type.caption,
                    color = colors.textMuted,
                )
            }
        }
        notice?.let { Text(stringResource(it), style = type.caption, color = colors.accent) }
        ModeButton(stringResource(R.string.tournament_play, run.round + 1)) { onPlay() }
    }
}

@Composable
private fun TournamentDuelScreen(
    key: Int,
    container: AppContainer,
    context: Context,
    themeId: ThemeId,
    settings: Settings,
    run: TournamentRun,
    setup: MatchSetup,
    restored: BattleMatch?,
    onFinish: (won: Boolean) -> Unit,
    onRestart: () -> Unit,
    onLeave: () -> Unit,
) {
    val round = run.round + 1
    val stake = ModeStake.Tournament(
        difficulty = run.difficulty,
        seed = run.seed,
        round = round,
        rounds = run.ladder.size,
        roundsWonBefore = run.wins,
    )
    val battleVm: BattleViewModel = viewModel(
        key = "tournament-duel-$key",
        factory = factory {
            BattleViewModel(
                container,
                context,
                BattleSource.FromSetup(setup, context.getString(admiralNameRes(run.ladder[run.round])), restored, stake = stake),
            )
        },
    )
    BattleScreen(
        viewModel = battleVm,
        settings = settings,
        themeId = themeId,
        onLeave = onLeave,
        onRestart = onRestart,
        onPlayAgain = {
            val summary = battleVm.ui.value.summary
            if (summary != null) onFinish(summary.won)
        },
        frame = BattleFrame(
            opponentName = context.getString(admiralNameRes(run.ladder[run.round])),
            title = stringResource(R.string.tournament_frame_title, round),
            subtitle = stringResource(R.string.tournament_frame_subtitle, difficultyName(context, run.difficulty), ruleName(run.rules[run.round])),
        ),
    )
}

@Composable
private fun TournamentOverScreen(
    wins: Int,
    champion: Boolean,
    forfeited: Boolean,
    onAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val size = TournamentRules.LADDER_SIZE
    ModeScaffold(title = stringResource(R.string.tournament_over_title), onBack = onBack) {
        if (champion) {
            Text(pluralStringResource(R.plurals.tournament_over_champion, size, size), style = type.bodyText, color = colors.accent)
        } else {
            if (forfeited) Text(stringResource(R.string.tournament_over_forfeit), style = type.bodyText, color = colors.text)
            Text(pluralStringResource(R.plurals.tournament_over_lost, size, wins, size), style = type.bodyText, color = colors.text)
        }
        ModeButton(stringResource(R.string.tournament_restart)) { onAgain() }
        ModeButton(stringResource(R.string.mode_back), primary = false) { onBack() }
    }
}

/** The mechanic's title in the campaign's words, or the classic duel. */
@Composable
private fun ruleName(rule: String): String {
    val mechanic = Mechanic.entries.firstOrNull { it.name == rule }
    return if (mechanic == null) stringResource(R.string.tournament_rule_classic) else stringResource(mechanicTitleRes(mechanic))
}

private fun difficultyName(context: Context, difficulty: Difficulty): String = context.getString(
    when (difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    },
)

/** The campaign's introduction title of each mechanic (strings of the intro cards). */
private fun mechanicTitleRes(mechanic: Mechanic): Int = when (mechanic) {
    Mechanic.ISLANDS -> R.string.intro_islands_reefs_title
    Mechanic.SONAR -> R.string.intro_sonar_title
    Mechanic.MINES -> R.string.intro_mines_title
    Mechanic.RECON -> R.string.intro_air_recon_title
    Mechanic.SCHOOL -> R.string.intro_school_rules_title
    Mechanic.DECOY -> R.string.intro_decoy_title
    Mechanic.TORPEDO -> R.string.intro_torpedo_title
    Mechanic.DIVING -> R.string.intro_diving_subs_title
    Mechanic.REPAIR -> R.string.intro_repair_title
    Mechanic.SALVO -> R.string.intro_salvo_title
    Mechanic.CROSS_SHELL -> R.string.intro_cross_barrage_title
    Mechanic.FOG -> R.string.intro_fog_title
    Mechanic.MOVING_SHIPS -> R.string.intro_moving_ships_title
}

private fun factory(build: () -> ViewModel): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = build() as T
}
