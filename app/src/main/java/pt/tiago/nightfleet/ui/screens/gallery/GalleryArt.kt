package pt.tiago.nightfleet.ui.screens.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.ui.common.drawStar
import pt.tiago.nightfleet.ui.theme.LocalFleetColors

/**
 * Drawn portrait of an admiral, in Canvas: a bust with a hat, a band
 * and a star at the chest. The colour comes from the theme's own tokens, so it changes with the theme. A
 * silhouette uses one flat colour and no details, until the admiral has been met.
 */
@Composable
fun AdmiralPortrait(index: Int, silhouette: Boolean, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val colors = LocalFleetColors.current
    // Each admiral gets a slightly different mix of the theme's warm and cool marks.
    val body = lerp(colors.hit, colors.miss, (index % 12) / 11f)
    val ink = if (silhouette) colors.line else body
    // Decorative: the name and the sea next to it are what TalkBack reads.
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color = colors.surface2, radius = w / 2f)
        // Shoulders and chest.
        drawRoundRect(
            color = ink,
            topLeft = Offset(w * 0.16f, h * 0.62f),
            size = Size(w * 0.68f, h * 0.5f),
            cornerRadius = CornerRadius(w * 0.3f),
        )
        // Head.
        drawCircle(color = if (silhouette) ink else colors.text, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.48f))
        // Hat: a trapezoid with a band.
        val hat = Path().apply {
            moveTo(w * 0.2f, h * 0.31f)
            lineTo(w * 0.8f, h * 0.31f)
            lineTo(w * 0.7f, h * 0.13f)
            lineTo(w * 0.3f, h * 0.13f)
            close()
        }
        drawPath(hat, color = ink)
        if (!silhouette) {
            drawRect(color = colors.accent, topLeft = Offset(w * 0.24f, h * 0.27f), size = Size(w * 0.52f, h * 0.05f))
            drawStar(Offset(w * 0.5f, h * 0.8f), w * 0.09f, colors.accent)
        }
    }
}

/**
 * Drawing of a ship class: its first orientation from the engine, one square per cell, in the hull and edge
 * colours of the theme. Sized to fit the given box.
 */
@Composable
fun ShipDrawing(shipClass: ShipClass, modifier: Modifier = Modifier, width: Dp = 96.dp, height: Dp = 56.dp) {
    val colors = LocalFleetColors.current
    Canvas(modifier.size(width, height)) {
        val cells = shipClass.rotations.first()
        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.col } + 1
        val gap = 2.dp.toPx()
        val cell = minOf((size.width - gap * (cols - 1)) / cols, (size.height - gap * (rows - 1)) / rows)
        val originX = (size.width - (cell * cols + gap * (cols - 1))) / 2f
        val originY = (size.height - (cell * rows + gap * (rows - 1))) / 2f
        for (square in cells) {
            val topLeft = Offset(originX + square.col * (cell + gap), originY + square.row * (cell + gap))
            drawRect(colors.ship, topLeft, Size(cell, cell))
            drawRect(colors.shipEdge, topLeft, Size(cell, cell), style = Stroke(width = 1.dp.toPx()))
        }
    }
}
