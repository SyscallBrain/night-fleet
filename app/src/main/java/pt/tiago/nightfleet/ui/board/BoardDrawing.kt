package pt.tiago.nightfleet.ui.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.ThemeId
import kotlin.math.cos
import kotlin.math.sin

/**
 * Paths, strokes and brushes of one board size and theme. Built once per (layout, colours, style) in
 * `remember`, so the draw phase allocates almost nothing. Paths are in cell-local px: the cell's
 * top-left corner is (0, 0) and its side is [cell].
 */
internal class BoardCache(
    val layout: BoardLayout,
    val colors: FleetColors,
    val style: BoardStyle,
) {
    val density: Float = layout.density
    val cell: Float = layout.cellPx.toFloat()
    val gap: Float = layout.gapPx.toFloat()

    /** Corner radius of a cell: the theme's cell radius. */
    val cellCorner: CornerRadius = CornerRadius(cellRadiusDp(style.theme) * density)

    val hairline: Stroke = Stroke(width = dp(1f))
    val stroke15: Stroke = Stroke(width = dp(1.5f))
    val stroke2: Stroke = Stroke(width = dp(2f))
    val dashedStroke: Stroke = Stroke(width = dp(1.5f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dp(4f), dp(3f))))
    val rim: Color = lerp(colors.hit, Color.Black, 0.35f)

    /** Hit shape of themes C and D (star and diamond). Theme B uses a circle and theme A a square. */
    val hitPath: Path? = when (style.hitShape) {
        HitShape.STAR -> polygon(STAR_POINTS)
        HitShape.DIAMOND -> polygon(DIAMOND_POINTS)
        else -> null
    }

    /** Four spikes of a mine. */
    val minePath: Path = buildPath {
        for ((dx, dy) in listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)) {
            moveTo(cell * (0.5f + dx * 0.2f), cell * (0.5f + dy * 0.2f))
            lineTo(cell * (0.5f + dx * 0.36f), cell * (0.5f + dy * 0.36f))
        }
    }

    /** The X of a repair patch. */
    val patchPath: Path = buildPath {
        moveTo(cell * 0.3f, cell * 0.3f)
        lineTo(cell * 0.7f, cell * 0.7f)
        moveTo(cell * 0.7f, cell * 0.3f)
        lineTo(cell * 0.3f, cell * 0.7f)
    }

    /** 45-degree hatch of theme A, clipped to the cell by construction. */
    val hatchPath: Path = buildPath {
        for (k in listOf(0.4f, 0.8f, 1.2f, 1.6f)) {
            moveTo(cell * maxOf(0f, k - 1f), cell * minOf(k, 1f))
            lineTo(cell * minOf(k, 1f), cell * maxOf(0f, k - 1f))
        }
    }

    /** Reef: three wavy lines. */
    val reefPath: Path = buildPath {
        for (y in listOf(0.28f, 0.5f, 0.72f)) {
            moveTo(cell * 0.12f, cell * y)
            cubicTo(cell * 0.3f, cell * (y - 0.1f), cell * 0.45f, cell * (y + 0.1f), cell * 0.5f, cell * y)
            cubicTo(cell * 0.55f, cell * (y - 0.1f), cell * 0.7f, cell * (y + 0.1f), cell * 0.88f, cell * y)
        }
    }

    /** Double wave of a submerged contact. */
    val contactPath: Path = buildPath {
        for (y in listOf(0.4f, 0.64f)) {
            moveTo(cell * 0.2f, cell * y)
            cubicTo(cell * 0.35f, cell * (y - 0.14f), cell * 0.65f, cell * (y - 0.14f), cell * 0.8f, cell * y)
        }
    }

    /** Reflection of the moon in theme C (top right of the board). */
    val moonBrush: Brush = Brush.radialGradient(
        colors = listOf(colors.text.copy(alpha = 0.2f), Color.Transparent),
        center = Offset(layout.widthPx * 0.78f, layout.heightPx * 0.14f),
        radius = layout.widthPx * 0.3f,
    )

    fun dp(value: Float): Float = value * density

    private fun polygon(points: List<Pair<Float, Float>>): Path = buildPath {
        points.forEachIndexed { i, (x, y) ->
            // The mockup clip-paths span the whole cell; the marks use 66 % of it, centred.
            val px = cell * (0.5f + (x / 100f - 0.5f) * 0.66f)
            val py = cell * (0.5f + (y / 100f - 0.5f) * 0.66f)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }

    private companion object {
        /** Cell corner radius per theme, in dp. */
        fun cellRadiusDp(theme: ThemeId): Float = when (theme) {
            ThemeId.CHART -> 1f
            ThemeId.SONAR -> 2f
            ThemeId.PAPER -> 7f
            ThemeId.HOLO -> 2f
        }
    }
}

