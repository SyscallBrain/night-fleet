package pt.tiago.nightfleet.ui.screens.achievements

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.meta.AchievementEvaluator
import pt.tiago.nightfleet.meta.AchievementFilter
import pt.tiago.nightfleet.meta.AchievementView
import pt.tiago.nightfleet.ui.common.EmptyState
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaProgressBar
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.common.TagChip
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The achievements with their progress bars and a filter. Secret achievements stay
 * hidden until they are unlocked. Wiring: `AchievementsRoute(container = container, onBack = ...)`.
 */
@Composable
fun AchievementsRoute(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val profile by container.profile.state.collectAsState()
    var filter by remember { mutableStateOf(AchievementFilter.ALL) }
    val views = remember(profile, filter) { AchievementEvaluator.list(profile, filter) }
    val unlockedCount = remember(profile) { AchievementEvaluator.list(profile).count { it.unlocked } }
    val total = remember(profile) { AchievementEvaluator.list(profile).size }

    MetaScreen(title = stringResource(R.string.ach_title), onBack = onBack, modifier = modifier, scrollable = false) {
        Text(
            pluralStringResource(R.plurals.ach_summary, unlockedCount, unlockedCount, total),
            style = LocalFleetType.current.caption,
            color = LocalFleetColors.current.textMuted,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AchievementFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(stringResource(filterLabel(option)), style = LocalFleetType.current.caption) },
                )
            }
        }
        if (views.isEmpty()) {
            EmptyState(stringResource(R.string.ach_empty))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(views, key = { it.def.id }) { view -> AchievementRow(view) }
            }
        }
    }
}

@Composable
private fun AchievementRow(view: AchievementView) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val hidden = view.def.secret && !view.unlocked
    MetaCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (hidden) R.string.ach_secret_title else view.def.title),
                style = type.cardTitle,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            TagChip(
                text = stringResource(if (view.unlocked) R.string.ach_unlocked else R.string.ach_locked),
                color = if (view.unlocked) colors.accent else colors.textMuted,
            )
        }
        Text(
            stringResource(if (hidden) R.string.ach_secret_desc else view.def.description),
            style = type.bodyText,
            color = colors.textMuted,
        )
        if (!hidden) {
            MetaProgressBar(fraction = view.fraction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${view.value} / ${view.def.target}", style = type.coordinate, color = colors.textMuted)
                if (view.def.perDifficulty) {
                    Text(stringResource(R.string.ach_per_difficulty), style = type.caption, color = colors.textMuted)
                }
            }
        }
    }
}

private fun filterLabel(filter: AchievementFilter): Int = when (filter) {
    AchievementFilter.ALL -> R.string.ach_filter_all
    AchievementFilter.UNLOCKED -> R.string.ach_filter_unlocked
    AchievementFilter.LOCKED -> R.string.ach_filter_locked
}
