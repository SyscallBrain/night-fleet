package pt.tiago.nightfleet.ui.screens.daily

import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.data.ChallengeKind
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.daily.DailyChallenge
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import java.time.LocalDate

/**
 * The daily challenge: one puzzle and one hunt for the chosen difficulty, with the best stars of today,
 * and a strip of the last 14 days. Nothing here unlocks campaign content.
 */
@Composable
fun DailyScreen(
    container: AppContainer,
    /** The day this screen opened on. Results are keyed by it, so a clock change never moves them. */
    date: LocalDate,
    onBack: () -> Unit,
    onPlayPuzzle: (Difficulty) -> Unit,
    onPlayHunt: (Difficulty) -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.MEDIUM) }
    var profile by remember { mutableStateOf(Profile()) }
    var hasPuzzle by remember { mutableStateOf(true) }
    var hasHunt by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        profile = runCatching { container.profile.load() }.getOrDefault(profile)
        hasPuzzle = (container.catalog.dailyPool() as? CatalogResult.Ok)?.value?.let { pool ->
            DailyChallenge.entries(pool, DailyKind.PUZZLE, difficulty).isNotEmpty()
        } ?: false
        hasHunt = (container.catalog.dailyPool() as? CatalogResult.Ok)?.value?.let { pool ->
            DailyChallenge.entries(pool, DailyKind.HUNT, difficulty).isNotEmpty()
        } ?: false
    }
    val puzzleStars = DailyChallenge.bestStars(profile, date, difficulty, ChallengeKind.PUZZLE)
    val huntStars = DailyChallenge.bestStars(profile, date, difficulty, ChallengeKind.HUNT)
    val strip = DailyChallenge.strip(profile, date, difficulty)

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.daily_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(date.toString(), style = type.bodyText, color = colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD).forEach { level ->
                    val selected = level == difficulty
                    val label = difficultyText(level)
                    OutlinedButton(
                        onClick = { difficulty = level },
                        modifier = Modifier.heightIn48(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(shapes.button),
                    ) {
                        Text(
                            label + if (selected) " ✓" else "",
                            style = type.button,
                            color = if (selected) colors.accent else colors.text,
                        )
                    }
                }
            }
            ChallengeCard(
                title = stringResource(R.string.daily_card_puzzle),
                stars = puzzleStars,
                available = hasPuzzle,
                playLabel = if (puzzleStars > 0) stringResource(R.string.daily_play_again) else stringResource(R.string.daily_play),
                onPlay = { onPlayPuzzle(difficulty) },
            )
            ChallengeCard(
                title = stringResource(R.string.daily_card_hunt),
                stars = huntStars,
                available = hasHunt,
                playLabel = if (huntStars > 0) stringResource(R.string.daily_play_again) else stringResource(R.string.daily_play),
                onPlay = { onPlayHunt(difficulty) },
            )
            Text(stringResource(R.string.daily_strip_title), style = type.bodyText, color = colors.textMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                strip.forEach { day ->
                    val best = maxOf(day.puzzle, day.hunt)
                    val dayText = stringResource(R.string.daily_day_label, day.date.toString(), day.puzzle, day.hunt)
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(shapes.button))
                            .background(if (best > 0) colors.surface2 else colors.bg2)
                            .padding(vertical = 6.dp)
                            .semantics {
                                contentDescription = dayText
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(day.date.dayOfMonth.toString(), style = type.bodyText, color = colors.text, textAlign = TextAlign.Center)
                        Text(
                            if (best > 0) "★".repeat(best) else "·",
                            style = type.bodyText,
                            color = colors.accent,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn48()) {
                Text(stringResource(R.string.campaign_back), style = type.button)
            }
        }
    }
}

@Composable
private fun ChallengeCard(title: String, stars: Int, available: Boolean, playLabel: String, onPlay: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(shapes.card))
            .background(colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.daily_best_today, stars),
            style = type.bodyText,
            color = colors.textMuted,
        )
        Button(
            onClick = onPlay,
            enabled = available,
            modifier = Modifier.fillMaxWidth().heightIn56(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(shapes.button),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) {
            Text(
                if (available) playLabel else stringResource(R.string.daily_unavailable),
                style = type.button,
            )
        }
    }
}

private fun Modifier.heightIn48(): Modifier = heightIn(min = 48.dp)
private fun Modifier.heightIn56(): Modifier = heightIn(min = 56.dp)

@Composable
private fun difficultyText(difficulty: Difficulty): String = stringResource(
    when (difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    },
)
