package pt.tiago.nightfleet.ui.board

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Duration of the fade that replaces every effect with reduced motion. */
internal const val REDUCED_FADE_MS = 150L

/**
 * An effect with its random data computed once: particles, smoke, and the hull path of a sinking ship.
 * Built in `remember(events, layout)`, never in the draw phase.
 */
internal class PreparedEffect(
    val event: EffectEvent,
    val particles: List<Particle>,
    val smoke: List<SmokePuff>,
    val hull: Path?,
    val hullCentre: Offset,
    val hullHeight: Float,
)

internal fun prepareEffects(events: List<EffectEvent>, layout: BoardLayout): List<PreparedEffect> =
    events.map { event ->
        val explosion = event.kind == EffectKind.EXPLOSION
        var hull: Path? = null
        var centre = Offset.Zero
        var height = 0f
        if (event.kind == EffectKind.SINK) {
            hull = buildHulls(listOf(ShipOutline(event.cells, sunk = true)), layout.cols, layout).first().path
            val rows = event.cells.map { it / layout.cols }
            val cols = event.cells.map { it % layout.cols }
            val left = layout.cellX(cols.min())
            val right = layout.cellX(cols.max()) + layout.cellPx
            val top = layout.cellY(rows.min())
            val bottom = layout.cellY(rows.max()) + layout.cellPx
            centre = Offset((left + right) / 2f, (top + bottom) / 2f)
            height = (bottom - top).toFloat()
        }
        PreparedEffect(
            event = event,
            particles = if (explosion) EffectParticles.explosion(event.seed) else emptyList(),
            smoke = if (explosion) EffectParticles.smoke(event.seed) else emptyList(),
            hull = hull,
            hullCentre = centre,
            hullHeight = height,
        )
    }

/**
 * Draws the effects that are running at [clockMs]. Effects that have not started yet or have finished are
 * skipped. With [reduced] every effect is a fade of at most [REDUCED_FADE_MS].
 */
internal fun DrawScope.drawEffects(
    effects: List<PreparedEffect>,
    clockMs: Long,
    reduced: Boolean,
    c: BoardCache,
) {
    for (effect in effects) {
        val t = clockMs - effect.event.startMillis
        if (t < 0 || t > effect.event.durationMillis + REDUCED_FADE_MS) continue
        if (reduced) drawReduced(effect, t, c) else drawFull(effect, t, c)
    }
}

