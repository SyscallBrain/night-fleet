package pt.tiago.nightfleet.ui.screens.puzzle

import androidx.compose.ui.semantics.heading
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.puzzle.PuzzleReason
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.game.puzzle.PuzzleAction
import pt.tiago.nightfleet.game.puzzle.PuzzleFleet
import pt.tiago.nightfleet.game.puzzle.PuzzleNotice
import pt.tiago.nightfleet.game.puzzle.PuzzleRequest
import pt.tiago.nightfleet.game.puzzle.PuzzleSnapshotCodec
import pt.tiago.nightfleet.game.puzzle.PuzzleUiState
import pt.tiago.nightfleet.game.puzzle.PuzzleViewModel
import pt.tiago.nightfleet.game.puzzle.PuzzleScreenState
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.FleetShapes
import pt.tiago.nightfleet.ui.theme.FleetType
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * Route of the lighthouse puzzle: binds a [PuzzleViewModel] to [request] (a new puzzle or the saved one)
 * and shows [PuzzleScreen]. The ViewModel is keyed by the request, so "next puzzle" starts a fresh one.
 */
@Composable
fun PuzzleRoute(
    request: PuzzleRequest,
    onBack: () -> Unit,
    onNext: (PuzzleRequest) -> Unit,
    /** Campaign and daily puzzles: the solved sheet offers this instead of a generated next puzzle. */
    onNextMission: (() -> Unit)? = null,
    /** False hides the next button (the daily puzzle has no next puzzle). */
    showNext: Boolean = true,
) {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.from(context) }
    val viewModel: PuzzleViewModel = viewModel(
        key = request.encode(),
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PuzzleViewModel(container.matches, container.profile, request, catalog = container.catalog) as T
        },
    )
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    PuzzleScreen(
        screen = screen,
        onAction = viewModel::dispatch,
        onBack = onBack,
        onNext = { size, grade -> onNext(PuzzleRequest.New(size, grade)) },
        nextMission = onNextMission,
        showNext = showNext,
    )
}

/** The puzzle screen. State comes in; actions go out. */
@Composable
fun PuzzleScreen(
    screen: PuzzleScreenState,
    onAction: (PuzzleAction) -> Unit,
    onBack: () -> Unit,
    onNext: (Int, PuzzleGrade) -> Unit,
    modifier: Modifier = Modifier,
    nextMission: (() -> Unit)? = null,
    showNext: Boolean = true,
) {
    val colors = LocalFleetColors.current
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (screen) {
                    PuzzleScreenState.Loading -> {
                        Hud(title = null, onBack = onBack)
                        Text(stringResource(R.string.puzzle_loading), style = LocalFleetType.current.bodyText, color = colors.textMuted)
                    }
                    is PuzzleScreenState.Failed -> {
                        Hud(title = null, onBack = onBack)
                        val message = when (screen.failure) {
                            PuzzleScreenState.Failed.Failure.NO_SAVED -> stringResource(R.string.puzzle_no_saved)
                            PuzzleScreenState.Failed.Failure.GENERATION -> stringResource(R.string.puzzle_failed)
                        }
                        Text(message, style = LocalFleetType.current.bodyText, color = colors.text)
                    }
                    is PuzzleScreenState.Ready -> PuzzleContent(
                        state = screen.state,
                        onAction = onAction,
                        onBack = onBack,
                        onNext = onNext,
                        nextMission = nextMission,
                        showNext = showNext,
                    )
                }
            }
        }
    }
}

