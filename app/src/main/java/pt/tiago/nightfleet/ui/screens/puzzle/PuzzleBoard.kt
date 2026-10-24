package pt.tiago.nightfleet.ui.screens.puzzle

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.puzzle.Dir
import pt.tiago.nightfleet.core.puzzle.LineStatus
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleValidator
import pt.tiago.nightfleet.core.puzzle.SegmentShape
import pt.tiago.nightfleet.game.puzzle.PuzzleAction
import pt.tiago.nightfleet.game.puzzle.PuzzleReducer
import pt.tiago.nightfleet.game.puzzle.PuzzleUiState
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/** Width of the column of row counts and the height of the row of column counts. */
internal val PUZZLE_LABEL_DP: Dp = 32.dp

/** Space between the board and the row counts. */
internal val PUZZLE_LABEL_GAP_DP: Dp = 8.dp

/** Gap between squares. */
internal val PUZZLE_GAP_DP: Dp = 2.dp

/** Smallest square of a 10 by 10 board. Below this width the board scrolls sideways. */
internal val PUZZLE_MIN_CELL_DP: Dp = 32.dp

/**
 * The lighthouse grid: squares drawn on one Canvas, row counts at the right, column counts at the
 * bottom, one accessibility node per square and a gesture layer. Squares are whole pixels.
 */
