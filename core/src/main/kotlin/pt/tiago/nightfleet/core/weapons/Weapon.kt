package pt.tiago.nightfleet.core.weapons

import kotlinx.serialization.Serializable

/** The six special weapons. [DECOY] is placed at setup and is never charged. */
@Serializable
enum class Weapon {
    SONAR,
    TORPEDO,
    RECON,
    CROSS_SHELL,
    DECOY,
    REPAIR,
}
