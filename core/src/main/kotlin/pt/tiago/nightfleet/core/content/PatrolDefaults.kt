package pt.tiago.nightfleet.core.content

/**
 * The built-in patrol config. The app falls back to it when `patrol/config.json` is not in the
 * assets, and `:leveltool patrol-config` writes exactly this, so both always agree.
 *
 * Stages 1 to 4 are 8×8 open seas with the small fleet. From stage 5 on the seas have islands ([ISLANDS]) and mines,
 * the grid grows to 10×10 (stages 5 to 12) and then to 12×12 (stages 13 to 20). After the last stage every sea repeats
 * the 20th with one more mine per sea ([PatrolConfigDto.extraMinesPerGrowth]).
 */
object PatrolDefaults {
    const val STAGE_COUNT = 20

    /** Stage terrain marker: islands generated from the run seed. Null means open water. */
    const val ISLANDS = "islands"

    fun config(): PatrolConfigDto = PatrolConfigDto(
        stages = (1..STAGE_COUNT).map(::stage),
        growthAfter = STAGE_COUNT,
        extraMinesPerGrowth = 1,
    )

    private fun stage(ordinal: Int): PatrolStageDto {
        val side = when {
            ordinal <= 4 -> 8
            ordinal <= 12 -> 10
            else -> 12
        }
        val fleet = when (side) {
            8 -> "SMALL"
            10 -> "CLASSIC"
            else -> "LARGE"
        }
        val mines = when {
            ordinal < 5 -> 0
            ordinal <= 8 -> 1
            ordinal <= 16 -> 2
            else -> 3
        }
        return PatrolStageDto(
            ordinal = ordinal,
            width = side,
            height = side,
            fleet = fleet,
            terrain = if (ordinal >= 5) ISLANDS else null,
            mines = mines,
            // Reserved for the calibration of M5b: the v1 rules refund a fixed 3 shots per ship sunk.
            reloadBudget = 2 + ordinal / 5,
        )
    }

    /** The stage of sea [sea] (1-based). Seas past the last stage repeat the last one with more mines. */
    fun PatrolConfigDto.stageFor(sea: Int): PatrolStageDto {
        require(sea >= 1) { "sea numbers start at 1" }
        if (sea <= stages.size) return stages[sea - 1]
        val last = stages.last()
        return last.copy(
            ordinal = sea,
            mines = last.mines + extraMinesPerGrowth * (sea - growthAfter),
        )
    }
}
