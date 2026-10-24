package pt.tiago.nightfleet.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import pt.tiago.nightfleet.R
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import pt.tiago.nightfleet.ui.tutorial.LocalTutorialAnchors
import pt.tiago.nightfleet.ui.tutorial.TutorialBounds

/** Long-press lens: the cell under the finger and the finger position (board px). */
internal data class LensState(val cell: Int, val x: Float, val y: Float)

/**
 * The board renderer: one Canvas for every cell and mark, with the coordinates, the theme's scenery and the
 * effects on top.
 *
 * - [themeId] picks the marks' shapes and the scenery; the colours come from `LocalFleetColors`.
 * - [effects] are played from the moment the list changes; their `startMillis` are relative to that moment.
 * - Tap: [onCellTap]. Long press: [onCellLongPress], or, when the cells are smaller than 26 dp, a 3×3 lens
 *   that stays above the finger, with the shot fired on release through [onCellTap].
 * - [interactive] false turns the board into a picture (the own sea).
 *
 * The board fills the width it is given and centres itself; it never draws outside its layout.
 */
@Composable
fun SeaBoard(
    model: BoardRenderModel,
    themeId: ThemeId,
    modifier: Modifier = Modifier,
    showCoordinates: Boolean = true,
    effects: List<EffectEvent> = emptyList(),
    interactive: Boolean = true,
    gapDp: Float = BOARD_GAP_DP,
    onCellTap: (Int) -> Unit = {},
    onCellLongPress: (Int) -> Unit = {},
    /** Tutorial anchors of squares of this board (`cell:D4`), registered with their window bounds when a tutorial runs. */
    tutorialCells: Set<String> = emptySet(),
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val style = remember(themeId) { boardStyle(themeId) }
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val availableW = with(density) { maxWidth.roundToPx() }
        val availableH = with(density) { maxHeight.roundToPx() }
        val layout = remember(model.width, model.height, availableW, availableH, showCoordinates, gapDp, model.recon.isNotEmpty(), density.density) {
            BoardMetrics.compute(
                availableWidthPx = availableW,
                availableHeightPx = availableH,
                cols = model.width,
                rows = model.height,
                density = density.density,
                showCoordinates = showCoordinates,
                gapDp = gapDp,
                reconMargin = model.recon.isNotEmpty(),
            )
        }
        val cache = remember(layout, colors, style) { BoardCache(layout, colors, style) }
        val hulls = remember(model.ships, model.width, layout) { buildHulls(model.ships, model.width, layout) }
        val covered = remember(model.ships) { coveredCells(model.ships) }
        val labels = if (showCoordinates) {
            val labelStyle = type.coordinate.copy(color = colors.textMuted)
            remember(model.width, model.height, labelStyle) {
                CoordinateLabels(
                    columns = List(model.width) { measurer.measure(('A' + it).toString(), labelStyle) },
                    rows = List(model.height) { measurer.measure((it + 1).toString(), labelStyle) },
                )
            }
        } else {
            null
        }
        val sonarStyle = TextStyle(fontFamily = type.data, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = colors.accent)
        val sonarTexts: List<TextLayoutResult> = remember(model.sonarReadings, sonarStyle) {
            model.sonarReadings.map { measurer.measure(it.count.toString(), sonarStyle) }
        }
        val prepared = remember(effects, layout) { prepareEffects(limitMineFlashes(effects), layout) }

        // Clock of the effects: starts at zero when the list of events changes, stops after the last one.
        val clock = remember { mutableLongStateOf(IDLE_CLOCK) }
        LaunchedEffect(prepared) {
            if (prepared.isEmpty()) return@LaunchedEffect
            val total = prepared.maxOf { it.event.startMillis + it.event.durationMillis + REDUCED_FADE_MS }
            clock.longValue = 0L
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val elapsed = (now - start) / 1_000_000L
                clock.longValue = elapsed
                if (elapsed > total) break
            }
        }

        val overlayPhase = remember { Animatable(0f) }
        LaunchedEffect(style.overlayPeriodMs, reduced) {
            if (style.overlayPeriodMs == 0 || reduced) {
                overlayPhase.snapTo(0f)
            } else {
                while (true) {
                    overlayPhase.animateTo(1f, tween(style.overlayPeriodMs, easing = LinearEasing))
                    overlayPhase.snapTo(0f)
                }
            }
        }

        val pulse = remember { Animatable(0f) }
        val pulsing = model.selected != null && !reduced
        LaunchedEffect(pulsing) {
            if (!pulsing) {
                pulse.snapTo(0f)
            } else {
                while (true) {
                    pulse.animateTo(1f, tween(600))
                    pulse.animateTo(0f, tween(600))
                }
            }
        }

        // The dashed 3×3 ring of a fresh sonar reading lasts 2 s; the number stays.
        val ringAlive = remember(model.sonarReadings) { mutableStateOf(model.sonarReadings.any { it.fresh }) }
        LaunchedEffect(model.sonarReadings) {
            if (ringAlive.value) {
                delay(SONAR_RING_MS)
                ringAlive.value = false
            }
        }

        var lens by remember { mutableStateOf<LensState?>(null) }
        val lensLayout = remember(density.density) { magnifierLayout(density.density) }
        val lensCache = remember(lensLayout, colors, style) { BoardCache(lensLayout, colors, style) }
        val widthDp: Dp = with(density) { layout.widthPx.toDp() }
        val heightDp: Dp = with(density) { layout.heightPx.toDp() }
        val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
        val gestures = if (interactive) {
            Modifier.pointerInput(layout, onCellTap, onCellLongPress, timeout) {
                boardGestures(
                    layout = layout,
                    longPressTimeoutMillis = timeout,
                    onTap = onCellTap,
                    onLongPress = onCellLongPress,
                    onLens = { lens = it },
                )
            }
        } else {
            Modifier
        }

        val tutorialAnchors = LocalTutorialAnchors.current
        val tutorialModifier = if (tutorialAnchors != null && tutorialCells.isNotEmpty()) {
            Modifier.onGloballyPositioned { coordinates ->
                val origin = coordinates.boundsInWindow().topLeft
                tutorialCells.forEach { id -> TutorialBounds.cellBounds(id, layout, origin)?.let { tutorialAnchors.put(id, it) } }
            }
        } else {
            Modifier
        }
        // One labelled node per square, laid over the canvas: TalkBack reads "D4, water" and can select a square.
        // The nodes have no pointer input, so touches still reach the canvas underneath.
        val squareTexts = squareDescriptions(model, enemy = interactive)
        val selectLabel = stringResource(R.string.board_action_select)
        val squareDp: Dp = with(density) { layout.cellPx.toDp() }
        Box(Modifier.size(widthDp, heightDp).then(tutorialModifier)) {
            Canvas(Modifier.size(widthDp, heightDp).then(gestures)) {
                drawBoard(
                    model = model,
                    layout = layout,
                    cache = cache,
                    hulls = hulls,
                    covered = covered,
                    labels = labels,
                    sonarTexts = sonarTexts,
                    ringVisible = ringAlive.value,
                    overlayPhase = overlayPhase.value,
                    pulse = pulse.value,
                    effects = prepared,
                    clockMs = clock.longValue,
                    reduced = reduced,
                )
            }
            for (index in model.cells.indices) {
                val text = squareTexts[index]
                val col = index % model.width
                val row = index / model.width
                Box(
                    Modifier
                        .offset { IntOffset(layout.cellX(col), layout.cellY(row)) }
                        .size(squareDp)
                        .semantics {
                            contentDescription = text
                            if (interactive) {
                                role = Role.Button
                                onClick(label = selectLabel, action = {
                                    onCellTap(index)
                                    true
                                })
                            }
                        },
                )
            }
            val current = lens
            if (current != null && layout.needsMagnifier) {
                Magnifier(
                    model = model,
                    cache = lensCache,
                    layoutSize = lensLayout,
                    centre = current.cell,
                    modifier = Modifier.offset {
                        val lensW = lensLayout.widthPx
                        val x = (current.x - lensW / 2f).toInt().coerceIn(0, (layout.widthPx - lensW).coerceAtLeast(0))
                        val y = (current.y - lensLayout.heightPx - LENS_GAP_DP * density.density).toInt().coerceAtLeast(0)
                        IntOffset(x, y)
                    },
                )
            }
        }
    }
}