private inline fun buildPath(block: Path.() -> Unit): Path = Path().apply(block)

/** A ship's hull as a path in board px, with the flags of its [ShipOutline]. */
internal class HullPath(val path: Path, val sunk: Boolean, val revealed: Boolean)

/** Cell states that belong to a ship, so they get no separate background when a hull covers them. */
internal fun CellVisual.isShipState(): Boolean = when (this) {
    CellVisual.SUNK, CellVisual.OWN_SHIP, CellVisual.OWN_SHIP_HIT, CellVisual.REVEALED_SHIP -> true
    else -> false
}

/**
 * Builds the hull paths of [ships] on a board of [cols] columns, in board px. Geometry is in cell units
 * and maps to px as `labelPx + u * pitch - gap / 2`, so a hull spans its cells and the gaps between them.
 */
internal fun buildHulls(ships: List<ShipOutline>, cols: Int, layout: BoardLayout): List<HullPath> {
    fun map(u: Float): Float = layout.labelPx + u * layout.pitch - layout.gapPx / 2f
    return ships.map { ship ->
        val path = Path()
        for (command in ShipGeometry.outline(ship.cells, cols)) {
            when (command) {
                is PathCommand.MoveTo -> path.moveTo(map(command.x), map(command.y))
                is PathCommand.LineTo -> path.lineTo(map(command.x), map(command.y))
                is PathCommand.CubicTo -> path.cubicTo(
                    map(command.x1), map(command.y1),
                    map(command.x2), map(command.y2),
                    map(command.x), map(command.y),
                )
                PathCommand.Close -> path.close()
            }
        }
        HullPath(path, ship.sunk, ship.revealed)
    }
}

/** Index of the cells covered by any hull, so the fallback background is skipped there. */
internal fun coveredCells(ships: List<ShipOutline>): Set<Int> = buildSet {
    for (ship in ships) addAll(ship.cells)
}

/** Background of one cell: sea, or land for islands, plus the 1 dp inner grid line. */
internal fun DrawScope.drawSeaCell(
    state: CellVisual,
    x: Float,
    y: Float,
    c: BoardCache,
    coveredByHull: Boolean,
) {
    val topLeft = Offset(x, y)
    val size = Size(c.cell, c.cell)
    if (state == CellVisual.ISLAND) {
        drawRoundRect(c.colors.island, topLeft, size, c.cellCorner)
        return
    }
    drawRoundRect(c.colors.sea, topLeft, size, c.cellCorner)
    if (state.isShipState() && !coveredByHull) {
        // A ship cell without an outline: a plain inset hull cell, so it is never invisible.
        val inset = c.cell * 0.08f
        drawRoundRect(
            color = c.colors.ship,
            topLeft = Offset(x + inset, y + inset),
            size = Size(c.cell - 2 * inset, c.cell - 2 * inset),
            cornerRadius = c.cellCorner,
            alpha = if (state == CellVisual.REVEALED_SHIP) 0.6f else 1f,
        )
    }
    val half = c.hairline.width / 2f
    drawRoundRect(
        color = c.colors.grid,
        topLeft = Offset(x + half, y + half),
        size = Size(c.cell - 2 * half, c.cell - 2 * half),
        cornerRadius = c.cellCorner,
        style = c.hairline,
    )
}