@Composable
fun PuzzleBoard(
    state: PuzzleUiState,
    onAction: (PuzzleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val def = state.definition
    val grid = state.grid
    val density = LocalDensity.current
    val reduced = rememberReducedMotion()
    val latestAction by rememberUpdatedState(onAction)
    val hintAlpha = rememberHintPulse(reduced)
    var strokeCells by remember { mutableStateOf(emptySet<Int>()) }

    val markWaterLabel = stringResource(R.string.puzzle_action_water)
    val markShipLabel = stringResource(R.string.puzzle_action_ship)
    val clearLabel = stringResource(R.string.puzzle_action_clear)
    val boardLabel = stringResource(R.string.puzzle_board_label, def.width, def.height)
    val report = remember(grid) { PuzzleValidator.validate(def, grid) }
    val rowShips = remember(grid) { IntArray(def.height) { r -> (0 until def.width).count { grid[def.cellAt(r, it)] == PlayerCell.SHIP } } }
    val colShips = remember(grid) { IntArray(def.width) { c -> (0 until def.height).count { grid[def.cellAt(it, c)] == PlayerCell.SHIP } } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val labelPx = with(density) { PUZZLE_LABEL_DP.toPx() + PUZZLE_LABEL_GAP_DP.toPx() }
        val fitPx = kotlin.math.floor((widthPx - labelPx) / def.width).toInt()
        val minPx = kotlin.math.ceil(with(density) { PUZZLE_MIN_CELL_DP.toPx() }).toInt()
        val cellPx = maxOf(fitPx, minPx)
        val cellDp = with(density) { cellPx.toDp() }
        val boardWidth = with(density) { (cellPx * def.width).toDp() }
        val boardHeight = with(density) { (cellPx * def.height).toDp() }
        val scrollable = cellPx * def.width + labelPx > widthPx
        val scroll = rememberScrollState()

        val cellSize = cellPx.toFloat()
        val gapPx = with(density) { PUZZLE_GAP_DP.toPx() }

        Row(
            Modifier.then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier),
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Box(
                    Modifier
                        .size(boardWidth, boardHeight)
                        .semantics { contentDescription = boardLabel },
                ) {
                    Canvas(Modifier.size(boardWidth, boardHeight)) {
                        for (cell in 0 until def.cellCount) {
                            val x = def.col(cell) * cellSize
                            val y = def.row(cell) * cellSize
                            drawSquare(
                                x = x,
                                y = y,
                                size = cellSize,
                                gap = gapPx,
                                mark = grid[cell],
                                shape = grid.segmentShape(cell),
                                sides = shipSides(def, grid, cell),
                                island = PuzzleReducer.isLocked(def, cell) && cell in def.islands,
                                given = cell in def.givens,
                                error = cell in state.errorCells,
                                hint = state.lastHint?.cell == cell,
                                pending = cell in strokeCells,
                                hintAlpha = hintAlpha,
                                colors = colors,
                            )
                        }
                    }
                    // Accessibility: one node per square, with the row and column status in its label.
                    Box(Modifier.matchParentSize()) {
                        for (cell in 0 until def.cellCount) {
                            val row = def.row(cell)
                            val col = def.col(cell)
                            val label = cellLabel(state, cell, rowShips[row], colShips[col], report.rowStatus[row], report.colStatus[col])
                            Box(
                                Modifier
                                    .offset { IntOffset(col * cellPx, row * cellPx) }
                                    .size(cellDp)
                                    .semantics {
                                        contentDescription = label
                                        customActions = listOf(
                                            CustomAccessibilityAction(markWaterLabel) {
                                                latestAction(PuzzleAction.Mark(cell, PlayerCell.WATER)); true
                                            },
                                            CustomAccessibilityAction(markShipLabel) {
                                                latestAction(PuzzleAction.Mark(cell, PlayerCell.SHIP)); true
                                            },
                                            CustomAccessibilityAction(clearLabel) {
                                                latestAction(PuzzleAction.Mark(cell, PlayerCell.EMPTY)); true
                                            },
                                        )
                                    },
                            )
                        }
                    }
                    // Gestures: a tap cycles the square, a long press starts a brush stroke that paints water.
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(cellPx, def.width, def.height) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val start = cellIndex(down.position, cellPx, def.width, def.height)
                                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        awaitTapOrMove(down.id, down.position, viewConfiguration.touchSlop)
                                    } ?: GestureOutcome.LONG_PRESS
                                    when (outcome) {
                                        GestureOutcome.TAP -> if (start >= 0) latestAction(PuzzleAction.Tap(start))
                                        GestureOutcome.MOVED -> Unit
                                        GestureOutcome.LONG_PRESS -> {
                                            val painted = LinkedHashSet<Int>()
                                            if (start >= 0) painted.add(start)
                                            strokeCells = painted.toSet()
                                            awaitStroke(down.id) { position ->
                                                val cell = cellIndex(position, cellPx, def.width, def.height)
                                                if (cell >= 0 && painted.add(cell)) strokeCells = painted.toSet()
                                            }
                                            strokeCells = emptySet()
                                            latestAction(PuzzleAction.PaintWater(painted.toList()))
                                        }
                                    }
                                }
                            },
                    )
                }
                // Column counts under the board.
                Row(Modifier.width(boardWidth)) {
                    for (col in 0 until def.width) {
                        Box(Modifier.width(cellDp).height(PUZZLE_LABEL_DP), contentAlignment = Alignment.Center) {
                            CountText(
                                value = def.colCounts[col],
                                status = report.colStatus[col],
                                colors = colors,
                                style = countStyle(type.data),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(PUZZLE_LABEL_GAP_DP))
            // Row counts at the right.
            Column {
                for (row in 0 until def.height) {
                    Box(Modifier.width(PUZZLE_LABEL_DP).height(cellDp), contentAlignment = Alignment.Center) {
                        CountText(
                            value = def.rowCounts[row],
                            status = report.rowStatus[row],
                            colors = colors,
                            style = countStyle(type.data),
                        )
                    }
                }
            }
        }
    }
}

/** The legend squares: the same drawing as the board, one square each. */
@Composable
fun PuzzleLegendSquare(mark: PlayerCell, shape: SegmentShape?, modifier: Modifier = Modifier) {
    val colors = LocalFleetColors.current
    val gap = with(LocalDensity.current) { PUZZLE_GAP_DP.toPx() }
    Canvas(modifier.size(LEGEND_SQUARE_DP)) {
        drawSquare(
            x = 0f,
            y = 0f,
            size = this.size.width,
            gap = gap,
            mark = mark,
            shape = shape,
            sides = BooleanArray(4),
            island = false,
            given = false,
            error = false,
            hint = false,
            pending = false,
            hintAlpha = 1f,
            colors = colors,
        )
    }
}