/** Draws the whole board in one pass: backgrounds, hulls, marks, scenery, outlines, labels and effects. */
private fun DrawScope.drawBoard(
    model: BoardRenderModel,
    layout: BoardLayout,
    cache: BoardCache,
    hulls: List<HullPath>,
    covered: Set<Int>,
    labels: CoordinateLabels?,
    sonarTexts: List<TextLayoutResult>,
    ringVisible: Boolean,
    overlayPhase: Float,
    pulse: Float,
    effects: List<PreparedEffect>,
    clockMs: Long,
    reduced: Boolean,
) {
    val cols = model.width
    for (row in 0 until model.height) for (col in 0 until cols) {
        val index = row * cols + col
        drawSeaCell(model.cells[index], layout.cellX(col).toFloat(), layout.cellY(row).toFloat(), cache, index in covered)
    }
    drawHulls(hulls, cache)
    for (row in 0 until model.height) for (col in 0 until cols) {
        val index = row * cols + col
        drawCellMark(
            state = model.cells[index],
            x = layout.cellX(col).toFloat(),
            y = layout.cellY(row).toFloat(),
            c = cache,
            fade = model.fadedMisses[index] ?: 1f,
        )
    }
    drawOverlay(cache.style.overlay, overlayPhase, cache)
    for (index in model.highlight) {
        drawOutline(index, cache, cache.dashedStroke, alpha = 1f)
    }
    model.lastShot?.let { drawOutline(it, cache, cache.stroke2, alpha = 1f) }
    model.selected?.let { drawOutline(it, cache, cache.stroke2, alpha = 0.6f + 0.4f * pulse) }
    model.sonarReadings.forEachIndexed { k, reading ->
        val (x, y) = cellOrigin(reading.centerIndex, cache)
        if (reading.fresh && ringVisible) drawSonarRing(reading.centerIndex, cache)
        sonarTexts.getOrNull(k)?.let { text ->
            drawText(text, topLeft = Offset(x + (cache.cell - text.size.width) / 2f, y + (cache.cell - text.size.height) / 2f))
        }
    }
    drawReconMarks(model.recon, cache)
    labels?.let { drawCoordinates(it, cache) }
    drawEffects(effects, clockMs, reduced, cache)
}