/** Draws every hull in the theme's hull style. */
internal fun DrawScope.drawHulls(hulls: List<HullPath>, c: BoardCache) {
    for (hull in hulls) {
        val alpha = if (hull.revealed) 0.6f else 1f
        if (c.style.hull == HullStyle.LIGHT) {
            drawPath(hull.path, c.colors.shipEdge, alpha = 0.25f * alpha, style = Stroke(c.dp(3f)))
        }
        if (c.style.hull != HullStyle.LINE) {
            drawPath(hull.path, c.colors.ship, alpha = alpha)
        }
        when {
            hull.revealed -> drawPath(hull.path, c.colors.shipEdge, alpha = 0.6f, style = c.dashedStroke)
            c.style.hull == HullStyle.LINE -> drawPath(hull.path, c.colors.shipEdge, style = c.stroke2)
            else -> drawPath(hull.path, c.colors.shipEdge, style = c.stroke15)
        }
    }
}

/** Mark of one cell. [fade] multiplies the alpha of water marks (fog). */
internal fun DrawScope.drawCellMark(
    state: CellVisual,
    x: Float,
    y: Float,
    c: BoardCache,
    fade: Float,
) {
    val cell = c.cell
    val center = Offset(x + cell / 2f, y + cell / 2f)
    when (state) {
        CellVisual.WATER -> drawCircle(
            color = c.colors.miss,
            radius = cell * 0.18f,
            center = center,
            alpha = 0.85f * fade,
            style = c.stroke2,
        )
        CellVisual.AUTO_WATER -> drawCircle(
            color = c.colors.miss,
            radius = cell * 0.08f,
            center = center,
            alpha = 0.45f * fade,
        )
        CellVisual.HIT -> {
            drawRoundRect(c.colors.hit, Offset(x, y), Size(cell, cell), c.cellCorner, alpha = 0.16f)
            drawHitShape(x, y, c, 1f)
        }
        CellVisual.SUNK, CellVisual.OWN_SHIP_HIT -> drawHitShape(x, y, c, 0.8f)
        CellVisual.CONTACT -> translate(x, y) {
            drawPath(c.contactPath, c.colors.accent, style = c.stroke15)
        }
        CellVisual.MINE -> {
            drawCircle(c.colors.danger, cell * 0.16f, center)
            translate(x, y) { drawPath(c.minePath, c.colors.danger, style = c.stroke15) }
        }
        CellVisual.DECOY_WRECK -> drawRoundRect(
            color = c.colors.miss,
            topLeft = Offset(x + cell * 0.2f, y + cell * 0.2f),
            size = Size(cell * 0.6f, cell * 0.6f),
            cornerRadius = CornerRadius(cell * 0.1f),
            style = c.dashedStroke,
        )
        CellVisual.REPAIRED -> {
            drawRoundRect(
                color = c.colors.accent,
                topLeft = Offset(x + cell * 0.3f, y + cell * 0.3f),
                size = Size(cell * 0.4f, cell * 0.4f),
                cornerRadius = CornerRadius(cell * 0.06f),
                alpha = 0.45f,
            )
            translate(x, y) { drawPath(c.patchPath, c.colors.accent, style = c.stroke15) }
        }
        CellVisual.ISLAND -> drawIslandPattern(x, y, c)
        CellVisual.REEF -> translate(x, y) { drawPath(c.reefPath, c.colors.line, style = c.hairline) }
        CellVisual.UNKNOWN, CellVisual.OWN_SHIP, CellVisual.REVEALED_SHIP -> Unit
    }
}