private val LEGEND_SQUARE_DP: Dp = 28.dp

private enum class GestureOutcome { TAP, MOVED, LONG_PRESS }

/** Waits for the finger to lift (a tap) or to leave the touch slop (a move). */
private suspend fun AwaitPointerEventScope.awaitTapOrMove(id: PointerId, origin: Offset, slop: Float): GestureOutcome {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: continue
        if (!change.pressed) return GestureOutcome.TAP
        if ((change.position - origin).getDistance() > slop) return GestureOutcome.MOVED
    }
}

/** Follows the pointer until it lifts, reporting every position and consuming the changes. */
private suspend fun AwaitPointerEventScope.awaitStroke(id: PointerId, onPosition: (Offset) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: continue
        if (!change.pressed) return
        onPosition(change.position)
        change.consume()
    }
}

private fun cellIndex(position: Offset, cellPx: Int, width: Int, height: Int): Int {
    val col = kotlin.math.floor(position.x / cellPx).toInt()
    val row = kotlin.math.floor(position.y / cellPx).toInt()
    return if (col in 0 until width && row in 0 until height) row * width + col else -1
}

/** Neighbours that are ship squares, indexed by [Dir] (N, E, S, W). */
private fun shipSides(def: pt.tiago.nightfleet.core.puzzle.PuzzleDefinition, grid: PlayerGrid, cell: Int): BooleanArray =
    BooleanArray(4) { dir ->
        val nb = def.neighbour(cell, dir)
        nb >= 0 && grid[nb] == PlayerCell.SHIP
    }

/**
 * Draws one square. Sea is the background, the grid line is drawn inside the square, ship hulls join with
 * their ship neighbours and round only at their ends, given squares carry a small accent dot.
 */
private fun DrawScope.drawSquare(
    x: Float,
    y: Float,
    size: Float,
    gap: Float,
    mark: PlayerCell,
    shape: SegmentShape?,
    sides: BooleanArray,
    island: Boolean,
    given: Boolean,
    error: Boolean,
    hint: Boolean,
    pending: Boolean,
    hintAlpha: Float,
    colors: FleetColors,
) {
    val half = gap / 2f
    val stroke = 1.dp.toPx()
    val thick = 2.dp.toPx()

    if (island) {
        drawRect(colors.island, topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap))
        val dot = size * 0.07f
        val dotColor = colors.line.copy(alpha = 0.7f)
        for ((fx, fy) in listOf(0.3f to 0.32f, 0.68f to 0.5f, 0.42f to 0.74f)) {
            drawCircle(dotColor, radius = dot, center = Offset(x + size * fx, y + size * fy))
        }
        return
    }

    if (mark == PlayerCell.SHIP) {
        val left = x + if (sides[Dir.WEST]) 0f else half
        val top = y + if (sides[Dir.NORTH]) 0f else half
        val right = x + size - if (sides[Dir.EAST]) 0f else half
        val bottom = y + size - if (sides[Dir.SOUTH]) 0f else half
        val radius = size * 0.38f
        val corners = roundedCorners(shape)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(left, top, right, bottom),
                    topLeft = CornerRadius(if (corners[0]) radius else 0f),
                    topRight = CornerRadius(if (corners[1]) radius else 0f),
                    bottomRight = CornerRadius(if (corners[2]) radius else 0f),
                    bottomLeft = CornerRadius(if (corners[3]) radius else 0f),
                ),
            )
        }
        drawPath(path, colors.ship)
        drawPath(path, colors.shipEdge, style = Stroke(width = stroke))
    } else {
        drawRect(colors.sea, topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap))
        drawRect(colors.grid, topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap), style = Stroke(width = stroke))
    }

    if (mark == PlayerCell.WATER) {
        val alpha = if (pending) 0.5f else 1f
        drawCircle(colors.miss.copy(alpha = alpha), radius = size * 0.26f, center = Offset(x + size / 2f, y + size / 2f), style = Stroke(width = thick))
    }

    if (given) {
        drawCircle(colors.accent, radius = size * 0.09f, center = Offset(x + size * 0.22f, y + size * 0.22f))
    }
    if (error) {
        drawRect(colors.danger, topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap), style = Stroke(width = thick))
    }
    if (hint) {
        drawRect(colors.accent.copy(alpha = 0.18f * hintAlpha), topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap))
        drawRect(colors.accent.copy(alpha = hintAlpha), topLeft = Offset(x + half, y + half), size = Size(size - gap, size - gap), style = Stroke(width = thick))
    }
}

