package pt.tiago.nightfleet.ui.screens.freeplay

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R

/**
 * The display name of each admiral, by admiral id. An unknown id (an older saved setup) plays and
 * shows as Coral, the same fallback as AdmiralOpponent.profileOf.
 */
@StringRes
fun admiralNameRes(id: String): Int = when (id) {
    "iris" -> R.string.admiral_iris_name
    "haze" -> R.string.admiral_haze_name
    "coral" -> R.string.admiral_coral_name
    "frost" -> R.string.admiral_frost_name
    "iron" -> R.string.admiral_iron_name
    "atoll" -> R.string.admiral_atoll_name
    "wolf" -> R.string.admiral_wolf_name
    "witch" -> R.string.admiral_witch_name
    "shade" -> R.string.admiral_shade_name
    "tempest" -> R.string.admiral_tempest_name
    "leviathan" -> R.string.admiral_leviathan_name
    "nox" -> R.string.admiral_nox_name
    else -> R.string.admiral_coral_name
}