/** Outline inside a cell (inset by half the stroke), used for the last shot, the selection and hints. */
private fun DrawScope.drawOutline(index: Int, c: BoardCache, style: DrawStyle, alpha: Float) {
    val (x, y) = cellOrigin(index, c)
    val half = if (style is Stroke) style.width / 2f else 0f
    drawRoundRect(
        color = c.colors.accent,
        topLeft = Offset(x + half, y + half),
        size = Size(c.cell - 2 * half, c.cell - 2 * half),
        cornerRadius = c.cellCorner,
        style = style,
        alpha = alpha,
    )
}

/** Dashed 3×3 ring around a sonar reading (clamped to the board edges). */
private fun DrawScope.drawSonarRing(centreIndex: Int, c: BoardCache) {
    val cols = c.layout.cols
    val rows = c.layout.rows
    val col = centreIndex % cols
    val row = centreIndex / cols
    val left = c.layout.cellX((col - 1).coerceAtLeast(0)).toFloat()
    val top = c.layout.cellY((row - 1).coerceAtLeast(0)).toFloat()
    val right = c.layout.cellX((col + 1).coerceAtMost(cols - 1)).toFloat() + c.cell
    val bottom = c.layout.cellY((row + 1).coerceAtMost(rows - 1)).toFloat() + c.cell
    drawRoundRect(
        color = c.colors.accent,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(c.cellCorner.x),
        style = c.dashedStroke,
    )
}

/** Gesture rules of the board. */
private suspend fun PointerInputScope.boardGestures(
    layout: BoardLayout,
    longPressTimeoutMillis: Long,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    onLens: (LensState?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        var released: PointerInputChange? = null
        val inTime = withTimeoutOrNull(longPressTimeoutMillis) {
            released = waitForUpOrCancellation()
            true
        } != null
        if (inTime) {
            released?.let { up -> layout.cellAt(up.position.x, up.position.y)?.let(onTap) }
            return@awaitEachGesture
        }
        val downCell = layout.cellAt(down.position.x, down.position.y)
        if (!layout.needsMagnifier) {
            downCell?.let(onLongPress)
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        // Magnifier: the lens follows the finger; the shot is taken on release.
        var pressed = true
        var lastCell = downCell
        var lastPos = down.position
        while (pressed) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            pressed = change.pressed
            lastPos = change.position
            lastCell = layout.cellAt(lastPos.x, lastPos.y)
            onLens(lastCell?.let { LensState(it, lastPos.x, lastPos.y) })
        }
        onLens(null)
        if (!pressed) lastCell?.let(onTap)
    }
}

