package pt.tiago.nightfleet.ui.screens.pause

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The pause sheet: the board darkens behind an opaque sheet with
 * continue, restart, quick settings (sound, music, vibration) and leave to home.
 */
@Composable
fun PauseSheet(
    settings: Settings,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
    onLeave: () -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = 0.72f))
            .clickable(interactionSource = interaction, indication = null, onClick = onContinue),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = shapes.sheet, topEnd = shapes.sheet))
                .clickable(interactionSource = interaction, indication = null) { }
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.pause_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.pause_continue), style = type.button) }
            OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pause_restart)) }

            Text(stringResource(R.string.pause_quick_settings), style = type.label, color = colors.textMuted)
            ToggleRow(stringResource(R.string.pause_sfx), settings.sfxVolume > 0f) { on ->
                onSettings { it.copy(sfxVolume = if (on) 0.8f else 0f) }
            }
            ToggleRow(stringResource(R.string.pause_music), settings.musicVolume > 0f) { on ->
                onSettings { it.copy(musicVolume = if (on) 0.4f else 0f) }
            }
            ToggleRow(stringResource(R.string.pause_vibration), settings.vibration) { on ->
                onSettings { it.copy(vibration = on) }
            }

            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pause_leave)) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.bodyText, color = colors.text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
