package pt.tiago.nightfleet.game.freeplay

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.weapons.Weapon

/** Difficulty of the free play opponent. The brain stays provisional until the admirals arrive. */
@Serializable
enum class OpponentLevel { EASY, MEDIUM, HARD, RELENTLESS }

/** The fleet a player chooses on the 10 by 10 grid; 8 and 12 use the small and the large fleet. */
@Serializable
enum class FleetChoice { CLASSIC, SCHOOL }

/** Who acts first. [RANDOM] draws it from the attempt seed. */
@Serializable
enum class Starter { PLAYER, ADMIRAL, RANDOM }

/**
 * The admirals of the free play dropdown: the twelve admirals, in campaign order. Their names are
 * in strings.xml (`admiral_<id>_name`), and their profiles in core/ai ([pt.tiago.nightfleet.core.ai.Admirals]).
 */
object FreePlayAdmirals {
    val IDS: List<String> = Admirals.ALL.map { it.id }

    const val DEFAULT_ID = "coral"
}

/** The "Play against" preset of the gallery: free play opens with the chosen admiral. */
object FreePlayPreset {

    /**
     * [state] with [admiralId] as the opponent. An id that is not an admiral of the catalog keeps the state as it is,
     * so a bad link never changes the setup.
     */
    fun withAdmiral(state: FreePlayState, admiralId: String?): FreePlayState {
        if (admiralId == null || admiralId !in FreePlayAdmirals.IDS) return state
        return state.copy(config = state.config.copy(admiralId = admiralId))
    }
}

/**
 * The free play setup: every choice the setup screen makes. Stored as the last setup, so the
 * screen opens with the choices of the last game.
 */
@Serializable
data class FreePlayConfig(
    val admiralId: String = FreePlayAdmirals.DEFAULT_ID,
    val level: OpponentLevel = OpponentLevel.MEDIUM,
    val grid: Int = 10,
    val fleet: FleetChoice = FleetChoice.CLASSIC,
    val islands: Boolean = false,
    val salvo: Boolean = false,
    val diving: Boolean = false,
    val fog: Boolean = false,
    val movingShips: Boolean = false,
    /** Mines per side, 0 to [MAX_MINES]. */
    val minesPerSide: Int = 0,
    /** The weapons in play. The decoy, when chosen, puts one isco on each sea. */
    val weapons: Set<Weapon> = emptySet(),
    /** Seconds per turn: null (off), 8, 6 or 4. */
    val shotClock: Int? = null,
    val starter: Starter = Starter.PLAYER,
) {
    /** The fleet of this game: the grid decides small and large, the choice decides classic and school on 10. */
    val fleetPreset: Fleet
        get() = when (grid) {
            8 -> Fleet.SMALL
            12 -> Fleet.LARGE
            else -> if (fleet == FleetChoice.SCHOOL) Fleet.SCHOOL else Fleet.CLASSIC
        }

    /** Stable problems of the setup, empty when the setup can be played. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        if (grid !in GRID_SIZES) out += "GRID"
        if (minesPerSide !in 0..MAX_MINES) out += "MINES"
        if (shotClock != null && shotClock !in SHOT_CLOCKS) out += "SHOT_CLOCK"
        return out
    }

    companion object {
        val GRID_SIZES = listOf(8, 10, 12)
        val SHOT_CLOCKS: List<Int> = listOf(8, 6, 4)
        const val MAX_MINES = 4
    }
}
