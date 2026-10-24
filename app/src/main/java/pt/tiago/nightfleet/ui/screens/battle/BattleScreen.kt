package pt.tiago.nightfleet.ui.screens.battle

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.engine.SubmarinePhase
import pt.tiago.nightfleet.game.analysis.AnalysisInput
import pt.tiago.nightfleet.game.campaign.ResultExtras
import pt.tiago.nightfleet.ui.screens.analysis.AnalysisScreen
import pt.tiago.nightfleet.ui.screens.replay.ReplayScreen
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.MotionPreference
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleUi
import pt.tiago.nightfleet.game.battle.BattleViewModel
import pt.tiago.nightfleet.game.battle.ConvoyHud
import pt.tiago.nightfleet.game.battle.LogKind
import pt.tiago.nightfleet.game.battle.LogLine
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.game.battle.TargetKind
import pt.tiago.nightfleet.game.battle.Targeting
import pt.tiago.nightfleet.game.battle.WeaponCatalog
import pt.tiago.nightfleet.ui.board.LocalReducedMotion
import pt.tiago.nightfleet.ui.board.ReducedMotionProvider
import pt.tiago.nightfleet.ui.board.SeaBoard
import pt.tiago.nightfleet.ui.board.SeaWeatherBackdrop
import pt.tiago.nightfleet.ui.board.ShakeLevel
import pt.tiago.nightfleet.ui.board.WeatherKind
import pt.tiago.nightfleet.ui.board.screenShake
import pt.tiago.nightfleet.ui.screens.pause.PauseSheet
import pt.tiago.nightfleet.ui.screens.placement.shipNameRes
import pt.tiago.nightfleet.ui.screens.result.ResultSheet
import pt.tiago.nightfleet.ui.screens.settings.weatherActive
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.tutorial.tutorialAnchor

/** The labels of the battle that depend on the mode: the quick battle and free play share the screen. */
data class BattleFrame(
    val opponentName: String = QuickBattle.ADMIRAL_NAME,
    /** The title line of the HUD. Null shows the default title. */
    val title: String? = null,
    /** The second line of the HUD. Null shows the medium difficulty of the quick battle. */
    val subtitle: String? = null,
    /** A patrol sea: the HUD shows the shots left in the reserve of the run (the budget of the hunt). */
    val reserveShown: Boolean = false,
    /** The weather of the sea behind the battle (campaign seas only). Drawn outside the grids, see [SeaWeatherBackdrop]. */
    val weather: WeatherKind = WeatherKind.CALM,
)

/** Callbacks of the battle screen, one per player action. */
data class BattleActions(
    val onCellTap: (Int) -> Unit,
    val onOwnTap: (Int) -> Unit,
    val onPickWeapon: (Weapon) -> Unit,
    val onPickMove: () -> Unit,
    val onMoveStep: (Int) -> Unit,
    val onFireSalvo: () -> Unit,
    val onFlipAxis: () -> Unit,
    val onFlipMargin: () -> Unit,
    val onCancelTargeting: () -> Unit,
    val onSkip: () -> Unit,
    /** The officer's hint (lightbulb): lights the best squares for a while. */
    val onHint: () -> Unit = {},
)

/** The squares of the enemy sea that the guided tutorial points at. */
internal val TUTORIAL_ENEMY_CELLS: Set<String> = setOf("cell:D4")

