package pt.tiago.nightfleet.ui.screens.modes

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/** The page of a mode's setup, list or result: gradient, safe insets, a back button and a scrolling column. */
@Composable
internal fun ModeScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.mode_back), style = type.button, color = colors.text)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The main action of a mode screen (accent) or a secondary one (outlined). */
@Composable
internal fun ModeButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    if (primary) {
        Surface(
            shape = RoundedCornerShape(LocalFleetShapes.current.button),
            color = colors.accent,
            contentColor = colors.onAccent,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Text(
                text,
                style = type.button,
                color = colors.onAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            )
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text, style = type.button, color = colors.text)
        }
    }
}

/** A card of a list: a title, a caption and a tap target. */
@Composable
internal fun ModeCard(title: String, caption: String, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    Surface(
        shape = RoundedCornerShape(shapes.card),
        color = colors.surface,
        contentColor = colors.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            Text(caption, style = type.caption, color = colors.textMuted)
        }
    }
}

/** The difficulty's name, as the setup chips show it. */
@Composable
internal fun difficultyLabel(difficulty: Difficulty): String = stringResource(
    when (difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    },
)
