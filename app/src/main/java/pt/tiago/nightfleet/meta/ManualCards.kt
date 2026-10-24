package pt.tiago.nightfleet.meta

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R

/**
 * A mechanic introduction card. The Gallery's "Manual" lists them; the
 * in-mission cards show the same text the first time a mechanic appears.
 */
data class ManualCard(
    val id: String,
    @StringRes val title: Int,
    @StringRes val first: Int,
    @StringRes val second: Int,
)

object ManualCards {
    val all: List<ManualCard> = listOf(
        ManualCard("puzzle", R.string.manual_puzzle_title, R.string.manual_puzzle_1, R.string.manual_puzzle_2),
        ManualCard("islands", R.string.manual_islands_title, R.string.manual_islands_1, R.string.manual_islands_2),
        ManualCard("sonar", R.string.manual_sonar_title, R.string.manual_sonar_1, R.string.manual_sonar_2),
        ManualCard("clock", R.string.manual_clock_title, R.string.manual_clock_1, R.string.manual_clock_2),
        ManualCard("mines", R.string.manual_mines_title, R.string.manual_mines_1, R.string.manual_mines_2),
        ManualCard("recon", R.string.manual_recon_title, R.string.manual_recon_1, R.string.manual_recon_2),
        ManualCard("school", R.string.manual_school_title, R.string.manual_school_1, R.string.manual_school_2),
        ManualCard("escort", R.string.manual_escort_title, R.string.manual_escort_1, R.string.manual_escort_2),
        ManualCard("decoy", R.string.manual_decoy_title, R.string.manual_decoy_1, R.string.manual_decoy_2),
        ManualCard("torpedo", R.string.manual_torpedo_title, R.string.manual_torpedo_1, R.string.manual_torpedo_2),
        ManualCard("submerge", R.string.manual_submerge_title, R.string.manual_submerge_1, R.string.manual_submerge_2),
        ManualCard("repair", R.string.manual_repair_title, R.string.manual_repair_1, R.string.manual_repair_2),
        ManualCard("salvo", R.string.manual_salvo_title, R.string.manual_salvo_1, R.string.manual_salvo_2),
        ManualCard("cross", R.string.manual_cross_title, R.string.manual_cross_1, R.string.manual_cross_2),
        ManualCard("large", R.string.manual_large_title, R.string.manual_large_1, R.string.manual_large_2),
        ManualCard("fog", R.string.manual_fog_title, R.string.manual_fog_1, R.string.manual_fog_2),
        ManualCard("moving", R.string.manual_moving_title, R.string.manual_moving_1, R.string.manual_moving_2),
    )
}
