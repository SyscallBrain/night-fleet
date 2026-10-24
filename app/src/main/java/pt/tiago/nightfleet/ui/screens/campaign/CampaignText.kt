package pt.tiago.nightfleet.ui.screens.campaign

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.game.campaign.MissionTitles

/**
 * String resources of the campaign: a mission title is composed from its type and its tag,
 * so the wording lives in strings.xml and never in the content.
 */
object CampaignText {

    @StringRes
    fun kindRes(kind: MissionKind): Int = when (kind) {
        MissionKind.DUEL -> R.string.campaign_kind_duel
        MissionKind.HUNT -> R.string.campaign_kind_hunt
        MissionKind.CONVOY -> R.string.campaign_kind_convoy
        MissionKind.BOSS -> R.string.campaign_kind_boss
        MissionKind.PUZZLE -> R.string.campaign_kind_puzzle
    }

    @StringRes
    fun tagRes(tag: MissionTag): Int = when (tag) {
        MissionTag.ISLANDS -> R.string.campaign_tag_islands
        MissionTag.REEFS -> R.string.campaign_tag_reefs
        MissionTag.MINES -> R.string.campaign_tag_mines
        MissionTag.TIMED -> R.string.campaign_tag_timed
        MissionTag.SALVO -> R.string.campaign_tag_salvo
        MissionTag.DIVING -> R.string.campaign_tag_diving
        MissionTag.FOG -> R.string.campaign_tag_fog
        MissionTag.MOVING -> R.string.campaign_tag_moving
        MissionTag.SCHOOL -> R.string.campaign_tag_school
        MissionTag.LARGE -> R.string.campaign_tag_large
        MissionTag.SONAR -> R.string.campaign_tag_sonar
        MissionTag.TORPEDO -> R.string.campaign_tag_torpedo
        MissionTag.RECON -> R.string.campaign_tag_recon
        MissionTag.CROSS -> R.string.campaign_tag_cross
        MissionTag.DECOY -> R.string.campaign_tag_decoy
        MissionTag.REPAIR -> R.string.campaign_tag_repair
    }

    /** "Duel · Islands", or just "Duel" when the mission has no tag. */
    @Composable
    fun missionTitle(kind: MissionKind, tags: List<MissionTag>): String {
        val title = MissionTitles.of(kind, tags)
        val kindText = stringResource(kindRes(title.kind))
        val tag = title.tag ?: return kindText
        return stringResource(R.string.campaign_title_tagged, kindText, stringResource(tagRes(tag)))
    }

    /** The title for code that runs outside composition (loading in a coroutine). */
    fun titleOf(context: Context, kind: MissionKind, tags: List<MissionTag>): String {
        val title = MissionTitles.of(kind, tags)
        val kindText = context.getString(kindRes(title.kind))
        val tag = title.tag ?: return kindText
        return context.getString(R.string.campaign_title_tagged, kindText, context.getString(tagRes(tag)))
    }
}