@Composable
private fun PuzzleContent(
    state: PuzzleUiState,
    onAction: (PuzzleAction) -> Unit,
    onBack: () -> Unit,
    onNext: (Int, PuzzleGrade) -> Unit,
    nextMission: (() -> Unit)?,
    showNext: Boolean,
) {
    val def = state.definition
    val sheetDismissed = rememberSaveable(def.id) { mutableStateOf(false) }

    Hud(title = stringResource(R.string.puzzle_title), state = state, onBack = onBack)
    FleetRequirements(state)
    PuzzleBoard(state = state, onAction = onAction)
    Legend()
    MessageLine(state)
    ActionButtons(state, onAction)

    if (state.solved && !sheetDismissed.value) {
        SolvedSheet(
            state = state,
            onDismiss = { sheetDismissed.value = true },
            onBack = onBack,
            onNext = { nextMission?.invoke() ?: onNext(def.width, state.grade) },
            nextIsMission = nextMission != null,
            showNext = showNext,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hud(title: String?, state: PuzzleUiState? = null, onBack: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.puzzle_back),
                tint = colors.text,
            )
        }
        Column(Modifier.weight(1f).padding(top = 6.dp)) {
            if (title != null) Text(title, style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            if (state != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val size = state.definition.width
                    Text(
                        stringResource(R.string.puzzle_subtitle, size, state.definition.height, gradeLabel(state.grade)),
                        style = type.caption,
                        color = colors.textMuted,
                    )
                    GradeChip(state.grade)
                }
            }
        }
    }
}

@Composable
private fun GradeChip(grade: PuzzleGrade) {
    val type = LocalFleetType.current
    val color = gradeColor(grade)
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(gradeLabel(grade), style = type.caption, color = color)
    }
}

@Composable
private fun gradeLabel(grade: PuzzleGrade): String = stringResource(
    when (grade) {
        PuzzleGrade.EASY -> R.string.difficulty_easy
        PuzzleGrade.MEDIUM -> R.string.difficulty_medium
        PuzzleGrade.HARD -> R.string.difficulty_hard
    },
)

@Composable
private fun gradeColor(grade: PuzzleGrade): Color {
    val colors = LocalFleetColors.current
    return when (grade) {
        PuzzleGrade.EASY -> colors.difficultyEasy
        PuzzleGrade.MEDIUM -> colors.difficultyMedium
        PuzzleGrade.HARD -> colors.difficultyHard
    }
}

