package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag

/**
 * The title of a mission is composed from its type and its most telling tag: "Duel with
 * islands", "Hunt in the fog". Missions carry no text of their own. The UI maps [kind] and [tag] to string
 * resources, so the composition is translated as a whole and never concatenated in code.
 */
data class MissionTitle(val kind: MissionKind, val tag: MissionTag?)

object MissionTitles {

    /** Tags that say the most about a mission, first. Rule tags that change the fight beat the minor ones. */
    val PRIORITY: List<MissionTag> = listOf(
        MissionTag.FOG,
        MissionTag.MOVING,
        MissionTag.DIVING,
        MissionTag.MINES,
        MissionTag.SALVO,
        MissionTag.CROSS,
        MissionTag.TORPEDO,
        MissionTag.RECON,
        MissionTag.DECOY,
        MissionTag.REPAIR,
        MissionTag.SCHOOL,
        MissionTag.ISLANDS,
        MissionTag.REEFS,
        MissionTag.TIMED,
        MissionTag.SONAR,
        MissionTag.LARGE,
    )

    /** The title of a mission with [kind] and [tags]: the highest-priority tag, or none. */
    fun of(kind: MissionKind, tags: List<MissionTag>): MissionTitle {
        val tag = PRIORITY.firstOrNull { it in tags }
        return MissionTitle(kind, tag)
    }
}
