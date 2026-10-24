package pt.tiago.nightfleet.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.meta.Insignia
import pt.tiago.nightfleet.meta.insigniaFor
import pt.tiago.nightfleet.ui.theme.LocalFleetColors

/**
 * The rank insignia, drawn on a Canvas: chevrons for enlisted ranks, bars for
 * officers, stars for flag officers, and stars inside a laurel for the Grand Admiral. The colour is `accent`.
 * [name] is the rank name, read by TalkBack.
 */
@Composable
fun RankBadge(rank: Rank, name: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val colors = LocalFleetColors.current
    val insignia = insigniaFor(rank)
    Canvas(modifier.size(size).semantics { contentDescription = name }) {
        drawInsignia(insignia, colors.accent, colors.line)
    }
}

/** Draws [insignia] centred in the canvas. Pure geometry, shared by the badge and the tests of the layout maths. */
fun DrawScope.drawInsignia(insignia: Insignia, accent: Color, ring: Color) {
    val side = size.minDimension
    val centre = Offset(size.width / 2f, size.height / 2f)
    drawCircle(color = ring, radius = side / 2f - 1.5.dp.toPx(), center = centre, style = Stroke(width = 1.5.dp.toPx()))
    if (insignia.laurel) drawLaurel(centre, side / 2f - 5.dp.toPx(), accent)

    when {
        insignia.chevrons > 0 -> {
            val step = side * 0.13f
            val top = centre.y - step * (insignia.chevrons - 1) / 2f - step * 0.5f
            repeat(insignia.chevrons) { i ->
                val y = top + step * i
                val path = Path().apply {
                    moveTo(centre.x - side * 0.26f, y + step * 0.45f)
                    lineTo(centre.x, y)
                    lineTo(centre.x + side * 0.26f, y + step * 0.45f)
                }
                drawPath(path, color = accent, style = Stroke(width = side * 0.07f))
            }
        }
        insignia.bars > 0 -> {
            val step = side * 0.13f
            val top = centre.y - step * (insignia.bars - 1) / 2f - side * 0.035f
            repeat(insignia.bars) { i ->
                drawRect(
                    color = accent,
                    topLeft = Offset(centre.x - side * 0.26f, top + step * i),
                    size = Size(side * 0.52f, side * 0.07f),
                )
            }
        }
        insignia.stars > 0 -> {
            val radius = if (insignia.stars > 2) side * 0.1f else side * 0.14f
            val spacing = radius * 2.5f
            val startX = centre.x - spacing * (insignia.stars - 1) / 2f
            repeat(insignia.stars) { i ->
                drawStar(Offset(startX + spacing * i, centre.y), radius, accent)
            }
        }
    }
}

/** A five-pointed star with its outer radius [outer] (the inner radius is 40 % of it). */
fun DrawScope.drawStar(centre: Offset, outer: Float, color: Color) {
    val inner = outer * 0.4f
    val path = Path()
    for (point in 0 until 10) {
        val radius = if (point % 2 == 0) outer else inner
        // Ten vertices, one every 36 degrees, starting at the top (-90 degrees).
        val angle = Math.toRadians(-90.0 + point * 36.0)
        val x = centre.x + radius * kotlin.math.cos(angle).toFloat()
        val y = centre.y + radius * kotlin.math.sin(angle).toFloat()
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color)
}

/** Two arcs of small leaves on each side, used only for the Grand Admiral. */
private fun DrawScope.drawLaurel(centre: Offset, radius: Float, color: Color) {
    val stroke = Stroke(width = 2.dp.toPx())
    drawArc(
        color = color,
        startAngle = 110f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = stroke,
    )
    drawArc(
        color = color,
        startAngle = -70f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = stroke,
    )
}