/** Corners that round for a ship end: TL, TR, BR, BL. A single square rounds all four, middles none. */
private fun roundedCorners(shape: SegmentShape?): BooleanArray = when (shape) {
    SegmentShape.SINGLE -> booleanArrayOf(true, true, true, true)
    SegmentShape.END_N -> booleanArrayOf(true, true, false, false)
    SegmentShape.END_E -> booleanArrayOf(false, true, true, false)
    SegmentShape.END_S -> booleanArrayOf(false, false, true, true)
    SegmentShape.END_W -> booleanArrayOf(true, false, false, true)
    else -> booleanArrayOf(false, false, false, false)
}

@Composable
private fun CountText(value: Int, status: LineStatus, colors: FleetColors, style: TextStyle) {
    val color = when (status) {
        LineStatus.OK -> colors.accent
        LineStatus.OVER -> colors.danger
        LineStatus.UNDER -> colors.text
    }
    Text(
        text = value.toString(),
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private fun countStyle(family: androidx.compose.ui.text.font.FontFamily): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 18.sp,
)

@Composable
private fun cellLabel(
    state: PuzzleUiState,
    cell: Int,
    rowShips: Int,
    colShips: Int,
    rowStatus: LineStatus,
    colStatus: LineStatus,
): String {
    val def = state.definition
    val row = def.row(cell)
    val col = def.col(cell)
    val isIsland = cell in def.islands
    val stateWord = when {
        isIsland -> stringResource(R.string.puzzle_cell_island)
        state.grid[cell] == PlayerCell.EMPTY -> stringResource(R.string.puzzle_cell_empty)
        state.grid[cell] == PlayerCell.WATER -> stringResource(R.string.puzzle_cell_water)
        else -> stringResource(R.string.puzzle_cell_ship)
    }
    val fixed = if (cell in def.givens) stringResource(R.string.puzzle_cell_fixed) else ""
    val rowText = lineStatusText(
        status = rowStatus,
        diff = def.rowCounts[row] - rowShips,
        needs = R.plurals.puzzle_row_needs,
        complete = R.string.puzzle_row_complete,
        over = R.plurals.puzzle_row_over,
    )
    val colText = lineStatusText(
        status = colStatus,
        diff = def.colCounts[col] - colShips,
        needs = R.plurals.puzzle_col_needs,
        complete = R.string.puzzle_col_complete,
        over = R.plurals.puzzle_col_over,
    )
    return stringResource(R.string.puzzle_cell, row + 1, col + 1, stateWord, fixed, rowText, colText)
}

@Composable
private fun lineStatusText(status: LineStatus, diff: Int, needs: Int, complete: Int, over: Int): String = when (status) {
    LineStatus.OK -> stringResource(complete)
    LineStatus.UNDER -> pluralStringResource(needs, diff, diff)
    LineStatus.OVER -> pluralStringResource(over, -diff, -diff)
}

/** Hint pulse: a slow alpha swing, or steady when the system asks for reduced motion. */
@Composable
internal fun rememberHintPulse(reduced: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "hint pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 700), repeatMode = RepeatMode.Reverse),
        label = "hint pulse",
    )
    return if (reduced) 1f else pulse
}

/** True when the system has animations turned off (the "remove animations" accessibility setting). */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
