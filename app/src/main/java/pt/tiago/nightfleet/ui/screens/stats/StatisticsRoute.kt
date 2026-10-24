package pt.tiago.nightfleet.ui.screens.stats

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.DifficultyStats
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.meta.Heatmap
import pt.tiago.nightfleet.meta.Ships
import pt.tiago.nightfleet.ui.common.EmptyState
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.common.SectionLabel
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * Statistics per difficulty: battles, wins, precision, the longest hit streak, ships sunk per
 * class, weapons used, puzzles solved, mode records, time played, and the 10 x 10 shot heatmap.
 * Wiring: `StatisticsRoute(container = container, onBack = ...)`.
 */
@Composable
fun StatisticsRoute(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val profile by container.profile.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val difficulty = Difficulty.entries[tab]
    val stats = profile.stats[difficulty] ?: DifficultyStats()

    MetaScreen(title = stringResource(R.string.stat_title), onBack = onBack, modifier = modifier) {
        TabRow(selectedTabIndex = tab, containerColor = LocalFleetColors.current.surface, contentColor = LocalFleetColors.current.accent) {
            Difficulty.entries.forEachIndexed { index, option ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(difficultyNameRes(option)), style = LocalFleetType.current.caption) },
                )
            }
        }
        SummaryCard(stats)
        HeatmapCard(stats.shotHeatmap)
        WeaponsCard(stats)
        ShipsCard(stats)
        RecordsCard(profile, difficulty)
    }
}

@Composable
private fun SummaryCard(stats: DifficultyStats) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val precision = if (stats.shots > 0) "${stats.hits * 100 / stats.shots} %" else "—"
    MetaCard {
        StatLine(stringResource(R.string.stat_battles), stats.battles.toString())
        StatLine(stringResource(R.string.stat_wins), stats.wins.toString())
        StatLine(stringResource(R.string.stat_precision), precision)
        StatLine(stringResource(R.string.stat_streak), stats.longestHitStreak.toString())
        StatLine(stringResource(R.string.stat_puzzles), stats.puzzlesSolved.toString())
        StatLine(
            stringResource(R.string.stat_playtime),
            stringResource(R.string.stat_playtime_value, stats.playTimeSeconds / 3600, stats.playTimeSeconds % 3600 / 60),
        )
        Text(pluralStringResource(R.plurals.stat_shots, stats.shots, stats.shots), style = type.caption, color = colors.textMuted)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = type.bodyText, color = colors.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = type.hudShots.copy(fontSize = 18.sp, lineHeight = 22.sp), color = colors.text)
    }
}

@Composable
private fun HeatmapCard(counts: List<Int>) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val total = Heatmap.cells(counts).sum()
    MetaCard {
        SectionLabel(stringResource(R.string.stat_heatmap))
        if (Heatmap.isEmpty(counts)) {
            EmptyState(stringResource(R.string.stat_heatmap_empty))
        } else {
            val values = Heatmap.normalize(counts)
            val description = pluralStringResource(R.plurals.stat_heatmap_desc, total, total)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .semantics { contentDescription = description },
            ) {
                val gap = 2.dp.toPx()
                val cell = (size.minDimension - gap * 9) / 10f
                for (row in 0 until 10) for (col in 0 until 10) {
                    val value = values[row * 10 + col]
                    drawRect(
                        color = lerp(colors.surface2, colors.accent, value),
                        topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                        size = Size(cell, cell),
                    )
                }
            }
            Text(stringResource(R.string.stat_heatmap_legend), style = type.caption, color = colors.textMuted)
        }
    }
}

@Composable
private fun WeaponsCard(stats: DifficultyStats) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    MetaCard {
        SectionLabel(stringResource(R.string.stat_weapons))
        if (stats.weaponsUsed.isEmpty()) {
            Text(stringResource(R.string.stat_no_data), style = type.bodyText, color = colors.textMuted)
        } else {
            stats.weaponsUsed.entries.sortedByDescending { it.value }.forEach { (key, count) ->
                val name = weaponNameRes(key)?.let { stringResource(it) } ?: key
                StatLine(name, count.toString())
            }
        }
    }
}

@Composable
private fun ShipsCard(stats: DifficultyStats) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    MetaCard {
        SectionLabel(stringResource(R.string.stat_sunk))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Ships.all.forEach { ship ->
                val count = stats.shipsSunkByClass[ship.shipClass.name] ?: 0
                StatLine(stringResource(ship.name), count.toString())
            }
        }
        Text(stringResource(R.string.stat_sunk_total, stats.shipsSunkByClass.values.sum()), style = type.caption, color = colors.textMuted)
    }
}

@Composable
private fun RecordsCard(profile: Profile, difficulty: Difficulty) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val patrol = profile.patrolBest[difficulty] ?: 0
    val tournament = profile.tournamentBest[difficulty] ?: 0
    MetaCard {
        SectionLabel(stringResource(R.string.stat_records))
        Text(
            pluralStringResource(R.plurals.stat_patrol_seas, patrol, patrol),
            style = type.bodyText,
            color = colors.text,
        )
        Text(
            pluralStringResource(R.plurals.stat_tournament_rounds, tournament, tournament),
            style = type.bodyText,
            color = colors.text,
        )
        Text(
            stringResource(
                R.string.stat_endless,
                profile.endlessSolved[6] ?: 0,
                profile.endlessSolved[8] ?: 0,
                profile.endlessSolved[10] ?: 0,
            ),
            style = type.bodyText,
            color = colors.text,
        )
        Text(stringResource(R.string.stat_records_note), style = type.caption, color = colors.textMuted)
    }
}

@StringRes
private fun difficultyNameRes(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}

/** Names of the six weapons (engine enum `Weapon`). Unknown keys are shown as they are stored. */
@StringRes
fun weaponNameRes(key: String): Int? = when (key) {
    "SONAR" -> R.string.weapon_sonar
    "TORPEDO" -> R.string.weapon_torpedo
    "RECON" -> R.string.weapon_recon
    "CROSS_SHELL" -> R.string.weapon_cross
    "DECOY" -> R.string.weapon_decoy
    "REPAIR" -> R.string.weapon_repair
    else -> null
}
