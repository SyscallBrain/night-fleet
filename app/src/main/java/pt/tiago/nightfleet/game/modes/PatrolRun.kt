package pt.tiago.nightfleet.game.modes

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.data.Difficulty

/** The rules of the patrol's shot reserve. Pure: no Android, no engine state. */
object PatrolRules {
    /** Shots refunded at the end of a cleared sea, per ship sunk. */
    const val REFUND_PER_SHIP = 3

    /** The shared reserve at the start of the first sea. */
    fun startingReserve(difficulty: Difficulty): Int = when (difficulty) {
        Difficulty.EASY -> 60
        Difficulty.MEDIUM -> 50
        Difficulty.HARD -> 40
    }

    fun refund(shipsSunk: Int): Int {
        require(shipsSunk >= 0) { "ships sunk cannot be negative" }
        return REFUND_PER_SHIP * shipsSunk
    }
}

/** How a sea ended: the fleet was sunk within the reserve, or the reserve ran out first. */
sealed interface SeaEnd {
    /** The fleet was sunk after [shotsUsed] shots, which the reserve paid for. */
    data class Cleared(val shotsUsed: Int, val shipsSunk: Int) : SeaEnd

    data object Lost : SeaEnd
}

/**
 * The state of one patrol run that is kept between seas. The reserve is the shots left for the next sea. The run is
 * seeded once, when it starts: every sea takes its layouts from [seaSeed], so a run never changes when it is resumed.
 */
@Serializable
data class PatrolRun(
    val runSeed: Long,
    val difficulty: Difficulty,
    /** The sea being played, 1-based. */
    val sea: Int = 1,
    /** Shots left for this sea and the seas after it. */
    val reserve: Int,
    /** Seas cleared before [sea]. */
    val seasCleared: Int = 0,
) {
    init {
        require(sea >= 1) { "sea numbers start at 1" }
        require(reserve >= 1) { "a running patrol has at least one shot in reserve" }
        require(seasCleared == sea - 1) { "every earlier sea of a running patrol is cleared" }
    }

    /** The seed of the sea being played. */
    val seaSeed: Long get() = seaSeed(runSeed, sea)

    /** The id of the battle of this sea, used for the saved match, the logbook and the profile commit. */
    val matchId: String get() = matchId(runSeed, sea)

    /**
     * The run after this sea, or null when the run is over. A cleared sea refunds [PatrolRules.REFUND_PER_SHIP] shots
     * per ship sunk and the run goes on; a lost sea (the reserve ran out) ends the run.
     */
    fun after(end: SeaEnd): PatrolRun? = when (end) {
        SeaEnd.Lost -> null
        is SeaEnd.Cleared -> {
            require(end.shotsUsed in 1..reserve) { "a cleared sea uses between 1 and the reserve shots" }
            val next = reserve - end.shotsUsed + PatrolRules.refund(end.shipsSunk)
            if (next < 1) {
                null
            } else {
                copy(sea = sea + 1, reserve = next, seasCleared = seasCleared + 1)
            }
        }
    }

    companion object {
        private const val SEA_SALT = 0x5345410000L

        /** A new run, starting at sea 1 with the reserve of [difficulty]. */
        fun start(difficulty: Difficulty, runSeed: Long): PatrolRun =
            PatrolRun(runSeed = runSeed, difficulty = difficulty, reserve = PatrolRules.startingReserve(difficulty))

        /** The seed of sea [sea] of the run [runSeed]. Each sea of a run has its own streams. */
        fun seaSeed(runSeed: Long, sea: Int): Long = SplitMix64.mix(runSeed xor (SEA_SALT + sea))

        fun matchId(runSeed: Long, sea: Int): String = "pa-" + hex(runSeed) + "-" + sea

        internal fun hex(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')
    }
}
