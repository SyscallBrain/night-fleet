package pt.tiago.nightfleet.ui.intro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.ui.board.LocalReducedMotion
import pt.tiago.nightfleet.ui.board.SeaBoard
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.tutorial.namedString

/**
 * The content of an intro card: the animated illustration (static with reduced motion), the title, the two
 * sentences and the "Got it" button. Put it in a bottom sheet, or use [IntroCardList], which opens it.
 * [themeId] picks the look of the board in the illustration; the colours come from the surrounding FleetTheme.
 */
@Composable
fun IntroCardSheet(
    mechanic: MechanicIntro,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    themeId: ThemeId = ThemeId.CHART,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val scene = remember(mechanic) { IntroScenes.of(mechanic) }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.intro_kicker),
            style = type.label,
            color = colors.accent,
        )
        Spacer(Modifier.heightIn(min = 8.dp))
        IntroIllustration(
            scene = scene,
            themeId = themeId,
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
        )
        Spacer(Modifier.heightIn(min = 16.dp))
        Text(
            text = namedString(mechanic.titleKey),
            style = type.screenTitle,
            color = colors.text,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(text = namedString(mechanic.firstSentenceKey), style = type.bodyText, color = colors.text)
        Spacer(Modifier.heightIn(min = 6.dp))
        Text(text = namedString(mechanic.secondSentenceKey), style = type.bodyText, color = colors.textMuted)
        Spacer(Modifier.heightIn(min = 20.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(text = stringResource(R.string.intro_got_it), style = type.button)
        }
    }
}

/**
 * The illustration of a scene: the sample sea with its effects, looping every [IntroScene.loopMillis]. With
 * reduced motion it is the final frame with no effects.
 */
@Composable
internal fun IntroIllustration(
    scene: IntroScene,
    themeId: ThemeId,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    var cycle by remember(scene) { mutableIntStateOf(0) }
    LaunchedEffect(scene, reduced) {
        if (reduced || scene.effects.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(scene.loopMillis)
            // A new seed makes the effect list differ, so the board restarts its clock for the next loop.
            cycle++
        }
    }
    val effects = if (reduced) {
        emptyList()
    } else {
        scene.effects.map { it.copy(seed = it.seed + cycle) }
    }
    SeaBoard(
        model = scene.model,
        themeId = themeId,
        modifier = modifier,
        showCoordinates = false,
        effects = effects,
        interactive = false,
    )
}

/**
 * The "Manual" of the Gallery: one row per mechanic, each opening its [IntroCardSheet] in a bottom sheet.
 * Give it a bounded height (it scrolls).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroCardList(
    modifier: Modifier = Modifier,
    themeId: ThemeId = ThemeId.CHART,
) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    var opened by remember { mutableStateOf<MechanicIntro?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MechanicIntro.entries, key = { it.name }) { mechanic ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(colors.surface, RoundedCornerShape(shapes.card))
                    .clickable { opened = mechanic }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = namedString(mechanic.titleKey),
                    style = type.cardTitle,
                    color = colors.text,
                )
            }
        }
    }
    opened?.let { mechanic ->
        ModalBottomSheet(onDismissRequest = { opened = null }) {
            IntroCardSheet(
                mechanic = mechanic,
                onDismiss = { opened = null },
                themeId = themeId,
            )
        }
    }
}