/** The theme's hit shape, filled with `hit`. [alpha] is 0.8 for sunk hulls and damaged own ships. */
private fun DrawScope.drawHitShape(x: Float, y: Float, c: BoardCache, alpha: Float) {
    val cell = c.cell
    when (c.style.hitShape) {
        HitShape.SEAL -> {
            val center = Offset(x + cell / 2f, y + cell / 2f)
            val radius = cell * 0.31f
            drawCircle(c.colors.hit, radius, center, alpha = alpha)
            drawCircle(c.rim, radius, center, alpha = alpha, style = c.stroke15)
            drawCircle(Color.White, cell * 0.07f, Offset(center.x - cell * 0.1f, center.y - cell * 0.1f), alpha = 0.45f * alpha)
        }
        HitShape.ECHO -> {
            drawRect(c.colors.hit, Offset(x + cell * 0.22f, y + cell * 0.22f), Size(cell * 0.56f, cell * 0.56f), alpha = alpha)
            drawRect(
                c.colors.hit,
                Offset(x + cell * 0.16f, y + cell * 0.16f),
                Size(cell * 0.68f, cell * 0.68f),
                alpha = 0.35f * alpha,
                style = c.hairline,
            )
        }
        HitShape.STAR, HitShape.DIAMOND -> {
            val path = c.hitPath ?: return
            translate(x, y) {
                if (c.style.hitShape == HitShape.DIAMOND) {
                    drawPath(path, c.colors.hit, alpha = 0.35f * alpha, style = c.stroke2)
                }
                drawPath(path, c.colors.hit, alpha = alpha)
            }
        }
    }
}

private fun DrawScope.drawIslandPattern(x: Float, y: Float, c: BoardCache) {
    val cell = c.cell
    when (c.style.island) {
        IslandPattern.DOTS -> for (fx in listOf(0.25f, 0.75f)) for (fy in listOf(0.25f, 0.75f)) {
            drawCircle(c.colors.text, cell * 0.05f, Offset(x + cell * fx, y + cell * fy), alpha = 0.22f)
        }
        IslandPattern.HATCH -> translate(x, y) { drawPath(c.hatchPath, c.colors.text, alpha = 0.15f, style = c.hairline) }
        IslandPattern.TUFTS -> for ((fx, fy) in listOf(0.3f to 0.35f, 0.68f to 0.6f, 0.4f to 0.75f)) {
            drawCircle(c.colors.text, cell * 0.12f, Offset(x + cell * fx, y + cell * fy), alpha = 0.18f)
        }
        IslandPattern.LINES -> for (fy in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
            drawLine(c.colors.text, Offset(x, y + cell * fy), Offset(x + cell, y + cell * fy), c.hairline.width, alpha = 0.2f)
        }
    }
}

/**
 * Scenery layer over the cells. [phase] is 0..1 over one
 * period of the overlay animation. Every stroke stays at alpha 0.2 or less.
 */
