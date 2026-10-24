package pt.tiago.nightfleet.ui.common

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.data.Rank

/** The name of each rank, in the language of the app. */
@StringRes
fun rankNameRes(rank: Rank): Int = when (rank) {
    Rank.CABIN_BOY -> R.string.rank_cabin_boy
    Rank.SEAMAN -> R.string.rank_seaman
    Rank.LEADING_SEAMAN -> R.string.rank_leading_seaman
    Rank.PETTY_OFFICER -> R.string.rank_petty_officer
    Rank.MIDSHIPMAN -> R.string.rank_midshipman
    Rank.ENSIGN -> R.string.rank_ensign
    Rank.SUB_LIEUTENANT -> R.string.rank_sub_lieutenant
    Rank.LIEUTENANT -> R.string.rank_lieutenant
    Rank.LIEUTENANT_COMMANDER -> R.string.rank_lieutenant_commander
    Rank.COMMANDER -> R.string.rank_commander
    Rank.CAPTAIN -> R.string.rank_captain
    Rank.REAR_ADMIRAL -> R.string.rank_rear_admiral
    Rank.VICE_ADMIRAL -> R.string.rank_vice_admiral
    Rank.ADMIRAL -> R.string.rank_admiral
    Rank.GRAND_ADMIRAL -> R.string.rank_grand_admiral
}
