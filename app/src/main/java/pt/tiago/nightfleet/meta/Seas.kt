package pt.tiago.nightfleet.meta

import androidx.annotation.StringRes
import pt.tiago.nightfleet.R

/** The twelve seas of the campaign. Sea N ends with its boss in mission N x 50. */
object Seas {
    const val COUNT = 12
    const val MISSIONS_PER_SEA = 50

    @StringRes
    private val names: List<Int> = listOf(
        R.string.sea_01_name, R.string.sea_02_name, R.string.sea_03_name, R.string.sea_04_name,
        R.string.sea_05_name, R.string.sea_06_name, R.string.sea_07_name, R.string.sea_08_name,
        R.string.sea_09_name, R.string.sea_10_name, R.string.sea_11_name, R.string.sea_12_name,
    )

    /** Name resource of a sea numbered 1..12. */
    @StringRes
    fun nameRes(sea: Int): Int = names[sea.coerceIn(1, COUNT) - 1]

    /** Mission id of the boss that closes [sea], for example "s03m150". */
    fun bossMissionId(sea: Int): String = missionId(sea, sea * MISSIONS_PER_SEA)

    fun missionId(sea: Int, mission: Int): String = "s%02dm%03d".format(sea, mission)

    /** Parses "s03m112" into sea and mission numbers. Returns null for anything else. */
    fun parseMissionId(id: String?): MissionRef? {
        val match = MISSION_ID.matchEntire(id.orEmpty()) ?: return null
        val (sea, mission) = match.destructured
        val ref = MissionRef(sea.toInt(), mission.toInt())
        return ref.takeIf { it.sea in 1..COUNT && it.mission in 1..COUNT * MISSIONS_PER_SEA }
    }

    private val MISSION_ID = Regex("s(\\d{2})m(\\d{3})")
}

/** A campaign mission reference: the sea (1..12) and the mission number inside the campaign (1..600). */
data class MissionRef(val sea: Int, val mission: Int)
