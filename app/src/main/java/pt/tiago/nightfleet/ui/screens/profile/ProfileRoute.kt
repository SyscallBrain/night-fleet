package pt.tiago.nightfleet.ui.screens.profile

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.hardBossesWon
import pt.tiago.nightfleet.data.totalStars
import pt.tiago.nightfleet.meta.Achievements
import pt.tiago.nightfleet.meta.PaintCatalog
import pt.tiago.nightfleet.meta.rankProgress
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaProgressBar
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.common.RankBadge
import pt.tiago.nightfleet.ui.common.rankNameRes
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The profile hub, opened from the rank chip on Home: the rank card and links to the Gallery,
 * the Achievements and the Statistics. Wiring: `ProfileRoute(container, onBack, onOpenGallery = ..., ...)`.
 */
@Composable
fun ProfileRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenStatistics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by container.profile.state.collectAsState()
    MetaScreen(title = stringResource(R.string.prof_title), onBack = onBack, modifier = modifier) {
        RankCard(profile)
        ProfileLink(
            title = stringResource(R.string.prof_gallery),
            subtitle = stringResource(R.string.prof_gallery_sub),
            onClick = onOpenGallery,
        )
        ProfileLink(
            title = stringResource(R.string.prof_achievements),
            subtitle = pluralStringResource(
                R.plurals.prof_achievements_sub,
                Achievements.all.count { profile.achievements[it.id]?.unlockedAtEpochDay != null },
                Achievements.all.count { profile.achievements[it.id]?.unlockedAtEpochDay != null },
                Achievements.all.size,
            ),
            onClick = onOpenAchievements,
        )
        ProfileLink(
            title = stringResource(R.string.prof_statistics),
            subtitle = stringResource(R.string.prof_statistics_sub),
            onClick = onOpenStatistics,
        )
        Text(
            stringResource(R.string.prof_paints, PaintCatalog.unlockedIds(profile).size - 1, PaintCatalog.unlockable),
            style = LocalFleetType.current.caption,
            color = LocalFleetColors.current.textMuted,
        )
    }
}

@Composable
private fun RankCard(profile: Profile) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val stars = profile.totalStars()
    val bosses = profile.hardBossesWon()
    val progress = rankProgress(stars, bosses)
    val rankName = stringResource(rankNameRes(progress.rank))
    MetaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RankBadge(rank = progress.rank, name = rankName, size = 72.dp)
            Column(Modifier.weight(1f)) {
                Text(rankName, style = type.seaName, color = colors.text, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.prof_total, stars), style = type.bodyText, color = colors.textMuted)
            }
        }
        MetaProgressBar(fraction = progress.fraction)
        val next = progress.next
        Text(
            when {
                next == null -> stringResource(R.string.prof_grand)
                progress.needsBossesOnly -> pluralStringResource(
                    R.plurals.prof_next_bosses,
                    progress.hardBossesNeeded - progress.hardBossesWon,
                    progress.hardBossesNeeded - progress.hardBossesWon,
                    stringResource(rankNameRes(next)),
                )
                else -> stringResource(R.string.prof_next, progress.starsToNext, stringResource(rankNameRes(next)))
            },
            style = type.caption,
            color = colors.textMuted,
        )
        Text(
            stringResource(R.string.prof_hard_bosses, bosses, progress.hardBossesNeeded),
            style = type.caption,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun ProfileLink(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    MetaCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                Text(subtitle, style = type.caption, color = colors.textMuted)
            }
        }
    }
}
