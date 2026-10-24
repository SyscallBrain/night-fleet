package pt.tiago.nightfleet.ui.screens.home

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.nightfleet.BuildConfig
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.campaign.CampaignHome
import pt.tiago.nightfleet.game.campaign.CampaignRules
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.common.rankNameRes
import pt.tiago.nightfleet.ui.screens.campaign.difficultyRes
import pt.tiago.nightfleet.ui.theme.FleetShapes
import pt.tiago.nightfleet.ui.theme.FleetType
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/** What Home shows that the campaign does not give: texts resolved by the caller. */
data class HomeTexts(
    /** Title of the mission to play next (empty when the campaign is complete). */
    val missionTitle: String,
    /** Name of the admiral of the sea. */
    val admiralName: String,
    /** The daily row caption: today's best stars of the puzzle and the hunt (see DailyChallenge.today). */
    val dailyCaption: String = "",
)

/**
 * Home screen: the current sea with its route, Continue, the modes and the logbook.
 * The numbers come from [CampaignHome], computed from the profile. [onBrandLongPress] is debug only.
 */
@Composable
fun HomeScreen(
    home: CampaignHome,
    texts: HomeTexts,
    hasSavedCampaign: Boolean,
    onContinue: () -> Unit,
    onSeaMap: () -> Unit,
    onDaily: () -> Unit,
    onDuel: () -> Unit,
    onFreePlay: () -> Unit,
    onMoreModes: () -> Unit,
    onLogbook: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBoardGallery: () -> Unit = {},
) {
    val colors = LocalFleetColors.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        val compact = maxWidth < 360.dp
        val gutter = if (compact) 16.dp else 20.dp
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HomeHeader(home = home, compact = compact, onProfile = onProfile, onLongPressBrand = onOpenBoardGallery)
                SeaCard(home = home, texts = texts, onClick = onSeaMap)
                ContinueButton(home = home, texts = texts, saved = hasSavedCampaign, onClick = onContinue)
                ModeRows(
                    dailyCaption = texts.dailyCaption,
                    onDaily = onDaily,
                    onDuel = onDuel,
                    onFreePlay = onFreePlay,
                    onMoreModes = onMoreModes,
                )
                LogbookSettingsGrid(onLogbook = onLogbook, onSettings = onSettings)
            }
        }
    }
}

@Composable
private fun HomeHeader(home: CampaignHome, compact: Boolean, onProfile: () -> Unit, onLongPressBrand: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val brand: TextStyle = if (compact) type.brand.copy(fontSize = 36.sp, lineHeight = 34.sp) else type.brand
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .weight(1f)
                // Debug builds only: a long-press on the brand opens the board gallery (see MainActivity).
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { if (BuildConfig.DEBUG) onLongPressBrand() })
                },
        ) {
            Text(stringResource(R.string.brand_night), style = brand, color = colors.text)
            Text(stringResource(R.string.brand_fleet), style = brand, color = colors.accent)
            Text(stringResource(R.string.home_subtitle), style = type.bodyText, color = colors.textMuted)
        }
        Surface(
            onClick = onProfile,
            shape = RoundedCornerShape(LocalFleetShapes.current.button),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.line),
        ) {
            Text(
                stringResource(R.string.home_rank_chip, stringResource(rankNameRes(home.rank)), home.totalStars),
                style = type.caption,
                color = colors.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SeaCard(home: CampaignHome, texts: HomeTexts, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val mission = home.currentMission ?: home.bossMission
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(shapes.card),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.home_sea_label, home.sea, Seas.COUNT),
                style = type.label,
                color = colors.textMuted,
            )
            Text(stringResource(Seas.nameRes(home.sea)), style = type.seaName, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(
                    R.string.home_sea_progress,
                    mission,
                    CampaignRules.TOTAL_MISSIONS,
                    home.seaStars,
                    CampaignRules.MISSIONS_PER_SEA * 3,
                ),
                style = type.bodyText,
                color = colors.textMuted,
            )
            val start = home.routeStart()
            SeaRoute(
                nodeCount = CampaignHome.ROUTE_NODES,
                currentIndex = mission - start,
                description = stringResource(R.string.home_sea_route, mission, CampaignRules.TOTAL_MISSIONS),
            )
            ProgressBar(fraction = home.seaStars / (CampaignRules.MISSIONS_PER_SEA * 3f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DifficultyChip(stringResource(difficultyRes(home.difficulty)), difficultyColor(home.difficulty))
                Text(
                    stringResource(R.string.home_sea_next, texts.admiralName, home.bossMission),
                    style = type.caption,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun difficultyColor(difficulty: Difficulty): Color {
    val colors = LocalFleetColors.current
    return when (difficulty) {
        Difficulty.EASY -> colors.difficultyEasy
        Difficulty.MEDIUM -> colors.difficultyMedium
        Difficulty.HARD -> colors.difficultyHard
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val colors = LocalFleetColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.surface2, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(colors.accent, CircleShape),
        )
    }
}

@Composable
private fun DifficultyChip(label: String, color: Color) {
    val type = LocalFleetType.current
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, style = type.caption, color = color)
    }
}

@Composable
private fun ContinueButton(home: CampaignHome, texts: HomeTexts, saved: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes: FleetShapes = LocalFleetShapes.current
    val type: FleetType = LocalFleetType.current
    val mission = home.currentMission
    Button(
        onClick = onClick,
        enabled = mission != null || saved,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(shapes.button),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.home_continue), style = type.button)
            Text(
                if (mission != null) {
                    stringResource(R.string.home_continue_caption, mission, texts.missionTitle)
                } else {
                    stringResource(R.string.home_campaign_complete)
                },
                style = type.caption,
            )
        }
    }
}

@Composable
private fun ModeRows(
    dailyCaption: String,
    onDaily: () -> Unit,
    onDuel: () -> Unit,
    onFreePlay: () -> Unit,
    onMoreModes: () -> Unit,
) {
    ModeRow(R.string.home_row_daily, dailyCaption, onClick = onDaily)
    ModeRow(R.string.home_row_duel, stringResource(R.string.home_row_duel_caption), onClick = onDuel)
    ModeRow(R.string.home_row_free, stringResource(R.string.home_row_free_caption), onClick = onFreePlay)
    ModeRow(R.string.home_row_more, stringResource(R.string.home_row_more_caption), onClick = onMoreModes)
}

@Composable
private fun ModeRow(titleRes: Int, caption: String, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(shapes.card),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(titleRes), style = type.button, color = colors.text)
                Text(caption, style = type.caption, color = colors.textMuted)
            }
        }
    }
}

@Composable
private fun LogbookSettingsGrid(onLogbook: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GridCard(R.string.home_grid_logbook, onLogbook, Modifier.weight(1f))
        GridCard(R.string.home_grid_settings, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun GridCard(titleRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(shapes.card),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            Text(stringResource(titleRes), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        }
    }
}
