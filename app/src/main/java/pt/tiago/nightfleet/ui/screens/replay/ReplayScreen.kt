package pt.tiago.nightfleet.ui.screens.replay

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.game.replay.MatchReplay
import pt.tiago.nightfleet.game.replay.ReplayRender
import pt.tiago.nightfleet.ui.board.MiniBoard
import pt.tiago.nightfleet.ui.board.SeaBoard
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId

/** Time between two steps while the replay plays. */
private const val REPLAY_STEP_MS = 700L

/**
 * The replay of a battle, turn by turn, with both seas in view. Play and pause, next and
 * previous, start and end. Every step is rebuilt from the logbook payload by [MatchReplay], so going back is just
 * another step and never changes the battle.
 */
@Composable
fun ReplayScreen(
    payload: JsonObject,
    themeId: ThemeId,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val replay = remember(payload) { MatchReplay.fromPayload(payload) }
    var step by rememberSaveable(payload) { mutableIntStateOf(0) }
    var playing by remember(payload) { mutableStateOf(false) }
    LaunchedEffect(playing, step) {
        if (!playing) return@LaunchedEffect
        val total = replay?.steps ?: 0
        if (step >= total) {
            playing = false
        } else {
            delay(REPLAY_STEP_MS)
            step += 1
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.freeplay_back)) }
            Text(stringResource(R.string.replay_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        }
        if (replay == null) {
            Text(stringResource(R.string.analysis_error), style = type.bodyText, color = colors.danger)
            return@Column
        }
        val total = replay.steps
        val current = step.coerceIn(0, total)
        val seas = remember(replay, current) { ReplayRender.seasAt(replay, current) }
        Text(stringResource(R.string.replay_turn, current, total), style = type.caption, color = colors.textMuted)
        SeaBoard(model = seas.enemy, themeId = themeId, showCoordinates = true, interactive = false, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniBoard(model = seas.own, themeId = themeId)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { playing = false; step = 0 }, enabled = current > 0) { Text(stringResource(R.string.replay_start)) }
            OutlinedButton(onClick = { playing = false; step = current - 1 }, enabled = current > 0) {
                Text(stringResource(R.string.replay_previous))
            }
            OutlinedButton(
                onClick = {
                    if (current >= total) step = 0
                    playing = !playing
                },
                enabled = total > 0,
            ) {
                Text(stringResource(if (playing) R.string.replay_pause else R.string.replay_play))
            }
            OutlinedButton(onClick = { playing = false; step = current + 1 }, enabled = current < total) {
                Text(stringResource(R.string.replay_next))
            }
            OutlinedButton(onClick = { playing = false; step = total }, enabled = current < total) { Text(stringResource(R.string.replay_end)) }
        }
    }
}
