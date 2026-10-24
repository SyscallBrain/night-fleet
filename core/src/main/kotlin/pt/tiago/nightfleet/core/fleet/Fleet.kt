package pt.tiago.nightfleet.core.fleet

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.fleet.ShipClass.BATTLESHIP
import pt.tiago.nightfleet.core.fleet.ShipClass.CARRIER
import pt.tiago.nightfleet.core.fleet.ShipClass.CRUISER
import pt.tiago.nightfleet.core.fleet.ShipClass.DESTROYER
import pt.tiago.nightfleet.core.fleet.ShipClass.FREIGHTER
import pt.tiago.nightfleet.core.fleet.ShipClass.MIDGET_SUB
import pt.tiago.nightfleet.core.fleet.ShipClass.SEAPLANE
import pt.tiago.nightfleet.core.fleet.ShipClass.SUBMARINE

/** A named list of ships to place. Presets follow the ship table; custom fleets are allowed. */
@Serializable
data class Fleet(val name: String, val ships: List<ShipClass>) {
    init {
        require(ships.isNotEmpty()) { "A fleet needs at least one ship" }
    }

    /** Total squares occupied by the fleet. */
    val cellCount: Int get() = ships.sumOf { it.size }

    companion object {
        val CLASSIC = Fleet("CLASSIC", listOf(CARRIER, BATTLESHIP, CRUISER, SUBMARINE, DESTROYER))
        val SMALL = Fleet("SMALL", listOf(BATTLESHIP, CRUISER, SUBMARINE, DESTROYER))
        val TUTORIAL = Fleet("TUTORIAL", listOf(CRUISER, DESTROYER, DESTROYER))
        val LARGE = Fleet("LARGE", listOf(CARRIER, BATTLESHIP, CRUISER, CRUISER, SUBMARINE, DESTROYER, FREIGHTER))
        val SCHOOL = Fleet("SCHOOL", listOf(CARRIER, BATTLESHIP, CRUISER, SEAPLANE, SEAPLANE, MIDGET_SUB, MIDGET_SUB))

        val PRESETS: List<Fleet> = listOf(CLASSIC, SMALL, TUTORIAL, LARGE, SCHOOL)
    }
}
