package pt.tiago.nightfleet.data

/**
 * Naval ranks. Only ids and thresholds live here; the en/pt names are
 * string resources. A rank is derived from the stars of all three difficulties and is never stored.
 */
enum class Rank(val minStars: Int) {
    CABIN_BOY(0),
    SEAMAN(25),
    LEADING_SEAMAN(75),
    PETTY_OFFICER(150),
    MIDSHIPMAN(300),
    ENSIGN(500),
    SUB_LIEUTENANT(800),
    LIEUTENANT(1_200),
    LIEUTENANT_COMMANDER(1_700),
    COMMANDER(2_300),
    CAPTAIN(3_000),
    REAR_ADMIRAL(3_800),
    VICE_ADMIRAL(4_600),
    ADMIRAL(5_200),

    /** Needs 5 200 stars and the 12 bosses won on HARD. */
    GRAND_ADMIRAL(5_200),
}

object Ranks {
    const val GRAND_ADMIRAL_HARD_BOSSES = 12

    fun rankFor(totalStars: Int, hardBossesWon: Int): Rank {
        val stars = totalStars.coerceAtLeast(0)
        if (stars >= Rank.GRAND_ADMIRAL.minStars && hardBossesWon >= GRAND_ADMIRAL_HARD_BOSSES) {
            return Rank.GRAND_ADMIRAL
        }
        return Rank.entries
            .filter { it != Rank.GRAND_ADMIRAL && stars >= it.minStars }
            .last()
    }
}
