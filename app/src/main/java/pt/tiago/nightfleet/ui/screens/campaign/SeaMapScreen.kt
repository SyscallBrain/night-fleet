package pt.tiago.nightfleet.ui.screens.campaign

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.game.campaign.CampaignRules
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.board.SeaWeatherBackdrop
import pt.tiago.nightfleet.ui.board.WeatherKind
import pt.tiago.nightfleet.ui.board.seaWeatherOf
import pt.tiago.nightfleet.ui.board.systemReducedMotion
import pt.tiago.nightfleet.ui.screens.settings.resolveReducedMotion
import pt.tiago.nightfleet.ui.screens.settings.weatherActive
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The sea map: the missions of a sea on a path between islands, with stars, type and lock. The
 * difficulty switch and the arrows move between the seas of the same difficulty (the three difficulties progress
 * separately). An unfinished campaign battle or puzzle of this sea has a resume entry above the chart.
 */
@Composable
fun SeaMapScreen(
    container: AppContainer,
    sea: Int,
    difficulty: Difficulty,
    onMission: (mission: Int) -> Unit,
    onSea: (sea: Int, difficulty: Difficulty) -> Unit,
    onBack: () -> Unit,
) {
    var profile by remember(sea) { mutableStateOf<Profile?>(null) }
    var missions by remember(sea) { mutableStateOf<List<MissionDto>>(emptyList()) }
    var resume by remember(sea, difficulty) { mutableStateOf<Int?>(null) }
    LaunchedEffect(sea, difficulty) {
        val loaded = container.profile.load()
        profile = loaded
        missions = (container.catalog.missionsForSea(sea) as? CatalogResult.Ok)?.value.orEmpty()
        resume = runCatching { container.matches.activeMatch(MatchKind.CAMPAIGN, loaded) }.getOrNull()
            ?.takeIf { it.difficulty == difficulty }
            ?.missionId
            ?.let(Seas::parseMissionId)
            ?.mission
            ?.takeIf { CampaignRules.seaOf(it) == sea }
    }
    val settings by container.settings.settings.collectAsState(initial = Settings())
    val weather by produceState(WeatherKind.CALM, sea) {
        value = withContext(Dispatchers.IO) { seaWeatherOf(container.catalog, sea) }
    }
    val reduced = settings.reducedMotion.resolveReducedMotion(systemReducedMotion(LocalContext.current))
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val loaded = profile
    val current = loaded?.let { CampaignRules.currentMission(it, difficulty) }

    Box(Modifier.fillMaxSize()) {
        SeaWeatherBackdrop(weather, enabled = settings.weatherActive(reduced), modifier = Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.campaign_back)) }
            }
            Text(stringResource(Seas.nameRes(sea)), style = type.seaName, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.campaign_map_stars, loaded?.let { CampaignRules.seaStars(it, sea, difficulty) } ?: 0),
                style = type.caption,
                color = colors.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Difficulty.entries.forEach { option ->
                    DifficultySegment(
                        selected = option == difficulty,
                        label = stringResource(difficultyRes(option)),
                        onClick = { onSea(sea, option) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SeaArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    description = stringResource(R.string.campaign_map_previous_sea),
                    enabled = sea > 1,
                    onClick = { onSea(sea - 1, difficulty) },
                )
                resume?.let { mission ->
                    AssistChip(
                        onClick = { onMission(mission) },
                        label = { Text(stringResource(R.string.campaign_map_resume, mission)) },
                    )
                }
                SeaArrow(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    description = stringResource(R.string.campaign_map_next_sea),
                    enabled = sea < Seas.COUNT,
                    onClick = { onSea(sea + 1, difficulty) },
                )
            }
            if (loaded != null) {
                val numbers = (CampaignRules.firstMissionOf(sea)..CampaignRules.bossOf(sea)).toList()
                val layout = remember(sea) { SeaPathLayout.build(sea, numbers) }
                val nodes = numbers.map { mission ->
                    val kind = missions.firstOrNull { it.ordinal == mission }
                    PathNodeUi(
                        mission = mission,
                        unlocked = CampaignRules.isUnlocked(loaded, mission, difficulty),
                        stars = CampaignRules.stars(loaded, mission, difficulty),
                        kind = kind?.type,
                        tags = kind?.tags.orEmpty(),
                        boss = CampaignRules.isBoss(mission),
                    )
                }
                SeaPathChart(
                    layout = layout,
                    nodes = nodes,
                    current = current,
                    onMission = onMission,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** Stars as glyphs: filled for earned, hollow for missing. Stars are read by TalkBack from the label. */
internal fun starsText(stars: Int): String = buildString {
    repeat(3) { index -> append(if (index < stars) "★" else "☆") }
}

/** A sea arrow: accent when it can move, a dimmed text tone when it cannot. 48 dp touch target. */
@Composable
private fun SeaArrow(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colors.accent,
            disabledContentColor = colors.textMuted.copy(alpha = 0.4f),
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** One segment of the difficulty switch: the accent fill with onAccent text when selected, a line border otherwise. */
@Composable
private fun DifficultySegment(selected: Boolean, label: String, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shape = RoundedCornerShape(LocalFleetShapes.current.button)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(1.dp, if (selected) colors.accent else colors.line, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = type.button, color = if (selected) colors.onAccent else colors.text)
    }
}
