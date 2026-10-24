package pt.tiago.nightfleet.core.mission

import kotlinx.serialization.Serializable

/**
 * Mission types that the engine plays. [DUEL]: both sides fire until one fleet is sunk
 * or the turn limit is reached. [HUNT]: only side 0 fires, within a shot budget. [CONVOY]: a duel
 * whose player fleet carries freighters that must survive. [BOSS]: one duel of a [BossSeries].
 */
@Serializable
enum class MissionType {
    DUEL,
    HUNT,
    CONVOY,
    BOSS,
    ;

    /** Whether the two sides take turns in turn order (every type but HUNT). */
    val alternates: Boolean get() = this != HUNT
}
