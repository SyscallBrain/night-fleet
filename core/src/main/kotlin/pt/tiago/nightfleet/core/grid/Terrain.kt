package pt.tiago.nightfleet.core.grid

import kotlinx.serialization.Serializable

/** Terrain of one square. Ships can only occupy [WATER]; islands and reefs are never targets. */
@Serializable
enum class Terrain {
    /** Open sea: ships may be here and it can be shot at. */
    WATER,

    /** Land: visible to both sides, blocks torpedoes. */
    ISLAND,

    /** Shallow reef: visible to both sides, does not block torpedoes. */
    REEF,
}
