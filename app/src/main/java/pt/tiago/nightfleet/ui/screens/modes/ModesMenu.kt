package pt.tiago.nightfleet.ui.screens.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R

/** The "More modes" list of Home: the endless puzzles, the patrol and the tournament. */
@Composable
fun ModesMenuScreen(
    onEndless: () -> Unit,
    onPatrol: () -> Unit,
    onTournament: () -> Unit,
    onBack: () -> Unit,
) {
    ModeScaffold(title = stringResource(R.string.modes_title), onBack = onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeCard(
                title = stringResource(R.string.kind_endless),
                caption = stringResource(R.string.modes_endless_caption),
                onClick = onEndless,
            )
            ModeCard(
                title = stringResource(R.string.modes_patrol_title),
                caption = stringResource(R.string.modes_patrol_caption),
                onClick = onPatrol,
            )
            ModeCard(
                title = stringResource(R.string.modes_tournament_title),
                caption = stringResource(R.string.modes_tournament_caption),
                onClick = onTournament,
            )
        }
    }
}
