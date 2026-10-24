package pt.tiago.nightfleet.ui.screens.gallery

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.ui.intro.IntroCardList
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.meta.Admiral
import pt.tiago.nightfleet.meta.Admirals
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.meta.ShipEntry
import pt.tiago.nightfleet.meta.Ships
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The Gallery: the twelve admirals, the eight ship classes and the mechanics manual.
 * An admiral is a silhouette until met. "Play against" opens a free play match; it is passed to
 * [onPlayAgainst] as the admiral id, because the free play screen is wired in the navigation graph.
 */
@Composable
fun GalleryRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onPlayAgainst: (admiralId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by container.profile.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    MetaScreen(title = stringResource(R.string.gal_title), onBack = onBack, modifier = modifier, scrollable = false) {
        TabRow(selectedTabIndex = tab, containerColor = LocalFleetColors.current.surface, contentColor = LocalFleetColors.current.accent) {
            listOf(R.string.gal_admirals, R.string.gal_ships, R.string.gal_manual).forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(label), style = LocalFleetType.current.caption) },
                )
            }
        }
        when (tab) {
            0 -> AdmiralList(profile, onPlayAgainst)
            1 -> ShipList(profile)
            else -> Box(Modifier.weight(1f)) { IntroCardList() }
        }
    }
}

@Composable
private fun ColumnScope.AdmiralList(profile: Profile, onPlayAgainst: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(Admirals.all) { index, admiral ->
            AdmiralCard(index, admiral, profile, onPlay = { onPlayAgainst(admiral.id) })
        }
    }
}

@Composable
private fun AdmiralCard(index: Int, admiral: Admiral, profile: Profile, onPlay: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val met = admiral.id in profile.metAdmirals
    val defeated = profile.defeatedAdmirals.values.any { admiral.id in it }
    val unknown = stringResource(R.string.gal_unknown)
    MetaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AdmiralPortrait(index = index, silhouette = !met, size = 80.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (met) stringResource(admiral.name) else unknown,
                    style = type.cardTitle,
                    color = colors.text,
                )
                Text(
                    if (met) stringResource(Seas.nameRes(admiral.sea)) else unknown,
                    style = type.caption,
                    color = colors.textMuted,
                )
                if (met) {
                    Text(stringResource(admiral.style), style = type.caption, color = colors.textMuted)
                }
            }
        }
        if (met) {
            if (defeated) {
                Text(stringResource(admiral.quote), style = type.bodyText, color = colors.accent)
            }
            Text(stringResource(R.string.gal_record_title), style = type.label, color = colors.textMuted)
            Difficulty.entries.forEach { difficulty ->
                val record = profile.bossSeries[variantKey(Seas.bossMissionId(admiral.sea), difficulty)]
                Text(
                    stringResource(
                        R.string.gal_record,
                        stringResource(difficultyName(difficulty)),
                        record?.wins ?: 0,
                        record?.losses ?: 0,
                    ),
                    style = type.caption,
                    color = colors.textMuted,
                )
            }
            Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.surface2, contentColor = colors.text),
            ) {
                Text(stringResource(R.string.gal_play_against), style = type.button)
            }
        }
    }
}

@Composable
private fun ColumnScope.ShipList(profile: Profile) {
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Ships.all, key = { it.shipClass.name }) { entry ->
            ShipCard(entry, sunk = profile.stats.values.sumOf { it.shipsSunkByClass[entry.shipClass.name] ?: 0 })
        }
    }
}

@Composable
private fun ShipCard(entry: ShipEntry, sunk: Int) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    MetaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ShipDrawing(entry.shipClass, width = 96.dp, height = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(entry.name), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                Text(
                    pluralStringResource(R.plurals.gal_ship_size, entry.shipClass.size, entry.shipClass.size),
                    style = type.caption,
                    color = colors.textMuted,
                )
            }
        }
        Text(stringResource(entry.fact), style = type.bodyText, color = colors.textMuted)
        Text(
            pluralStringResource(R.plurals.stat_sunk_count, sunk, sunk),
            style = type.caption,
            color = colors.accent,
        )
    }
}

private fun difficultyName(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> R.string.difficulty_easy
    Difficulty.MEDIUM -> R.string.difficulty_medium
    Difficulty.HARD -> R.string.difficulty_hard
}