/** The battle screen. */
@Composable
fun BattleScreen(
    viewModel: BattleViewModel,
    settings: Settings,
    themeId: ThemeId,
    onLeave: () -> Unit,
    onRestart: () -> Unit,
    onPlayAgain: () -> Unit,
    frame: BattleFrame = BattleFrame(),
) {
    val ui by viewModel.ui.collectAsState()
    val extras by viewModel.extras.collectAsState()
    var overlay by rememberSaveable { mutableStateOf(BattleOverlay.NONE) }
    val analysis by produceState<AnalysisInput?>(null, ui.summary) {
        value = if (ui.summary != null) viewModel.analysisInput() else null
    }
    val scope = rememberCoroutineScope()
    val override: Boolean? = when (settings.reducedMotion) {
        MotionPreference.ON -> true
        MotionPreference.OFF -> false
        MotionPreference.FOLLOW_SYSTEM -> null
    }
    val actions = BattleActions(
        onCellTap = viewModel::tap,
        onOwnTap = viewModel::ownTap,
        onPickWeapon = viewModel::pickWeapon,
        onPickMove = viewModel::pickMove,
        onMoveStep = viewModel::moveStep,
        onFireSalvo = viewModel::fireSalvo,
        onFlipAxis = viewModel::flipAxis,
        onFlipMargin = viewModel::flipMargin,
        onCancelTargeting = viewModel::cancelTargeting,
        onSkip = viewModel::skip,
        onHint = viewModel::useHint,
    )
    ReducedMotionProvider(override = override) {
        BattleContent(
            ui = ui,
            settings = settings,
            themeId = themeId,
            frame = frame,
            actions = actions,
            onPause = { viewModel.openPause(true) },
            onResume = { viewModel.openPause(false) },
            onRestart = { viewModel.abandon(onRestart) },
            onLeave = onLeave,
            onPlayAgain = onPlayAgain,
            onSettings = viewModel::updateSettings,
            onAnalysis = { overlay = BattleOverlay.ANALYSIS },
            extras = extras,
        )
    }
    when (overlay) {
        BattleOverlay.NONE -> Unit
        BattleOverlay.ANALYSIS -> analysis?.let { input ->
            AnalysisScreen(
                input = input,
                onSave = { summary -> viewModel.saveAnalysis(input.logId, summary) },
                onReplay = { overlay = BattleOverlay.REPLAY },
                onBack = { overlay = BattleOverlay.NONE },
            )
        }
        BattleOverlay.REPLAY -> analysis?.let { input ->
            ReplayScreen(
                payload = input.replay,
                themeId = themeId,
                onBack = { overlay = BattleOverlay.ANALYSIS },
            )
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler {
        when {
            overlay == BattleOverlay.REPLAY -> overlay = BattleOverlay.ANALYSIS
            overlay == BattleOverlay.ANALYSIS -> overlay = BattleOverlay.NONE
            ui.summary != null -> onLeave()
            ui.pauseOpen -> viewModel.openPause(false)
            ui.targeting != Targeting.Idle -> viewModel.cancelTargeting()
            else -> viewModel.openPause(true)
        }
    }
}

/** The battle layout for a render model: shared by the battle screen and the two-player duel. */
@Composable
internal fun BattleContent(
    ui: BattleUi,
    settings: Settings,
    themeId: ThemeId,
    frame: BattleFrame,
    actions: BattleActions,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onLeave: () -> Unit,
    onPlayAgain: () -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
    onAnalysis: () -> Unit = {},
    extras: ResultExtras? = null,
) {
    val colors = LocalFleetColors.current
    // The window size in px: same orientation test as the dp values, and it follows split-screen and fold changes.
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        SeaWeatherBackdrop(
            kind = frame.weather,
            enabled = settings.weatherActive(LocalReducedMotion.current),
            modifier = Modifier.fillMaxSize(),
        )
        val content = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .screenShake(ui.shakeTrigger, ui.shakeLevel ?: ShakeLevel.HIT)
        if (landscape) {
            Row(content, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(2f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EnemySea(ui, themeId, actions, Modifier.weight(1f))
                    AimPromptOnEnemy(ui, actions, Modifier.fillMaxWidth())
                    FleetStatus(ui)
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hud(ui, frame, onPause)
                    TurnLine(ui, frame, actions.onSkip, actions.onHint)
                    LogRow(ui, frame.opponentName)
                    SeaAndWeapons(ui, settings, themeId, actions, Modifier.weight(1f))
                }
            }
        } else {
            Column(content, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hud(ui, frame, onPause)
                TurnLine(ui, frame, actions.onSkip, actions.onHint)
                LogRow(ui, frame.opponentName)
                EnemySea(ui, themeId, actions, Modifier.weight(1f))
                AimPromptOnEnemy(ui, actions, Modifier.fillMaxWidth())
                FleetStatus(ui)
                SeaAndWeapons(ui, settings, themeId, actions, Modifier.fillMaxWidth())
                Spacer(Modifier.heightIn(min = 8.dp))
            }
        }
    }

    if (ui.pauseOpen && ui.summary == null) {
        PauseSheet(
            settings = settings,
            onContinue = onResume,
            onRestart = onRestart,
            onLeave = onLeave,
            onSettings = onSettings,
        )
    }
    ui.summary?.let { summary ->
        ResultSheet(
            summary = summary,
            onPlayAgain = onPlayAgain,
            onHome = onLeave,
            onAnalysis = onAnalysis,
            reducedMotion = LocalReducedMotion.current,
            extras = extras,
        )
    }
}

/**
 * The enemy sea, with the marks of the aiming: the squares of a salvo, the line of a torpedo or
 * recon, and the misses that fade during the current step.
 */
@Composable
private fun EnemySea(ui: BattleUi, themeId: ThemeId, actions: BattleActions, modifier: Modifier) {
    val width = ui.enemy.width
    val height = ui.enemy.height
    val highlight: Set<Int> = when (val target = ui.targeting) {
        is Targeting.Salvo -> target.picked.toSet()
        is Targeting.Line -> target.anchor?.let { anchor ->
            (0 until width * height).filter { index ->
                if (target.isRow) index / width == anchor.row else index % width == anchor.col
            }.toSet()
        }.orEmpty()
        else -> emptySet()
    }
    val fading = ui.fadingCells.associateWith { FADING_ALPHA }
    val model = ui.enemy.copy(highlight = highlight, fadedMisses = ui.enemy.fadedMisses + fading)
    SeaBoard(
        model = model,
        themeId = themeId,
        modifier = modifier.fillMaxWidth().tutorialAnchor("enemy_board"),
        effects = ui.enemyEffects,
        interactive = !ui.busy,
        onCellTap = actions.onCellTap,
        tutorialCells = TUTORIAL_ENEMY_CELLS,
    )
}

@Composable
private fun Hud(ui: BattleUi, frame: BattleFrame, onPause: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val pauseLabel = stringResource(R.string.battle_pause)
        // The pause button keeps the theme's button shape: a fully rounded one looked like an empty avatar.
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            ClockRing(ui)
            OutlinedButton(
                onClick = onPause,
                shape = RoundedCornerShape(shapes.button),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp).semantics { contentDescription = pauseLabel },
            ) { Text("II", style = type.button, color = colors.text) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                frame.title ?: stringResource(R.string.battle_title),
                style = type.hudMission,
                color = colors.text,
                modifier = Modifier.tutorialAnchor("stars"),
            )
            Text(
                frame.subtitle ?: stringResource(R.string.battle_subtitle, stringResource(R.string.difficulty_medium)),
                style = type.caption,
                color = colors.textMuted,
            )
            val budget = ui.match.state.shotBudget
            if (frame.reserveShown && budget != null) {
                Text(
                    stringResource(R.string.patrol_reserve_hud, budget - ui.match.state.sides[0].starShots),
                    style = type.caption,
                    color = colors.accent,
                )
            }
        }
        Text(
            stringResource(R.string.battle_shots, ui.match.playerShots),
            style = type.hudShots,
            color = colors.text,
            modifier = Modifier.tutorialAnchor("shots_counter"),
        )
    }
}

