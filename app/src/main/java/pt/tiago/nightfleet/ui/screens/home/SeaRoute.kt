package pt.tiago.nightfleet.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.ui.theme.LocalFleetColors

// Vertical position of each route node, as a fraction of the canvas height (a sinuous route).
private val RouteHeights = floatArrayOf(0.62f, 0.30f, 0.56f, 0.78f, 0.36f, 0.22f, 0.60f, 0.42f, 0.70f)

/**
 * The sea route: a dashed path with nodes. Nodes before [currentIndex] are done
 * (accent), the current one is ringed, the later ones are future nodes, and the last node is the boss
 * (diamond with a `hit` border). The screen passes sample values; real progress arrives in M8.
 */
@Composable
fun SeaRoute(
    nodeCount: Int,
    currentIndex: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    Canvas(
        modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .semantics { contentDescription = description },
    ) {
        val marginX = 16.dp.toPx()
        val step = (size.width - 2 * marginX) / (nodeCount - 1)
        val points = List(nodeCount) { i ->
            Offset(marginX + step * i, size.height * RouteHeights[i % RouteHeights.size])
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(
            path = path,
            color = colors.line,
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            ),
        )
        points.forEachIndexed { i, p ->
            when {
                i == nodeCount - 1 -> {
                    val r = 11.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(p.x, p.y - r)
                        lineTo(p.x + r, p.y)
                        lineTo(p.x, p.y + r)
                        lineTo(p.x - r, p.y)
                        close()
                    }
                    drawPath(diamond, color = colors.surface2)
                    drawPath(diamond, color = colors.hit, style = Stroke(width = 2.dp.toPx()))
                }
                i < currentIndex -> drawCircle(color = colors.accent, radius = 7.dp.toPx(), center = p)
                i == currentIndex -> {
                    drawCircle(color = colors.accent, radius = 9.dp.toPx(), center = p)
                    drawCircle(color = colors.text, radius = 15.dp.toPx(), center = p, style = Stroke(width = 2.dp.toPx()))
                }
                else -> {
                    drawCircle(color = colors.surface2, radius = 7.dp.toPx(), center = p)
                    drawCircle(color = colors.line, radius = 7.dp.toPx(), center = p, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
    }
}