/** One group per ship length: squares for the ships needed, filled as the player places them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FleetRequirements(state: PuzzleUiState) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val groups = remember(state.grid, state.definition) { PuzzleFleet.groups(state.definition, state.grid) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.puzzle_fleet_label), style = type.label, color = colors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in groups) {
                val label = pluralStringResource(R.plurals.puzzle_fleet_group_label, group.need, group.length, minOf(group.have, group.need), group.need)
                Row(
                    Modifier.semantics { contentDescription = label },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val squareShape = RoundedCornerShape(2.dp)
                    for (index in 0 until group.need) {
                        val filled = index < group.have
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(if (filled) colors.ship else colors.surface2, squareShape)
                                .border(1.dp, colors.shipEdge.copy(alpha = if (filled) 1f else 0.4f), squareShape),
                        )
                    }
                    val complete = group.complete
                    Text(
                        stringResource(R.string.puzzle_fleet_count, minOf(group.have, group.need), group.need) +
                            if (complete) " ✓" else "",
                        style = countStyle(type),
                        color = if (complete) colors.accent else colors.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(PlayerCell.EMPTY, null, stringResource(R.string.puzzle_legend_empty), colors, type)
        LegendItem(PlayerCell.WATER, null, stringResource(R.string.puzzle_legend_water), colors, type)
        LegendItem(PlayerCell.SHIP, pt.tiago.nightfleet.core.puzzle.SegmentShape.SINGLE, stringResource(R.string.puzzle_legend_ship), colors, type)
    }
}

@Composable
private fun LegendItem(
    mark: PlayerCell,
    shape: pt.tiago.nightfleet.core.puzzle.SegmentShape?,
    label: String,
    colors: FleetColors,
    type: FleetType,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PuzzleLegendSquare(mark = mark, shape = shape)
        Text(label, style = type.caption, color = colors.textMuted)
    }
}

@Composable
private fun MessageLine(state: PuzzleUiState) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val text = messageText(state)
    Text(
        text,
        style = type.bodyText,
        color = colors.text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun messageText(state: PuzzleUiState): String {
    return when (val notice = state.notice) {
        null -> stringResource(R.string.puzzle_instructions)
        PuzzleNotice.Hint -> state.lastHint?.let { stringResource(reasonRes(it.reasonKey)) }
            ?: stringResource(R.string.puzzle_instructions)
        is PuzzleNotice.Filled -> pluralStringResource(R.plurals.puzzle_notice_filled, notice.cells, notice.cells)
        PuzzleNotice.NothingToFill -> stringResource(R.string.puzzle_notice_nothing_to_fill)
        PuzzleNotice.NoHintsLeft -> stringResource(R.string.puzzle_notice_no_hints_left)
        PuzzleNotice.NoHintAvailable -> stringResource(R.string.puzzle_notice_no_hint)
        PuzzleNotice.VerifyClean -> stringResource(R.string.puzzle_notice_verify_clean)
        is PuzzleNotice.VerifyErrors -> pluralStringResource(R.plurals.puzzle_notice_verify_errors, notice.count, notice.count)
        PuzzleNotice.Restarted -> stringResource(R.string.puzzle_notice_restarted)
        PuzzleNotice.Solved -> stringResource(R.string.puzzle_notice_solved)
    }
}

/** Text of a hint reason (the keys of the core solver). Unknown keys fall back to the contradiction text. */
internal fun reasonRes(key: String): Int = when (key) {
    PuzzleReason.LINE_FULL -> R.string.puzzle_reason_line_full
    PuzzleReason.LINE_NEEDS_ALL -> R.string.puzzle_reason_line_needs_all
    PuzzleReason.DIAGONAL_OF_SHIP -> R.string.puzzle_reason_diagonal_of_ship
    PuzzleReason.SIDE_OF_SHIP -> R.string.puzzle_reason_side_of_ship
    PuzzleReason.GIVEN_SHAPE -> R.string.puzzle_reason_given_shape
    PuzzleReason.FLEET_FIT -> R.string.puzzle_reason_fleet_fit
    PuzzleReason.SHIP_CLOSED -> R.string.puzzle_reason_ship_closed
    PuzzleReason.LINE_FIT -> R.string.puzzle_reason_line_fit
    PuzzleReason.WRONG_MARK -> R.string.puzzle_reason_wrong_mark
    else -> R.string.puzzle_reason_contradiction
}

private fun countStyle(type: FleetType): TextStyle = TextStyle(
    fontFamily = type.data,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 18.sp,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(state: PuzzleUiState, onAction: (PuzzleAction) -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val buttonShape = RoundedCornerShape(shapes.button)
    val minHeight = Modifier.heightIn(min = 48.dp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onAction(PuzzleAction.Hint) },
            modifier = minHeight,
            shape = buttonShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) {
            Text(pluralStringResource(R.plurals.puzzle_hint, state.hintsLeft, state.hintsLeft), style = type.button)
        }
        OutlinedButton(onClick = { onAction(PuzzleAction.Verify) }, modifier = minHeight, shape = buttonShape) {
            Text(stringResource(R.string.puzzle_verify), style = type.button)
        }
        OutlinedButton(onClick = { onAction(PuzzleAction.FillWater) }, modifier = minHeight, shape = buttonShape) {
            Text(stringResource(R.string.puzzle_fill), style = type.button)
        }
        OutlinedButton(
            onClick = { onAction(PuzzleAction.Undo) },
            enabled = state.canUndo,
            modifier = minHeight,
            shape = buttonShape,
        ) {
            Text(stringResource(R.string.puzzle_undo), style = type.button)
        }
        OutlinedButton(onClick = { onAction(PuzzleAction.Restart) }, modifier = minHeight, shape = buttonShape) {
            Text(stringResource(R.string.puzzle_restart), style = type.button)
        }
    }
}