@Composable
private fun TurnLine(ui: BattleUi, frame: BattleFrame, onSkip: () -> Unit, onHint: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    val yourTurn = ui.myTurn && !ui.busy
    Row(
        Modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The left side takes what the hint and skip chips leave and ellipsizes, so a long admiral name never squeezes
        // the chips on the right into one letter per line.
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Surface(
            shape = RoundedCornerShape(shapes.button),
            color = if (yourTurn) colors.accent else colors.surface2,
            contentColor = if (yourTurn) colors.onAccent else colors.text,
        ) {
            Text(
                if (yourTurn) {
                    stringResource(R.string.battle_turn_yours)
                } else {
                    stringResource(R.string.battle_turn_admiral, frame.opponentName)
                },
                style = type.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        ui.submarinePhase?.let { phase ->
            val rounds = ui.roundsUntilSubmarinePhaseChange ?: 0
            Text(
                if (phase == SubmarinePhase.SUBMERGED) {
                    pluralStringResource(R.plurals.battle_submerged_chip, rounds, rounds)
                } else {
                    pluralStringResource(R.plurals.battle_surfaced_chip, rounds, rounds)
                },
                style = type.caption,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
        if (ui.hintsLeft > 0 && ui.summary == null) {
            val hintLabel = stringResource(R.string.battle_hint_left, ui.hintsLeft)
            Surface(
                shape = RoundedCornerShape(shapes.button),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.accent.copy(alpha = if (yourTurn) 1f else 0.4f)),
                modifier = Modifier
                    .tutorialAnchor("hint_button")
                    .semantics { contentDescription = hintLabel }
                    .clickable(enabled = yourTurn, onClick = onHint),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LightbulbIcon(colors.accent)
                    Text(ui.hintsLeft.toString(), style = type.caption, color = colors.text)
                }
            }
        }
        if (ui.busy && ui.summary == null) {
            Surface(
                shape = RoundedCornerShape(shapes.button),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.line),
                modifier = Modifier.clickable(onClick = onSkip),
            ) {
                Text(
                    stringResource(R.string.battle_skip),
                    style = type.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** The log line: a refusal first, then the notice of the moment, then the last shot. */
@Composable
private fun LogRow(ui: BattleUi, opponentName: String) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val text: String
    val tint: Color
    val taunt = tauntText(ui.taunt)
    when {
        ui.refusal != null -> {
            text = refusalText(ui.refusal)
            tint = colors.danger
        }
        ui.refusedTap != null -> {
            text = stringResource(R.string.battle_already_fired)
            tint = colors.danger
        }
        ui.notice != null -> {
            text = noticeText(ui.notice)
            tint = colors.text
        }
        taunt != null -> {
            text = stringResource(R.string.battle_taunt_line, opponentName, taunt)
            tint = colors.text
        }
        else -> {
            text = logText(ui.lastLog)
            tint = colors.textMuted
        }
    }
    Text(
        text,
        style = type.coordinate.copy(fontSize = 12.sp),
        color = tint,
        modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
    )
}

@Composable
private fun logText(line: LogLine?): String {
    if (line == null) return stringResource(R.string.battle_log_none)
    val cell = line.cell.label()
    val ship = line.shipClass?.let { stringResource(shipNameRes(it)) }.orEmpty()
    return when {
        line.byPlayer && line.kind == LogKind.MISS -> stringResource(R.string.battle_log_player_miss, cell)
        line.byPlayer && line.kind == LogKind.HIT -> stringResource(R.string.battle_log_player_hit, cell)
        line.byPlayer -> stringResource(R.string.battle_log_player_sunk, cell, ship)
        line.kind == LogKind.MISS -> stringResource(R.string.battle_log_admiral_miss, QuickBattle.ADMIRAL_NAME, cell)
        line.kind == LogKind.HIT -> stringResource(R.string.battle_log_admiral_hit, QuickBattle.ADMIRAL_NAME, cell)
        else -> stringResource(R.string.battle_log_admiral_sunk, QuickBattle.ADMIRAL_NAME, cell, ship)
    }
}

/**
 * The prompt of an aim that is made on the enemy sea: the salvo header and its buttons, or the
 * row/column and margin chips of a line, or the cancel of a square. Own-sea aims (repair, move) show
 * their prompt beside the own sea instead.
 */
@Composable
private fun AimPromptOnEnemy(ui: BattleUi, actions: BattleActions, modifier: Modifier) {
    val aimsAtEnemy = when (val target = ui.targeting) {
        is Targeting.Salvo, is Targeting.Line -> true
        is Targeting.Square -> WeaponCatalog.kind(target.weapon) != TargetKind.SQUARE_OWN
        else -> false
    }
    if (!aimsAtEnemy) return
    TargetingPanel(
        ui = ui,
        onFireSalvo = actions.onFireSalvo,
        onClearSalvo = actions.onCancelTargeting,
        onFlipAxis = actions.onFlipAxis,
        onFlipMargin = actions.onFlipMargin,
        onCancel = actions.onCancelTargeting,
        onMoveStep = actions.onMoveStep,
        modifier = modifier,
    )
}

/**
 * The weapons strip over the own sea. The strip takes the full width so that no weapon name is squeezed; the own
 * sea sits on the thumb side (right for left-handed play) and its aim prompt goes under it.
 */
@Composable
private fun SeaAndWeapons(ui: BattleUi, settings: Settings, themeId: ThemeId, actions: BattleActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WeaponsStrip(ui = ui, onPickWeapon = actions.onPickWeapon, onPickMove = actions.onPickMove, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (settings.leftHanded) Arrangement.End else Arrangement.Start,
        ) {
            OwnSea(ui, themeId, actions.onOwnTap)
        }
        OwnPrompt(ui, actions)
    }
}

/** The prompt under the own sea while a repair or a move is aimed: the arrows of a moving ship, or the repair hint. */
@Composable
private fun OwnPrompt(ui: BattleUi, actions: BattleActions) {
    val target = ui.targeting
    val ownAim = target is Targeting.Move || (target is Targeting.Square && target.weapon == Weapon.REPAIR)
    if (!ownAim) return
    TargetingPanel(
        ui = ui,
        onFireSalvo = {},
        onClearSalvo = {},
        onFlipAxis = {},
        onFlipMargin = {},
        onCancel = actions.onCancelTargeting,
        onMoveStep = actions.onMoveStep,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FleetStatus(ui: BattleUi) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val sunkLeft = ui.enemySunkClasses.groupingBy { it }.eachCount().toMutableMap()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.battle_enemy_fleet), style = type.label, color = colors.textMuted)
            ui.convoy?.let { ConvoyLine(it) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.enemyFleet.forEach { ship ->
                val left = sunkLeft[ship] ?: 0
                val sunk = left > 0
                if (sunk) sunkLeft[ship] = left - 1
                ShipGroup(ship, sunk)
            }
        }
    }
}

/** The convoy HUD: rounds survived out of the required, and the freighters afloat. */
@Composable
private fun ConvoyLine(hud: ConvoyHud) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Text(
        pluralStringResource(R.plurals.battle_convoy_hud, hud.freightersTotal, hud.roundsSurvived, hud.roundsRequired, hud.freightersAfloat, hud.freightersTotal),
        style = type.caption,
        color = colors.textMuted,
    )
}

@Composable
private fun ShipGroup(ship: ShipClass, sunk: Boolean) {
    val colors = LocalFleetColors.current
    val name = stringResource(shipNameRes(ship))
    val state = stringResource(if (sunk) R.string.battle_ship_sunk else R.string.battle_ship_afloat)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics { contentDescription = "$name: $state" },
    ) {
        repeat(ship.size) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(if (sunk) colors.hit else colors.surface2, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * The player's own sea. It grows on the admiral's turn when the setting is on,
 * and while a repair or a move is aimed, when its squares take the taps.
 */
@Composable
private fun OwnSea(ui: BattleUi, themeId: ThemeId, onOwnTap: (Int) -> Unit) {
    val reduced = LocalReducedMotion.current
    val aiming = ui.targeting is Targeting.Move || (ui.targeting as? Targeting.Square)?.weapon == Weapon.REPAIR
    val target = if (ui.ownSeaLarge || aiming) 240.dp else 122.dp
    val size by animateDpAsState(
        targetValue = target,
        animationSpec = if (reduced) snap() else tween(durationMillis = 250),
        label = "own-sea-size",
    )
    val highlight: Set<Int> = (ui.targeting as? Targeting.Move)?.shipId?.let { shipCells(ui, it) }.orEmpty()
    SeaBoard(
        model = ui.own.copy(highlight = highlight),
        themeId = themeId,
        modifier = Modifier.size(size).tutorialAnchor("own_board"),
        showCoordinates = false,
        gapDp = 1f,
        interactive = aiming && !ui.busy,
        effects = ui.ownEffects,
        onCellTap = onOwnTap,
    )
}

/** Squares of one of the player's ships, as row-major indices. */
private fun shipCells(ui: BattleUi, shipId: Int): Set<Int> {
    val player = ui.match.state.sides[ui.viewer]
    val ship = player.ships.getOrNull(shipId) ?: return emptySet()
    return ship.placement.cells().map { player.board.index(it) }.toSet()
}

private const val FADING_ALPHA = 0.4f

/** The screens that open over the battle after it ends: the analysis, and the replay from it. */
private enum class BattleOverlay { NONE, ANALYSIS, REPLAY }

/** The officer's lightbulb: a bulb and its base, in the given tint. */
@Composable
private fun LightbulbIcon(tint: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(tint, radius = w * 0.36f, center = Offset(w / 2, h * 0.4f), style = Stroke(width = 1.4.dp.toPx()))
        drawLine(tint, Offset(w * 0.36f, h * 0.86f), Offset(w * 0.64f, h * 0.86f), strokeWidth = 1.4.dp.toPx())
    }
}

/**
 * The text of an admiral's taunt, from its resource name, or null when there is none (see Taunts). The names come
 * from the admirals' data in :core, so the lookup is by name on purpose; lint cannot check it.
 */
@SuppressLint("DiscouragedApi", "LocalContextResourcesRead")
@Composable
private fun tauntText(key: String?): String? {
    if (key == null) return null
    val context = LocalContext.current
    val id = remember(key) { context.resources.getIdentifier(key, "string", context.packageName) }
    return if (id == 0) null else stringResource(id)
}
