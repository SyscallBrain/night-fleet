package pt.tiago.nightfleet.ui.intro

import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.data.Profile

/** Which intro cards a mission brings, and the cards the profile has already seen. */
object MissionIntros {

    /** The card of a rule tag, or null when the tag has no card of its own. */
    fun cardOf(tag: MissionTag): MechanicIntro? = when (tag) {
        MissionTag.ISLANDS, MissionTag.REEFS -> MechanicIntro.ISLANDS_REEFS
        MissionTag.MINES -> MechanicIntro.MINES
        MissionTag.TIMED -> MechanicIntro.SHOT_CLOCK
        MissionTag.SALVO -> MechanicIntro.SALVO
        MissionTag.DIVING -> MechanicIntro.DIVING_SUBS
        MissionTag.FOG -> MechanicIntro.FOG
        MissionTag.MOVING -> MechanicIntro.MOVING_SHIPS
        MissionTag.SCHOOL -> MechanicIntro.SCHOOL_RULES
        MissionTag.LARGE -> MechanicIntro.LARGE_GRID
        MissionTag.SONAR -> MechanicIntro.SONAR
        MissionTag.TORPEDO -> MechanicIntro.TORPEDO
        MissionTag.RECON -> MechanicIntro.AIR_RECON
        MissionTag.CROSS -> MechanicIntro.CROSS_BARRAGE
        MissionTag.DECOY -> MechanicIntro.DECOY
        MissionTag.REPAIR -> MechanicIntro.REPAIR
    }

    /** The mechanics of a mission: its rule tags, plus the convoy and the lighthouse puzzle by kind. */
    fun mechanicsOf(tags: Collection<MissionTag>, kind: MissionKind): List<MechanicIntro> {
        val byKind = when (kind) {
            MissionKind.CONVOY -> listOf(MechanicIntro.CONVOY)
            MissionKind.PUZZLE -> listOf(MechanicIntro.LIGHTHOUSE_PUZZLE)
            else -> emptyList()
        }
        return (tags.mapNotNull { cardOf(it) } + byKind).distinct()
    }
}

/** The intro cards the player has already seen, from the profile. */
fun Profile.introCardsSeen(): Set<MechanicIntro> =
    MechanicIntro.entries.filter { it.tag in seenIntroCards }.toSet()

/** The profile with [seen] stored as the seen intro cards. */
fun Profile.withIntroCardsSeen(seen: Set<MechanicIntro>): Profile =
    copy(seenIntroCards = seen.map { it.tag }.toSet())