/** Layout of the 3×3 lens: 44 dp cells with a 2 dp gap, no coordinates. */
internal fun magnifierLayout(density: Float): BoardLayout = BoardLayout(
    cols = 3,
    rows = 3,
    cellPx = BoardMetrics.dpToPx(LENS_CELL_DP, density),
    gapPx = BoardMetrics.dpToPx(BOARD_GAP_DP, density),
    labelPx = 0,
    marginPx = 0,
    density = density,
)

/**
 * 3×3 enlarged lens above the finger. It shows the marks of the cells around
 * [centre]; it does not draw hulls. Cells outside the board are drawn as sea.
 */
@Composable
internal fun Magnifier(
    model: BoardRenderModel,
    cache: BoardCache,
    layoutSize: BoardLayout,
    centre: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val shape = RoundedCornerShape(LocalFleetShapes.current.card)
    val size = with(LocalDensity.current) { layoutSize.widthPx.toDp() }
    Box(
        modifier
            .size(size)
            .background(colors.bg, shape)
            .border(1.dp, colors.accent, shape),
    ) {
        Canvas(Modifier.size(size)) {
            val col0 = centre % model.width
            val row0 = centre / model.width
            for (dr in -1..1) for (dc in -1..1) {
                val col = col0 + dc
                val row = row0 + dr
                val x = layoutSize.cellX(dc + 1).toFloat()
                val y = layoutSize.cellY(dr + 1).toFloat()
                val inside = col in 0 until model.width && row in 0 until model.height
                val state = if (inside) model.cells[row * model.width + col] else CellVisual.UNKNOWN
                drawSeaCell(state, x, y, cache, coveredByHull = false)
                drawCellMark(state, x, y, cache, fade = 1f)
            }
        }
    }
}

/** Board size of one mini board on the own sea: 122 dp wide, 1 dp gaps, no coordinates. */
@Composable
fun MiniBoard(
    model: BoardRenderModel,
    themeId: ThemeId,
    modifier: Modifier = Modifier,
) {
    SeaBoard(
        model = model,
        themeId = themeId,
        modifier = modifier.size(MINI_BOARD_DP.dp),
        showCoordinates = false,
        gapDp = 1f,
        interactive = false,
    )
}

/** Width of the own-sea board in the battle screen. */
const val MINI_BOARD_DP = 122f

private const val LENS_CELL_DP = 44f
private const val LENS_GAP_DP = 24f
private const val SONAR_RING_MS = 2000L
private const val IDLE_CLOCK = Long.MAX_VALUE / 4

/** TalkBack text of every square of [model], in row-major order: "D4, water" (see [BoardLabels]). */
@Composable
private fun squareDescriptions(model: BoardRenderModel, enemy: Boolean): List<String> {
    val words = BoardLabels.stateKeys.associateWith { stringResource(stateWordRes(it)) }
    return model.cells.mapIndexed { index, visual ->
        val coordinate = BoardLabels.coordinateOf(index, model.width)
        stringResource(R.string.board_cell_label, coordinate, words.getValue(BoardLabels.stateKey(visual, enemy)))
    }
}

private fun stateWordRes(key: String): Int = when (key) {
    "unknown" -> R.string.board_state_unknown
    "empty_sea" -> R.string.board_state_empty_sea
    "water" -> R.string.board_state_water
    "hit" -> R.string.board_state_hit
    "sunk" -> R.string.board_state_sunk
    "contact" -> R.string.board_state_contact
    "mine" -> R.string.board_state_mine
    "decoy" -> R.string.board_state_decoy
    "repaired" -> R.string.board_state_repaired
    "island" -> R.string.board_state_island
    "reef" -> R.string.board_state_reef
    "own_ship" -> R.string.board_state_own_ship
    "own_ship_hit" -> R.string.board_state_own_ship_hit
    "revealed_ship" -> R.string.board_state_revealed_ship
    else -> error("no state word for $key")
}
