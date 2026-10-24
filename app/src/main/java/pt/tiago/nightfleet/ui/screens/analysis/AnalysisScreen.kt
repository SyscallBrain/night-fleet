package pt.tiago.nightfleet.ui.screens.analysis

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.AnalysisSummary
import pt.tiago.nightfleet.game.analysis.AnalysisInput
import pt.tiago.nightfleet.game.analysis.MatchAnalysis
import pt.tiago.nightfleet.game.analysis.MatchAnalyzer
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.ObservationMapper
import pt.tiago.nightfleet.game.replay.MatchReplay
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The analysis of a battle: the final map with the
 * shots numbered, the wasted shots marked, the three worst shots with the better square, the precision and the
 * officer's shots. A battle analysed before comes from the logbook ([AnalysisInput.stored]); otherwise it is
 * computed off the main thread, once, and kept in the logbook entry through [onSave].
 */
@Composable
fun AnalysisScreen(
    input: AnalysisInput,
    onSave: suspend (AnalysisSummary) -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val replay = remember(input) { MatchReplay.fromPayload(input.replay) }
    var computed by remember(input) { mutableStateOf<MatchAnalysis?>(null) }
    LaunchedEffect(input) {
        if (input.stored != null || replay == null) return@LaunchedEffect
        val analysis = withContext(Dispatchers.Default) { MatchAnalyzer.analyze(replay) }
        computed = analysis
        onSave(analysis.summary)
    }
    val summary = input.stored ?: computed?.summary

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.freeplay_back)) }
            Text(stringResource(R.string.analysis_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        }
        when {
            replay == null -> Text(stringResource(R.string.analysis_error), style = type.bodyText, color = colors.danger)
            summary == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = colors.accent)
                Text(stringResource(R.string.analysis_computing), style = type.bodyText, color = colors.textMuted)
            }
            else -> {
                Text(
                    pluralStringResource(R.plurals.analysis_precision, summary.shots, summary.precision, summary.shots),
                    style = type.bodyText,
                    color = colors.text,
                )
                Text(stringResource(R.string.analysis_wasted, summary.wasted), style = type.caption, color = colors.textMuted)
                AnalysisMap(replay, summary, Modifier.fillMaxWidth())
                Text(stringResource(R.string.analysis_legend), style = type.caption, color = colors.textMuted)
                Text(stringResource(R.string.analysis_worst_title), style = type.bodyText, color = colors.text)
                if (summary.worst.isEmpty()) {
                    Text(stringResource(R.string.analysis_worst_none), style = type.caption, color = colors.textMuted)
                } else {
                    summary.worst.forEach { worst ->
                        Text(
                            stringResource(
                                R.string.analysis_worst_line,
                                worst.number,
                                coordinate(worst.cell, replay.stateAt(0).rules.width),
                                coordinate(worst.bestCell, replay.stateAt(0).rules.width),
                            ),
                            style = type.caption,
                            color = colors.text,
                        )
                    }
                }
                Text(
                    summary.officerShots?.let { pluralStringResource(R.plurals.analysis_officer, it, it) }
                        ?: stringResource(R.string.analysis_officer_unknown),
                    style = type.bodyText,
                    color = colors.text,
                )
                OutlinedButton(onClick = onReplay, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.analysis_replay))
                }
            }
        }
    }
}

/** A square label such as "B4": the column letter and the row number, from 1. */
internal fun coordinate(index: Int, width: Int): String {
    val row = index / width
    val col = index % width
    return "${'A' + col}${row + 1}"
}

/**
 * The final enemy sea with the player's shots numbered in play order. Wasted shots carry a thin cross, and each of
 * the worst shots has an arrow to the better square of that moment.
 */
@Composable
private fun AnalysisMap(replay: MatchReplay, summary: AnalysisSummary, modifier: Modifier) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val last = replay.last
    val width = last.rules.width
    val height = last.rules.height
    val model = remember(replay) {
        ObservationMapper.revealEnemy(ObservationMapper.enemySea(Observation.of(last, BattleMatch.PLAYER), emptySet(), null, null), last, BattleMatch.PLAYER)
    }
    val shots = remember(replay) { MatchAnalyzer.playerShots(replay) }
    val numberOf = remember(shots) { shots.associate { it.cell to it.number } }
    val wasted = remember(summary) { summary.wastedShots.toSet() }
    val wastedCells = remember(shots, wasted) { shots.filter { it.number in wasted }.map { it.cell }.toSet() }
    val shape = RoundedCornerShape(LocalFleetShapes.current.card)
    BoxWithConstraints(modifier) {
        val cell = (maxWidth / width).coerceAtMost(MAX_CELL)
        Box(
            Modifier
                .size(cell * width, cell * height)
                .background(colors.sea, shape)
                .padding(0.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                for (row in 0 until height) {
                    Row(Modifier.weight(1f)) {
                        for (col in 0 until width) {
                            val index = row * width + col
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(fillOf(model.cells[index], colors))
                                    .padding(0.5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                numberOf[index]?.let { number ->
                                    Text(
                                        number.toString(),
                                        style = type.coordinate.copy(fontSize = 9.sp, textAlign = TextAlign.Center),
                                        color = colors.text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Canvas(Modifier.matchParentSize()) {
                val cw = size.width / width
                val ch = size.height / height
                fun centre(index: Int) = Offset((index % width + 0.5f) * cw, (index / width + 0.5f) * ch)
                for (index in wastedCells) {
                    val x = (index % width) * cw
                    val y = (index / width) * ch
                    val inset = cw * 0.22f
                    val stroke = Stroke(width = 1.dp.toPx())
                    drawLine(colors.textMuted, Offset(x + inset, y + inset), Offset(x + cw - inset, y + ch - inset), strokeWidth = stroke.width)
                    drawLine(colors.textMuted, Offset(x + cw - inset, y + inset), Offset(x + inset, y + ch - inset), strokeWidth = stroke.width)
                }
                for (worst in summary.worst) {
                    val from = centre(worst.cell)
                    val to = centre(worst.bestCell)
                    drawRect(colors.danger, topLeft = Offset(from.x - cw / 2, from.y - ch / 2), size = androidx.compose.ui.geometry.Size(cw, ch), style = Stroke(width = 2.dp.toPx()))
                    drawLine(colors.accent, from, to, strokeWidth = 2.dp.toPx())
                    val head = 6.dp.toPx()
                    val angle = kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
                    for (side in listOf(-0.5, 0.5)) {
                        val a = angle + Math.PI - side
                        drawLine(
                            colors.accent,
                            to,
                            Offset(to.x + (head * kotlin.math.cos(a)).toFloat(), to.y + (head * kotlin.math.sin(a)).toFloat()),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        }
    }
}

private val MAX_CELL = 36.dp

private fun fillOf(visual: CellVisual, colors: pt.tiago.nightfleet.ui.theme.FleetColors): Color = when (visual) {
    CellVisual.HIT, CellVisual.SUNK, CellVisual.OWN_SHIP_HIT -> colors.hit.copy(alpha = 0.55f)
    CellVisual.REVEALED_SHIP, CellVisual.OWN_SHIP -> colors.ship.copy(alpha = 0.6f)
    CellVisual.ISLAND, CellVisual.REEF -> colors.island
    CellVisual.WATER, CellVisual.AUTO_WATER -> colors.miss.copy(alpha = 0.2f)
    else -> colors.sea
}
