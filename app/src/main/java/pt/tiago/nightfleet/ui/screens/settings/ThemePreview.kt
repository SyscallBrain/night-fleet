package pt.tiago.nightfleet.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.theme.fleetColors
import pt.tiago.nightfleet.ui.theme.fleetShapes

/**
 * A theme option with a live mini preview: a five-by-three sea drawn with that theme's own colours,
 * one miss, one hit and one ship. Only the colours change; the layout is the same in every theme.
 */
@Composable
fun ThemeOptionCard(
    themeId: ThemeId,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outer = LocalFleetColors.current
    val type = LocalFleetType.current
    val shape = RoundedCornerShape(fleetShapes(themeId).card)
    Column(
        modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // Fixed: the preview holds no text, and a drawing with only a minimum height gets no size to draw in.
                .height(72.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(fleetColors(themeId).bg, fleetColors(themeId).bg2)))
                .border(if (selected) 2.dp else 1.dp, if (selected) outer.accent else outer.line, shape),
        ) {
            drawSeaPreview(fleetColors(themeId))
        }
        Text(label, style = type.caption, color = outer.text)
    }
}

/** The mini sea: a 5 x 3 grid with a miss, a hit and a two-cell ship. */
private fun DrawScope.drawSeaPreview(colors: FleetColors) {
    val margin = 10.dp.toPx()
    val gap = 2.dp.toPx()
    val cell = ((size.width - margin * 2 - gap * 4) / 5f).coerceAtMost((size.height - margin * 2 - gap * 2) / 3f)
    val originX = (size.width - (cell * 5 + gap * 4)) / 2f
    val originY = (size.height - (cell * 3 + gap * 2)) / 2f
    for (row in 0 until 3) for (col in 0 until 5) {
        val x = originX + col * (cell + gap)
        val y = originY + row * (cell + gap)
        drawRect(colors.sea, Offset(x, y), Size(cell, cell))
        drawRect(colors.grid, Offset(x, y), Size(cell, cell), style = Stroke(width = 1.dp.toPx()))
    }
    // Miss: a ring in the miss colour.
    drawCircle(colors.miss, radius = cell * 0.18f, center = Offset(originX + cell / 2f, originY + cell / 2f), style = Stroke(width = 2.dp.toPx()))
    // Hit: a solid mark in the hit colour on the second cell of the top row.
    val hitX = originX + (cell + gap) + cell / 2f
    drawCircle(colors.hit, radius = cell * 0.3f, center = Offset(hitX, originY + cell / 2f))
    // Ship: two cells in the bottom row with the hull colour and its edge.
    val shipY = originY + 2 * (cell + gap)
    val shipX = originX + 2 * (cell + gap)
    drawRect(colors.ship, Offset(shipX, shipY), Size(cell * 2 + gap, cell))
    drawRect(colors.shipEdge, Offset(shipX, shipY), Size(cell * 2 + gap, cell), style = Stroke(width = 1.5.dp.toPx()))
}