/** Solved sheet: the stars light up one by one (together with reduced motion), then Next or Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SolvedSheet(
    state: PuzzleUiState,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextIsMission: Boolean = false,
    showNext: Boolean = true,
) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val reduced = rememberReducedMotion()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = shapes.sheet, topEnd = shapes.sheet),
        containerColor = colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.puzzle_solved_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            StarRow(stars = state.stars, reduced = reduced)
            Text(
                stringResource(R.string.puzzle_time, formatElapsed(state.elapsedMillis)),
                style = type.bodyText,
                color = colors.textMuted,
            )
            Text(
                stringResource(R.string.puzzle_aids, state.aidsUsed),
                style = type.bodyText,
                color = colors.textMuted,
            )
            if (showNext) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(shapes.button),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                ) {
                    Text(
                        stringResource(if (nextIsMission) R.string.puzzle_next_mission else R.string.puzzle_next),
                        style = type.button,
                    )
                }
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(shapes.button),
            ) {
                Text(stringResource(R.string.puzzle_back), style = type.button)
            }
        }
    }
}

@Composable
private fun StarRow(stars: Int, reduced: Boolean) {
    val colors = LocalFleetColors.current
    var lit by remember(stars) { mutableIntStateOf(if (reduced) stars else 0) }
    LaunchedEffect(stars, reduced) {
        if (!reduced) {
            lit = 0
            repeat(stars) {
                delay(STAR_STEP_MILLIS)
                lit++
            }
        }
    }
    val starsLabel = stringResource(R.string.puzzle_stars, stars)
    Row(
        Modifier.semantics { contentDescription = starsLabel },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (index in 0 until 3) {
            Text(
                text = "★",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = if (index < lit) colors.accent else colors.surface2,
            )
        }
    }
}

private const val STAR_STEP_MILLIS = 300L

/** The endless puzzles chooser: board size, grade, and the saved puzzle to resume. */
@Composable
fun PuzzleEntryDialog(
    onDismiss: () -> Unit,
    onStart: (PuzzleRequest) -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.from(context) }
    var size by rememberSaveable { mutableIntStateOf(6) }
    var grade by rememberSaveable { mutableStateOf(PuzzleGrade.EASY) }
    var savedSize by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        savedSize = runCatching {
            container.matches.activeMatch(MatchKind.ENDLESS, container.profile.load())
                ?.let(PuzzleSnapshotCodec::fromSavedMatch)
                ?.definition?.width
        }.getOrNull()
    }
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.puzzle_entry_title), style = type.cardTitle) },
        text = {
            // The default selected colour matches the dialog surface, so the choice would not show.
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = colors.accent,
                selectedLabelColor = colors.onAccent,
                labelColor = colors.text,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.puzzle_entry_size), style = type.label, color = colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 8, 10).forEach { option ->
                        FilterChip(
                            selected = option == size,
                            onClick = { size = option },
                            colors = chipColors,
                            label = { Text(stringResource(R.string.puzzle_entry_size_option, option, option), style = type.caption) },
                        )
                    }
                }
                Text(stringResource(R.string.puzzle_entry_grade), style = type.label, color = colors.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PuzzleGrade.entries.forEach { option ->
                        FilterChip(
                            selected = option == grade,
                            onClick = { grade = option },
                            colors = chipColors,
                            label = { Text(gradeLabel(option), style = type.caption) },
                        )
                    }
                }
                val saved = savedSize
                if (saved != null) {
                    OutlinedButton(onClick = { onStart(PuzzleRequest.Resume) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.puzzle_entry_resume, saved, saved), style = type.button)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onStart(PuzzleRequest.New(size, grade)) }) {
                Text(stringResource(R.string.puzzle_entry_start), style = type.button)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.puzzle_entry_cancel), style = type.button)
            }
        },
    )
}

@Composable
private fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    return stringResource(R.string.puzzle_clock, total / 60, total % 60)
}