internal fun DrawScope.drawOverlay(kind: OverlayKind, phase: Float, c: BoardCache) {
    val layout = c.layout
    val left = layout.labelPx.toFloat()
    val top = layout.labelPx.toFloat()
    val right = left + layout.cols * layout.pitch - c.gap
    val bottom = top + layout.rows * layout.pitch - c.gap
    val centre = Offset((left + right) / 2f, (top + bottom) / 2f)
    when (kind) {
        OverlayKind.COMPASS_ROSE -> {
            // A rose in the bottom-right corner and two rhumb lines across the sea.
            val rose = Offset(right - c.cell * 1.2f, bottom - c.cell * 1.2f)
            drawCircle(c.colors.accent, c.cell * 1.1f, rose, alpha = 0.2f, style = c.hairline)
            for (i in 0 until 8) {
                val angle = i * ROSE_STEP
                val dx = cos(angle)
                val dy = sin(angle)
                drawLine(
                    c.colors.accent,
                    Offset(rose.x + dx * c.cell * 0.4f, rose.y + dy * c.cell * 0.4f),
                    Offset(rose.x + dx * c.cell * 1.1f, rose.y + dy * c.cell * 1.1f),
                    c.hairline.width,
                    alpha = 0.2f,
                )
            }
            drawLine(c.colors.accent, Offset(left, top), Offset(right, bottom), c.hairline.width, alpha = 0.12f)
            drawLine(c.colors.accent, Offset(left, bottom), Offset(right, top), c.hairline.width, alpha = 0.12f)
        }
        OverlayKind.SONAR_SWEEP -> {
            val radius = maxOf(right - left, bottom - top)
            drawArc(
                c.colors.accent,
                startAngle = phase * 360f - 60f,
                sweepAngle = 60f,
                useCenter = true,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2, radius * 2),
                alpha = 0.12f,
            )
            for (fraction in listOf(0.33f, 0.66f)) {
                drawCircle(c.colors.accent, radius * fraction, centre, alpha = 0.12f, style = c.hairline)
            }
        }
        OverlayKind.MOON_REFLECTION -> drawRect(c.moonBrush, Offset.Zero, Size(layout.widthPx.toFloat(), layout.heightPx.toFloat()))
        OverlayKind.SCAN_LINE -> {
            val y = top + (bottom - top) * phase
            drawLine(c.colors.accent, Offset(left, y), Offset(right, y), c.dp(6f), alpha = 0.12f)
        }
    }
}

private const val ROSE_STEP = 0.7853982f

/** Recon marks in the margin: a check (accent) or a cross (muted), drawn as two strokes each. */
internal fun DrawScope.drawReconMarks(marks: List<ReconMark>, c: BoardCache) {
    val layout = c.layout
    val glyph = minOf(layout.marginPx * 0.5f, c.cell * 0.4f)
    for (mark in marks) {
        val cx: Float
        val cy: Float
        if (mark.isRow) {
            cx = layout.cellX(layout.cols - 1) + c.cell + layout.marginPx / 2f
            cy = layout.cellY(mark.index) + c.cell / 2f
        } else {
            cx = layout.cellX(mark.index) + c.cell / 2f
            cy = layout.cellY(layout.rows - 1) + c.cell + layout.marginPx / 2f
        }
        val half = glyph / 2f
        if (mark.present) {
            val color = c.colors.accent
            drawLine(color, Offset(cx - half, cy), Offset(cx - half * 0.2f, cy + half * 0.8f), c.stroke2.width)
            drawLine(color, Offset(cx - half * 0.2f, cy + half * 0.8f), Offset(cx + half, cy - half * 0.8f), c.stroke2.width)
        } else {
            val color = c.colors.textMuted
            drawLine(color, Offset(cx - half, cy - half), Offset(cx + half, cy + half), c.stroke2.width)
            drawLine(color, Offset(cx + half, cy - half), Offset(cx - half, cy + half), c.stroke2.width)
        }
    }
}

/** Coordinate labels, measured once in `remember`: letters over the columns, numbers left of the rows. */
internal class CoordinateLabels(
    val columns: List<TextLayoutResult>,
    val rows: List<TextLayoutResult>,
)

/** Draws the coordinate labels centred in the strips reserved by [BoardLayout]. */
internal fun DrawScope.drawCoordinates(labels: CoordinateLabels, c: BoardCache) {
    val layout = c.layout
    labels.columns.forEachIndexed { col, text ->
        val x = layout.cellX(col) + (c.cell - text.size.width) / 2f
        val y = (layout.labelPx - text.size.height) / 2f
        drawText(text, topLeft = Offset(x, y))
    }
    labels.rows.forEachIndexed { row, text ->
        val x = (layout.labelPx - text.size.width) / 2f
        val y = layout.cellY(row) + (c.cell - text.size.height) / 2f
        drawText(text, topLeft = Offset(x, y))
    }
}
