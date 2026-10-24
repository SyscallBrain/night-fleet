package pt.tiago.nightfleet.meta

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.fleet.ShipClass

/**
 * The eight ship classes of the Ships gallery. The sunk counters in
 * `DifficultyStats.shipsSunkByClass` are keyed by [ShipClass.name].
 */
data class ShipEntry(
    val shipClass: ShipClass,
    @StringRes val name: Int,
    @StringRes val fact: Int,
)

object Ships {
    val all: List<ShipEntry> = listOf(
        ShipEntry(ShipClass.CARRIER, R.string.ship_carrier_name, R.string.ship_carrier_fact),
        ShipEntry(ShipClass.BATTLESHIP, R.string.ship_battleship_name, R.string.ship_battleship_fact),
        ShipEntry(ShipClass.CRUISER, R.string.ship_cruiser_name, R.string.ship_cruiser_fact),
        ShipEntry(ShipClass.SUBMARINE, R.string.ship_submarine_name, R.string.ship_submarine_fact),
        ShipEntry(ShipClass.DESTROYER, R.string.ship_destroyer_name, R.string.ship_destroyer_fact),
        ShipEntry(ShipClass.SEAPLANE, R.string.ship_seaplane_name, R.string.ship_seaplane_fact),
        ShipEntry(ShipClass.MIDGET_SUB, R.string.ship_midget_name, R.string.ship_midget_fact),
        ShipEntry(ShipClass.FREIGHTER, R.string.ship_freighter_name, R.string.ship_freighter_fact),
    )
}
