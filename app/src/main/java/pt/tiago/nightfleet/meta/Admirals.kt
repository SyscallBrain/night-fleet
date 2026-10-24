package pt.tiago.nightfleet.meta

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R

/**
 * One of the twelve admirals. The admiral of sea N is that sea's boss, so the
 * ids here are the keys used by `Profile.metAdmirals` and `Profile.defeatedAdmirals`.
 */
data class Admiral(
    val id: String,
    val sea: Int,
    @StringRes val name: Int,
    @StringRes val style: Int,
    @StringRes val quote: Int,
)

object Admirals {
    val all: List<Admiral> = listOf(
        Admiral("iris", 1, R.string.adm_iris_name, R.string.adm_iris_style, R.string.adm_iris_quote),
        Admiral("haze", 2, R.string.adm_haze_name, R.string.adm_haze_style, R.string.adm_haze_quote),
        Admiral("coral", 3, R.string.adm_coral_name, R.string.adm_coral_style, R.string.adm_coral_quote),
        Admiral("frost", 4, R.string.adm_frost_name, R.string.adm_frost_style, R.string.adm_frost_quote),
        Admiral("iron", 5, R.string.adm_iron_name, R.string.adm_iron_style, R.string.adm_iron_quote),
        Admiral("atoll", 6, R.string.adm_atoll_name, R.string.adm_atoll_style, R.string.adm_atoll_quote),
        Admiral("wolf", 7, R.string.adm_wolf_name, R.string.adm_wolf_style, R.string.adm_wolf_quote),
        Admiral("sargasso", 8, R.string.adm_sargasso_name, R.string.adm_sargasso_style, R.string.adm_sargasso_quote),
        Admiral("shade", 9, R.string.adm_shade_name, R.string.adm_shade_style, R.string.adm_shade_quote),
        Admiral("tempest", 10, R.string.adm_tempest_name, R.string.adm_tempest_style, R.string.adm_tempest_quote),
        Admiral("leviathan", 11, R.string.adm_leviathan_name, R.string.adm_leviathan_style, R.string.adm_leviathan_quote),
        Admiral("nox", 12, R.string.adm_nox_name, R.string.adm_nox_style, R.string.adm_nox_quote),
    )

    fun byId(id: String): Admiral? = all.firstOrNull { it.id == id }
}
