package pt.tiago.nightfleet.ui.screens.logbook

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.LogbookRules
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.meta.Admirals
import pt.tiago.nightfleet.meta.CellMark
import pt.tiago.nightfleet.meta.CompactMap
import pt.tiago.nightfleet.meta.LogFilter
import pt.tiago.nightfleet.meta.LogbookFilter
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.common.EmptyState
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The logbook: finished battles and puzzles, newest first, with filters and pins. Tapping an
 * entry opens its detail with the two maps and the "Replay" and "Analysis" actions. The replay and analysis
 * screens arrive later; their callbacks receive the entry id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onReplay: (entryId: String) -> Unit,
    onAnalysis: (entryId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by container.logbook.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    var pinLimit by remember { mutableStateOf(false) }
    val visible = remember(entries, filter) { LogbookFilter.apply(entries, filter) }
    val locale: Locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val togglePin: (LogEntry) -> Unit = { entry ->
        scope.launch {
            val ok = container.logbook.setPinned(entry.id, !entry.pinned)
            pinLimit = !ok
        }
    }

    MetaScreen(title = stringResource(R.string.log_title), onBack = onBack, modifier = modifier, scrollable = false) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(stringResource(filterLabel(option)), style = LocalFleetType.current.caption) },
                )
            }
        }
        if (pinLimit) {
            Text(
                pluralStringResource(R.plurals.log_pin_limit, LogbookRules.MAX_PINNED, LogbookRules.MAX_PINNED),
                style = LocalFleetType.current.caption,
                color = LocalFleetColors.current.danger,
            )
        }
        if (visible.isEmpty()) {
            EmptyState(stringResource(if (entries.isEmpty()) R.string.log_empty else R.string.log_empty_filter))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { entry ->
                    LogRow(entry, formatter, onOpen = { selected = entry }, onTogglePin = { togglePin(entry) })
                }
            }
        }
    }

    selected?.let { entry ->
        // The detail reads the live entry, so a pin change shows at once.
        val current = entries.firstOrNull { it.id == entry.id } ?: entry
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            LogDetail(
                entry = current,
                formatter = formatter,
                onTogglePin = { togglePin(current) },
                onReplay = { selected = null; onReplay(current.id) },
                onAnalysis = { selected = null; onAnalysis(current.id) },
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, formatter: DateTimeFormatter, onOpen: () -> Unit, onTogglePin: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val pinLabel = stringResource(if (entry.pinned) R.string.log_unpin else R.string.log_pin)
    val pinnedState = if (entry.pinned) stringResource(R.string.log_pinned) else null
    MetaCard(Modifier.clickable(role = Role.Button, onClick = onOpen)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The thumbnail shows the player's shots, so it reads the enemy sea.
            MapThumbnail(entry.finalMapEnemy, entry.boardWidth, entry.boardHeight, colors, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(missionTitle(entry), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                Text(
                    "${formatter.format(Instant.ofEpochMilli(entry.epochMillis))} · ${stringResource(resultLabel(entry.result))}",
                    style = type.caption,
                    color = colors.textMuted,
                )
                Text(stars(entry.stars), style = type.caption, color = colors.accent)
            }
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = pinLabel
                    pinnedState?.let { stateDescription = it }
                },
            ) {
                PinIcon(
                    tint = if (entry.pinned) colors.accent else colors.textMuted,
                    filled = entry.pinned,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** A drawn pushpin: a head and a needle, filled when the entry is pinned. */
@Composable
private fun PinIcon(tint: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val head = Offset(w / 2f, h * 0.36f)
        val radius = w * 0.24f
        if (filled) {
            drawCircle(tint, radius = radius, center = head)
        } else {
            drawCircle(tint, radius = radius, center = head, style = Stroke(stroke))
        }
        drawLine(tint, Offset(w * 0.2f, h * 0.6f), Offset(w * 0.8f, h * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w / 2f, h * 0.6f), Offset(w / 2f, h * 0.94f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ColumnScope.LogDetail(
    entry: LogEntry,
    formatter: DateTimeFormatter,
    onTogglePin: () -> Unit,
    onReplay: () -> Unit,
    onAnalysis: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shots = entry.shots
    val precision = if (shots > 0) entry.hits * 100 / shots else 0
    Column(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(missionTitle(entry), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        Text(formatter.format(Instant.ofEpochMilli(entry.epochMillis)), style = type.caption, color = colors.textMuted)
        Text(
            "${stringResource(resultLabel(entry.result))} · ${stars(entry.stars)}",
            style = type.bodyText,
            color = colors.text,
        )
        Text(stringResource(R.string.log_precision, precision), style = type.bodyText, color = colors.text)
        Text(pluralStringResource(R.plurals.log_shots, shots, shots), style = type.caption, color = colors.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.log_player_map), style = type.caption, color = colors.textMuted)
                MapThumbnail(entry.finalMapPlayer, entry.boardWidth, entry.boardHeight, colors, size = 140.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.log_enemy_map), style = type.caption, color = colors.textMuted)
                MapThumbnail(entry.finalMapEnemy, entry.boardWidth, entry.boardHeight, colors, size = 140.dp)
            }
        }
        Button(
            onClick = onReplay,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) { Text(stringResource(R.string.log_replay), style = type.button) }
        // Analysis is for battles; a solved puzzle has no shot-by-shot comparison.
        if (entry.result != LogResult.SOLVED && entry.kind != MatchKind.ENDLESS) {
            Button(
                onClick = onAnalysis,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.surface2, contentColor = colors.text),
            ) { Text(stringResource(R.string.log_analysis), style = type.button) }
        }
        TextButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(if (entry.pinned) R.string.log_unpin else R.string.log_pin), style = type.button)
        }
    }
}

/**
 * The compact final map as a small grid (CompactMap.kt for the glyphs). Unknown or damaged maps show an
 * empty grid, so an old entry never breaks the list.
 */
@Composable
private fun MapThumbnail(text: String, width: Int, height: Int, colors: FleetColors, size: Dp) {
    val cells = CompactMap.decode(text, width, height)
    val gapDp = if (size >= 80.dp) 2.dp else 1.dp
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val columns = width.coerceAtLeast(1)
            val rows = height.coerceAtLeast(1)
            val gap = gapDp.toPx()
            val cell = minOf((this.size.width - gap * (columns - 1)) / columns, (this.size.height - gap * (rows - 1)) / rows)
            for (row in 0 until rows) for (col in 0 until columns) {
                val mark = cells?.getOrNull(row * columns + col) ?: CellMark.UNKNOWN
                drawRect(
                    color = markColour(mark, colors),
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private fun markColour(mark: CellMark, colors: FleetColors) = when (mark) {
    CellMark.UNKNOWN -> colors.surface2
    CellMark.WATER -> colors.miss.copy(alpha = 0.45f)
    CellMark.HIT -> colors.hit
    CellMark.SHIP -> colors.ship
    CellMark.SUNK -> colors.shipEdge
    CellMark.ISLAND -> colors.island
}

@Composable
private fun missionTitle(entry: LogEntry): String {
    val mission = Seas.parseMissionId(entry.missionId)
    val admiral = entry.admiralId?.let { Admirals.byId(it) }
    return when {
        mission != null -> stringResource(R.string.log_mission_title, stringResource(Seas.nameRes(mission.sea)), mission.mission)
        admiral != null -> stringResource(admiral.name)
        else -> stringResource(kindLabel(entry.kind))
    }
}

private fun stars(count: Int): String {
    val filled = count.coerceIn(0, 3)
    return "★".repeat(filled) + "☆".repeat(3 - filled)
}

@StringRes
private fun filterLabel(filter: LogFilter): Int = when (filter) {
    LogFilter.ALL -> R.string.log_filter_all
    LogFilter.CAMPAIGN -> R.string.log_filter_campaign
    LogFilter.MODES -> R.string.log_filter_modes
    LogFilter.WINS -> R.string.log_filter_wins
    LogFilter.PINNED -> R.string.log_filter_pinned
}

@StringRes
private fun resultLabel(result: LogResult): Int = when (result) {
    LogResult.WIN -> R.string.log_result_win
    LogResult.LOSS -> R.string.log_result_loss
    LogResult.DRAW -> R.string.log_result_draw
    LogResult.SOLVED -> R.string.log_result_solved
    LogResult.ABANDONED -> R.string.log_result_abandoned
}

@StringRes
private fun kindLabel(kind: MatchKind): Int = when (kind) {
    MatchKind.CAMPAIGN -> R.string.kind_campaign
    MatchKind.DAILY -> R.string.kind_daily
    MatchKind.FREE_PLAY -> R.string.kind_free
    MatchKind.DUO -> R.string.kind_duo
    MatchKind.PATROL -> R.string.kind_patrol
    MatchKind.TOURNAMENT -> R.string.kind_tournament
    MatchKind.ENDLESS -> R.string.kind_endless
}
