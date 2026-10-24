package pt.tiago.nightfleet.core.fleet

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.grid.Cell

/** A ship in a sea: its class, the top-left corner of its bounding box and its orientation index. */
@Serializable
data class Placement(
    val shipClass: ShipClass,
    val origin: Cell,
    val rotationIndex: Int = 0,
) {
    /** Squares covered by this placement. Requires a valid [rotationIndex]. */
    fun cells(): List<Cell> = shipClass.cellsAt(rotationIndex, origin)
}