private fun DrawScope.drawFull(effect: PreparedEffect, t: Long, c: BoardCache) {
    val event = effect.event
    when (event.kind) {
        EffectKind.SPLASH -> {
            val centre = cellCentre(event.cells.first(), c)
            val p = progress(t, EffectEvent.SPLASH_MS)
            drawCircle(c.colors.miss, c.cell * (0.2f + 0.3f * p), centre, alpha = (1f - p), style = c.stroke2)
            val inner = progress(t - 120, 300)
            val outer = progress(t - 60, 360)
            drawCircle(c.colors.miss, c.cell * (0.5f + 1.5f * inner), centre, alpha = 0.6f * (1f - inner), style = c.hairline)
            drawCircle(c.colors.miss, c.cell * (0.5f + 1.5f * outer), centre, alpha = 0.6f * (1f - outer), style = c.hairline)
        }
        EffectKind.EXPLOSION -> {
            val centre = cellCentre(event.cells.first(), c)
            val p = progress(t, EffectEvent.EXPLOSION_MS)
            drawCircle(c.colors.hit, c.cell * 0.3f, centre, alpha = 1f - p)
            for (particle in effect.particles) {
                val d = particle.distance * c.cell * p
                val at = Offset(centre.x + cos(particle.angle) * d, centre.y + sin(particle.angle) * d)
                val colour = if (particle.hot) c.colors.hit else c.colors.accent
                drawCircle(colour, particle.size * c.cell * (1f - 0.5f * p), at, alpha = 1f - p)
            }
            // Smoke rises from 0.2 s and stays for 2 s at 30 % alpha, fading at the end.
            val smokeT = t - 200
            if (smokeT > 0) {
                val s = progress(smokeT, EffectEvent.SMOKE_MS)
                for (puff in effect.smoke) {
                    val local = progress(smokeT - (puff.delayFraction * 400).toLong(), EffectEvent.SMOKE_MS)
                    val at = Offset(
                        centre.x + puff.drift * c.cell,
                        centre.y - puff.rise * c.cell * local,
                    )
                    drawCircle(c.colors.textMuted, puff.radius * c.cell * (1f + 0.6f * local), at, alpha = 0.3f * (1f - 0.5f * s))
                }
            }
        }
        EffectKind.SINK -> drawSink(effect, t, c)
        EffectKind.SONAR_PULSE -> {
            val centre = cellCentre(event.cells.first(), c)
            val p = progress(t, EffectEvent.SONAR_MS)
            drawCircle(c.colors.accent, c.cell * (0.5f + 2.2f * p), centre, alpha = 0.6f * (1f - p), style = c.stroke2)
        }
        EffectKind.TORPEDO_TRAIL -> {
            event.cells.forEachIndexed { k, index ->
                val started = t - k * EffectEvent.TRAIL_STEP_MS
                if (started >= 0) {
                    val (x, y) = cellOrigin(index, c)
                    drawRoundRect(c.colors.accent, Offset(x, y), Size(c.cell, c.cell), c.cellCorner, alpha = 0.35f)
                }
            }
        }
        EffectKind.RECON_SHADOW -> drawShadow(event, progress(t, EffectEvent.RECON_MS), c)
        EffectKind.CROSS -> event.cells.forEachIndexed { k, index ->
            val started = t - k * EffectEvent.CROSS_STEP_MS
            if (started >= 0) {
                val p = progress(started, EffectEvent.CROSS_STEP_MS * 2)
                drawCircle(c.colors.hit, c.cell * (0.2f + 0.2f * p), cellCentre(index, c), alpha = 1f - p)
            }
        }
        EffectKind.MINE_FLASH -> {
            val p = progress(t, EffectEvent.MINE_MS)
            val index = event.cells.first()
            val (x, y) = cellOrigin(index, c)
            drawRoundRect(c.colors.danger, Offset(x, y), Size(c.cell, c.cell), c.cellCorner, alpha = 0.5f * (1f - p))
            drawCircle(c.colors.danger, c.cell * (0.3f + 0.9f * p), cellCentre(index, c), alpha = 1f - p, style = c.stroke2)
        }
    }
}

/** The ship sinks: it appears, tilts 4 degrees and goes down 20 % of its height, with bubbles. */
private fun DrawScope.drawSink(effect: PreparedEffect, t: Long, c: BoardCache) {
    val hull = effect.hull ?: return
    val event = effect.event
    val p = progress(t, EffectEvent.SINK_MS)
    val appear = min(1f, t / 120f)
    translate(0f, effect.hullHeight * 0.2f * p) {
        rotate(degrees = 4f * p, pivot = effect.hullCentre) {
            drawPath(hull, c.colors.ship, alpha = appear * (1f - 0.3f * p))
            drawPath(hull, c.colors.shipEdge, alpha = appear * (1f - 0.3f * p), style = c.stroke15)
        }
    }
    // Bubbles rise from the hull while it sinks.
    if (t in 200..EffectEvent.SINK_MS) {
        for (particle in effect.particles.take(6)) {
            val b = progress(t - 200, 700)
            val at = Offset(
                effect.hullCentre.x + cos(particle.angle) * c.cell * 0.4f,
                effect.hullCentre.y + effect.hullHeight * 0.3f - b * c.cell * 1.2f,
            )
            drawCircle(c.colors.textMuted, c.cell * 0.05f, at, alpha = 0.4f * (1f - b))
        }
    }
    // Automatic water appears cell by cell, 30 ms apart, after the hull is gone.
    event.autoWater.forEachIndexed { k, index ->
        val started = t - EffectEvent.SINK_MS - k * EffectEvent.AUTO_WATER_STEP_MS
        if (started >= 0) {
            drawCircle(c.colors.miss, c.cell * 0.08f, cellCentre(index, c), alpha = 0.45f)
        }
    }
}

