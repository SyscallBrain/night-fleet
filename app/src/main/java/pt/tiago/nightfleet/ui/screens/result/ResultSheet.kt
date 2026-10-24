package pt.tiago.nightfleet.ui.screens.result

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.game.campaign.NextStar
import pt.tiago.nightfleet.game.campaign.ResultExtras
import pt.tiago.nightfleet.ui.common.RankBadge
import pt.tiago.nightfleet.ui.common.rankNameRes
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

private const val STAR_STEP_MS = 300L

/**
 * The result of a battle. A win lights the stars one by one, 300 ms apart (at once
 * with reduced motion). A loss is neutral: no large red, the enemy ships left afloat are already drawn on the sea.
 *
 * [extras] carries the campaign rules (stars, next star, boss series), the achievements unlocked by the battle (at most
 * three lines), the rank change (with its badge) and a paint unlocked. Without it, the summary alone decides.
 */
@Composable
fun ResultSheet(
    summary: QuickBattle.Summary,
    onPlayAgain: () -> Unit,
    onHome: () -> Unit,
    reducedMotion: Boolean,
    onAnalysis: () -> Unit = {},
    extras: ResultExtras? = null,
) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val interaction = remember { MutableInteractionSource() }
    val stars = extras?.stars ?: summary.stars
    val series = extras?.series
    val draw = summary.reason == EndReason.TURN_LIMIT
    val won = summary.won
    var lit by remember(summary, extras) { mutableIntStateOf(if (reducedMotion) stars else 0) }
    LaunchedEffect(summary, extras) {
        if (reducedMotion) return@LaunchedEffect
        while (lit < stars) {
            delay(STAR_STEP_MS)
            lit++
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.72f))
            .clickable(interactionSource = interaction, indication = null) { },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = shapes.sheet, topEnd = shapes.sheet))
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val replay = extras?.drawReplay == true
            val boss = series != null
            val starsShown = won && (series == null || series.decided) && (extras?.showStars ?: true)
            when {
                replay -> {
                    Text(stringResource(R.string.result_draw), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.result_draw_replay), style = type.bodyText, color = colors.text)
                }
                draw && !boss -> {
                    Text(stringResource(R.string.result_draw), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.result_draw_text), style = type.caption, color = colors.textMuted)
                }
                starsShown -> {
                    Text(stringResource(R.string.result_victory), style = type.screenTitle, color = colors.accent, modifier = Modifier.semantics { heading() })
                    StarRow(stars = stars, lit = lit, colors = colors.accent, muted = colors.surface2)
                }
                boss && won -> Text(stringResource(R.string.result_duel_won), style = type.screenTitle, color = colors.accent, modifier = Modifier.semantics { heading() })
                boss -> Text(stringResource(R.string.result_duel_lost), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                else -> {
                    Text(stringResource(R.string.result_defeat), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(R.string.result_defeat_count, summary.enemyShipsSunk, summary.enemyShipsTotal),
                        style = type.bodyText,
                        color = colors.text,
                    )
                }
            }
            if (series != null) {
                Text(
                    stringResource(R.string.result_series_score, series.wins, series.losses),
                    style = type.bodyText,
                    color = colors.text,
                )
                if (!series.decided && !replay) {
                    Text(stringResource(R.string.result_series_open), style = type.caption, color = colors.textMuted)
                }
            }
            if (won || draw) {
                Text(
                    pluralStringResource(R.plurals.result_precision, summary.shots, summary.precision, summary.shots),
                    style = type.bodyText,
                    color = colors.text,
                )
            } else if (!boss) {
                Text(
                    pluralStringResource(R.plurals.result_precision, summary.shots, summary.precision, summary.shots),
                    style = type.caption,
                    color = colors.textMuted,
                )
                Text(stringResource(R.string.result_revealed), style = type.caption, color = colors.textMuted)
            }
            if (starsShown) {
                NextStarLine(extras?.nextStar ?: quickNextStar(summary.stars), stars, type.caption, colors.textMuted)
            }
            extras?.modeLine?.let { Text(it, style = type.bodyText, color = colors.text) }
            extras?.let { AchievementLines(it, type.caption, colors.accent) }
            extras?.let { RankLine(it, type.caption, colors.text) }
            extras?.paintUnlocked?.let {
                Text(stringResource(R.string.result_paint_unlocked), style = type.caption, color = colors.accent)
            }
            Button(
                onClick = onPlayAgain,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    extras?.primaryLabel ?: stringResource(
                        when {
                            replay -> R.string.result_replay_duel
                            series != null && !series.decided -> R.string.result_next_duel
                            won -> R.string.result_play_again
                            else -> R.string.result_retry
                        },
                    ),
                    style = type.button,
                )
            }
            OutlinedButton(onClick = onAnalysis, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_analysis)) }
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_home)) }
        }
    }
}

@Composable
private fun StarRow(stars: Int, lit: Int, colors: Color, muted: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            Text("★", fontSize = 36.sp, color = if (i < lit && i < stars) colors else muted)
        }
    }
}

@Composable
private fun NextStarLine(next: NextStar, stars: Int, style: TextStyle, color: Color) {
    val text = when (next) {
        is NextStar.Shots -> pluralStringResource(R.plurals.result_next_star, next.shots, next.shots)
        NextStar.Freighters -> stringResource(R.string.result_next_star_freighters)
        NextStar.SinkEnemy -> stringResource(R.string.result_next_star_sink)
        NextStar.None -> if (stars >= 3) stringResource(R.string.result_three_stars) else null
    } ?: return
    Text(text, style = style, color = color)
}

@Composable
private fun AchievementLines(extras: ResultExtras, style: TextStyle, color: Color) {
    extras.achievements.take(ResultExtras.MAX_ACHIEVEMENT_LINES).forEach { def ->
        Text(stringResource(R.string.result_achievement, stringResource(def.title)), style = style, color = color)
    }
}

@Composable
private fun RankLine(extras: ResultExtras, style: TextStyle, color: Color) {
    if (!extras.rankChanged) return
    val rank: Rank = extras.rankAfter
    val name = stringResource(rankNameRes(rank))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RankBadge(rank = rank, name = name, size = 40.dp)
        Text(stringResource(R.string.result_rank_up, name), style = style, color = color)
    }
}

/** The next star of a quick battle (its own thresholds). */
private fun quickNextStar(stars: Int): NextStar =
    QuickBattle.nextStarShots(stars)?.let { NextStar.Shots(it) } ?: NextStar.None
