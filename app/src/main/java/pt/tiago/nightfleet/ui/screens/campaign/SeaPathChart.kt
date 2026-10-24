package pt.tiago.nightfleet.ui.screens.campaign

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the chart shows of one mission. */
data class PathNodeUi(
    val mission: Int,
    val unlocked: Boolean,
    val stars: Int,
    val kind: MissionKind?,
    val tags: List<MissionTag>,
    val boss: Boolean,
)

/** Touch target of a node, in dp. The visible circle is [NODE_VISIBLE] dp. */
private const val NODE_TOUCH = 48
private const val NODE_VISIBLE = 44

/** Size of the type icon drawn inside the node, in dp (never below 14 dp). */
private const val ICON_SIZE = 14

/**
 * The sea map as a chart. The path is drawn first, from node centre to node centre, so it sits
 * behind everything. Each node is a filled circle with its number and type icon; its stars sit in a small pill
 * beside it, on the side the path does not use (see [SeaPathLayout.starsRect]). Locked nodes show a lock. The current
 * node has an accent ring; the chip scrolls back to it. [onMission] is called for an unlocked node.
 */
@Composable
fun SeaPathChart(
    layout: SeaChart,
    nodes: List<PathNodeUi>,
    current: Int?,
    onMission: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentY = current?.let { mission -> layout.nodes.firstOrNull { it.mission == mission }?.y }
    fun scrollToCurrent() {
        val y = currentY ?: return
        scope.launch { scroll.animateScrollTo(with(density) { (y - 160).coerceAtLeast(0).dp.toPx().toInt() }) }
    }
    LaunchedEffect(current) { scrollToCurrent() }
    val byMission = remember(nodes) { nodes.associateBy { it.mission } }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(layout.width.dp, layout.height.dp)) {
                // Background, islands and path: drawn first, so the nodes and the stars pills sit above them.
                Canvas(Modifier.fillMaxSize()) {
                    drawChart(layout, byMission, colors.island, colors.text, colors.accent, colors.line, colors.bg2)
                }
                layout.nodes.forEach { placed ->
                    val ui = byMission[placed.mission] ?: return@forEach
                    // The node box is 48 dp, centred on the node; the stars pill is placed inside that frame.
                    val box = Modifier.offset(
                        x = (placed.x - NODE_TOUCH / 2).dp,
                        y = (placed.y - NODE_TOUCH / 2).dp,
                    )
                    val pill = SeaPathLayout.starsRect(placed, layout.width)
                    PathNodeView(
                        ui = ui,
                        isCurrent = ui.mission == current,
                        starsOffsetX = pill.left - (placed.x - NODE_TOUCH / 2),
                        starsOffsetY = pill.top - (placed.y - NODE_TOUCH / 2),
                        modifier = box,
                        onClick = { onMission(ui.mission) },
                    )
                }
            }
        }
        if (current != null) {
            AssistChip(
                onClick = { scrollToCurrent() },
                label = { Text(stringResource(R.string.campaign_map_current), style = type.caption) },
                // Opaque, so the nodes that scroll under it do not show through the label.
                colors = AssistChipDefaults.assistChipColors(containerColor = colors.surface, labelColor = colors.text),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun PathNodeView(
    ui: PathNodeUi,
    isCurrent: Boolean,
    starsOffsetX: Int,
    starsOffsetY: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val label = if (!ui.unlocked) {
        stringResource(R.string.campaign_map_node_locked, ui.mission)
    } else {
        val what = if (ui.boss) {
            stringResource(R.string.campaign_map_boss)
        } else {
            ui.kind?.let { stringResource(CampaignText.kindRes(it)) }.orEmpty()
        }
        pluralStringResource(R.plurals.campaign_map_node, ui.stars, ui.mission, what, ui.stars)
    }
    val fill = when {
        !ui.unlocked -> colors.surface2
        ui.stars > 0 -> lerp(colors.surface, colors.accent, 0.2f)
        else -> colors.surface
    }
    Box(modifier.size(NODE_TOUCH.dp)) {
        Canvas(Modifier.size(NODE_TOUCH.dp)) {
            drawNodeDisc(fill = fill, line = colors.line, accent = colors.accent, current = isCurrent)
        }
        Column(
            Modifier
                .size(NODE_TOUCH.dp)
                .clip(CircleShape)
                .semantics { contentDescription = label }
                .clickable(enabled = ui.unlocked, role = Role.Button, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!ui.unlocked) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.textMuted)
            } else {
                Canvas(Modifier.size(ICON_SIZE.dp)) {
                    drawNodeIcon(ui, if (isCurrent) colors.accent else colors.textMuted)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (ui.boss) stringResource(R.string.campaign_map_boss) else ui.mission.toString(),
                    style = type.caption,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (ui.unlocked) {
            // Decorative: the node label already says how many stars there are.
            Box(
                Modifier
                    .offset(x = starsOffsetX.dp, y = starsOffsetY.dp)
                    .size(SeaPathLayout.STARS_WIDTH.dp, SeaPathLayout.STARS_HEIGHT.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.bg.copy(alpha = 0.85f))
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(starsText(ui.stars), style = type.caption, color = colors.accent)
            }
        }
    }
}

/** The disc of a node: a soft glow and an accent ring when current, a thin line border otherwise. */
private fun DrawScope.drawNodeDisc(fill: Color, line: Color, accent: Color, current: Boolean) {
    val centre = Offset(size.width / 2, size.height / 2)
    val radius = NODE_VISIBLE.dp.toPx() / 2
    if (current) drawCircle(accent.copy(alpha = 0.22f), radius = radius + 2.dp.toPx(), center = centre)
    drawCircle(fill, radius = radius, center = centre)
    if (current) {
        drawCircle(accent, radius = radius - 1.5.dp.toPx(), center = centre, style = Stroke(width = 3.dp.toPx()))
    } else {
        drawCircle(line, radius = radius - 0.5.dp.toPx(), center = centre, style = Stroke(width = 1.dp.toPx()))
    }
}

/** Icon of a node, drawn with paths (no images). Lighthouse for puzzles, spyglass for hunts, freighter for convoys. */
private fun DrawScope.drawNodeIcon(ui: PathNodeUi, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
    when {
        ui.boss -> {
            val path = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w, h / 2)
                lineTo(w / 2, h)
                lineTo(0f, h / 2)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ui.kind == MissionKind.PUZZLE -> {
            drawRect(color, topLeft = Offset(w * 0.4f, h * 0.2f), size = Size(w * 0.2f, h * 0.8f))
            val beam = Path().apply {
                moveTo(w * 0.4f, h * 0.2f)
                lineTo(0f, 0f)
                lineTo(w * 0.4f, 0f)
                close()
            }
            drawPath(beam, color)
        }
        ui.kind == MissionKind.HUNT -> {
            drawLine(color, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.7f, h * 0.3f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color, radius = w * 0.2f, center = Offset(w * 0.8f, h * 0.2f), style = stroke)
        }
        ui.kind == MissionKind.CONVOY -> {
            val hull = Path().apply {
                moveTo(0f, h * 0.5f)
                lineTo(w, h * 0.5f)
                lineTo(w * 0.8f, h)
                lineTo(w * 0.2f, h)
                close()
            }
            drawPath(hull, color)
            drawRect(color, topLeft = Offset(w * 0.25f, h * 0.15f), size = Size(w * 0.5f, h * 0.3f))
        }
        MissionTag.MINES in ui.tags -> {
            drawCircle(color, radius = w * 0.3f, center = Offset(w / 2, h / 2))
            drawLine(color, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.5.dp.toPx())
            drawLine(color, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 1.5.dp.toPx())
        }
        MissionTag.TIMED in ui.tags -> {
            val top = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w / 2, h / 2)
                close()
            }
            val bottom = Path().apply {
                moveTo(0f, h)
                lineTo(w, h)
                lineTo(w / 2, h / 2)
                close()
            }
            drawPath(top, color)
            drawPath(bottom, color)
        }
        else -> {
            // Plain duel: a gun sight, a ring with four ticks and a centre dot (a cross would read as "failed").
            val centre = Offset(w / 2, h / 2)
            val ring = w * 0.32f
            val tick = 1.5.dp.toPx()
            drawCircle(color, radius = ring, center = centre, style = stroke)
            drawCircle(color, radius = 1.5.dp.toPx(), center = centre)
            drawLine(color, Offset(w / 2, 0f), Offset(w / 2, h / 2 - ring * 0.55f), strokeWidth = tick, cap = StrokeCap.Round)
            drawLine(color, Offset(w / 2, h), Offset(w / 2, h / 2 + ring * 0.55f), strokeWidth = tick, cap = StrokeCap.Round)
            drawLine(color, Offset(0f, h / 2), Offset(w / 2 - ring * 0.55f, h / 2), strokeWidth = tick, cap = StrokeCap.Round)
            drawLine(color, Offset(w, h / 2), Offset(w / 2 + ring * 0.55f, h / 2), strokeWidth = tick, cap = StrokeCap.Round)
        }
    }
}

/**
 * The background, the islands and the path, all drawn from node centre to node centre. Sailed segments (up to the
 * last unlocked node) are solid accent at 3 dp; future segments are dashed in the `line` colour.
 */
private fun DrawScope.drawChart(
    layout: SeaChart,
    byMission: Map<Int, PathNodeUi>,
    island: Color,
    ink: Color,
    accent: Color,
    future: Color,
    background: Color,
) {
    drawRect(background)
    layout.islands.forEach { drawIsland(it, island, ink) }
    for (i in 0 until layout.nodes.size - 1) {
        val a = layout.nodes[i]
        val b = layout.nodes[i + 1]
        val sailed = byMission[b.mission]?.unlocked == true
        drawLine(
            color = if (sailed) accent else future,
            start = Offset(a.x.dp.toPx(), a.y.dp.toPx()),
            end = Offset(b.x.dp.toPx(), b.y.dp.toPx()),
            strokeWidth = SeaPathLayout.PATH_STROKE.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (sailed) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }
}

/**
 * One decorative island: an irregular coast (a closed curve whose radius wobbles between 62 and 100 % of the island's
 * radius), a band of surf around it, the land in the theme's `island` colour and a scatter of sand dots inside
 *. Everything comes from the island's position, so the same sea always draws the same coast.
 */
private fun DrawScope.drawIsland(land: PathIsland, fill: Color, ink: Color) {
    val r = land.radius.dp.toPx()
    val cx = land.x.dp.toPx()
    val cy = land.y.dp.toPx()
    var state = (land.x * 73_856_093L) xor (land.y * 19_349_663L) xor (land.radius * 83_492_791L)
    fun next(): Float {
        state = (state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L)
        return ((state ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF
    }
    val count = 11
    val tilt = next() * 2f * PI.toFloat()
    val points = (0 until count).map { i ->
        val angle = tilt + i * 2f * PI.toFloat() / count
        val reach = r * (0.62f + 0.38f * next())
        Offset(cx + reach * cos(angle), cy + reach * sin(angle))
    }
    // A smooth closed curve: through the midpoints, with the points themselves as control points.
    val coast = Path().apply {
        val start = (points.last() + points.first()) / 2f
        moveTo(start.x, start.y)
        points.forEachIndexed { i, p ->
            val mid = (p + points[(i + 1) % count]) / 2f
            quadraticTo(p.x, p.y, mid.x, mid.y)
        }
        close()
    }
    drawPath(coast, ink.copy(alpha = 0.07f), style = Stroke(width = 6.dp.toPx(), join = StrokeJoin.Round))
    drawPath(coast, fill)
    drawPath(coast, ink.copy(alpha = 0.22f), style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round))
    val dot = 0.9.dp.toPx()
    clipPath(coast) {
        repeat((r * r / (9.dp.toPx() * 9.dp.toPx())).toInt().coerceIn(4, 40)) {
            val angle = next() * 2f * PI.toFloat()
            val reach = r * 0.7f * sqrt(next())
            drawCircle(ink.copy(alpha = 0.2f), dot, Offset(cx + reach * cos(angle), cy + reach * sin(angle)))
        }
    }
}