/** The plane shadow crosses the line of cells (a row or a column). */
private fun DrawScope.drawShadow(event: EffectEvent, p: Float, c: BoardCache) {
    val first = cellCentre(event.cells.first(), c)
    val last = cellCentre(event.cells.last(), c)
    val at = Offset(first.x + (last.x - first.x) * p, first.y + (last.y - first.y) * p)
    val horizontal = event.isRow
    val size = if (horizontal) Size(c.cell * 1.2f, c.cell * 0.3f) else Size(c.cell * 0.3f, c.cell * 1.2f)
    drawRoundRect(
        color = Color.Black,
        topLeft = Offset(at.x - size.width / 2f, at.y - size.height / 2f),
        size = size,
        cornerRadius = CornerRadius(c.cell * 0.15f),
        alpha = 0.3f,
    )
}

/** Reduced motion: the same effect as a fade of at most [REDUCED_FADE_MS], without movement. */
private fun DrawScope.drawReduced(effect: PreparedEffect, t: Long, c: BoardCache) {
    val event = effect.event
    val fade = 1f - min(1f, t / REDUCED_FADE_MS.toFloat())
    val appear = min(1f, t / REDUCED_FADE_MS.toFloat())
    when (event.kind) {
        EffectKind.SPLASH -> drawCircle(c.colors.miss, c.cell * 0.5f, cellCentre(event.cells.first(), c), alpha = 0.8f * fade, style = c.stroke2)
        EffectKind.EXPLOSION -> {
            val (x, y) = cellOrigin(event.cells.first(), c)
            drawRoundRect(c.colors.hit, Offset(x, y), Size(c.cell, c.cell), c.cellCorner, alpha = 0.16f * appear)
        }
        EffectKind.SINK -> {
            effect.hull?.let { drawPath(it, c.colors.ship, alpha = appear) }
            event.autoWater.forEach { index -> drawCircle(c.colors.miss, c.cell * 0.08f, cellCentre(index, c), alpha = 0.45f * appear) }
        }
        EffectKind.SONAR_PULSE -> drawCircle(c.colors.accent, c.cell * 0.5f, cellCentre(event.cells.first(), c), alpha = 0.6f * fade, style = c.stroke2)
        EffectKind.TORPEDO_TRAIL -> event.cells.forEach { index ->
            val (x, y) = cellOrigin(index, c)
            drawRoundRect(c.colors.accent, Offset(x, y), Size(c.cell, c.cell), c.cellCorner, alpha = 0.35f * appear)
        }
        EffectKind.RECON_SHADOW -> drawShadow(event, 1f, c)
        EffectKind.CROSS -> event.cells.forEach { index -> drawCircle(c.colors.hit, c.cell * 0.3f, cellCentre(index, c), alpha = fade) }
        EffectKind.MINE_FLASH -> {
            val (x, y) = cellOrigin(event.cells.first(), c)
            drawRoundRect(c.colors.danger, Offset(x, y), Size(c.cell, c.cell), c.cellCorner, alpha = 0.5f * fade)
        }
    }
}

/** Linear progress of [t] over [duration] ms, clamped to 0..1. */
internal fun progress(t: Long, duration: Long): Float {
    if (t <= 0) return 0f
    return min(1f, t.toFloat() / duration.toFloat())
}

/** Top-left corner of a cell in board px. */
internal fun cellOrigin(index: Int, c: BoardCache): Pair<Float, Float> {
    val col = index % c.layout.cols
    val row = index / c.layout.cols
    return c.layout.cellX(col).toFloat() to c.layout.cellY(row).toFloat()
}

/** Centre of a cell in board px. */
internal fun cellCentre(index: Int, c: BoardCache): Offset {
    val (x, y) = cellOrigin(index, c)
    return Offset(x + c.cell / 2f, y + c.cell / 2f)
}
