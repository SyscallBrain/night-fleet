package pt.tiago.nightfleet.ui.board

/**
 * Pure text for the TalkBack labels of the squares of a [SeaBoard]. The words of each state are
 * string resources: [stateKey] names the one to use and the screen looks it up in `values/strings.xml`.
 */
object BoardLabels {

    /** Columns are named A, B, C…; the largest board is 12 columns wide. */
    private const val MAX_COLUMNS = 26

    /** Coordinate of a square, zero based: column 0 row 0 is `A1`, column 3 row 3 is `D4`. */
    fun coordinate(col: Int, row: Int): String {
        require(col in 0 until MAX_COLUMNS) { "column $col outside the board" }
        require(row >= 0) { "row $row outside the board" }
        return "${'A' + col}${row + 1}"
    }

    /** Coordinate of the row-major [index] on a board [width] squares wide. */
    fun coordinateOf(index: Int, width: Int): String {
        require(width > 0 && index >= 0) { "index $index on width $width" }
        return coordinate(index % width, index / width)
    }

    /**
     * Name of the state word of [visual]. [enemy] is true on the sea the player shoots at: an unknown square there is
     * "not shot yet", on any other sea it is simply "empty sea".
     */
    fun stateKey(visual: CellVisual, enemy: Boolean): String = when (visual) {
        CellVisual.UNKNOWN -> if (enemy) "unknown" else "empty_sea"
        CellVisual.WATER, CellVisual.AUTO_WATER -> "water"
        CellVisual.HIT -> "hit"
        CellVisual.SUNK -> "sunk"
        CellVisual.CONTACT -> "contact"
        CellVisual.MINE -> "mine"
        CellVisual.DECOY_WRECK -> "decoy"
        CellVisual.REPAIRED -> "repaired"
        CellVisual.ISLAND -> "island"
        CellVisual.REEF -> "reef"
        CellVisual.OWN_SHIP -> "own_ship"
        CellVisual.OWN_SHIP_HIT -> "own_ship_hit"
        CellVisual.REVEALED_SHIP -> "revealed_ship"
    }

    /** Every key that [stateKey] can return; the JVM test checks that each has a string in both languages. */
    val stateKeys: List<String> = listOf(
        "unknown", "empty_sea", "water", "hit", "sunk", "contact", "mine", "decoy", "repaired",
        "island", "reef", "own_ship", "own_ship_hit", "revealed_ship",
    )
}
