package pt.tiago.nightfleet.core.grid

import kotlinx.serialization.Serializable

/** A square of the grid. The origin is the top-left corner; [row] and [col] start at 0. */
@Serializable
data class Cell(val row: Int, val col: Int) {

    /** Visible label: column as a letter, row as a number, e.g. `Cell(6, 1).label() == "B7"`. */
    fun label(): String {
        require(row >= 0 && col in 0..25) { "No label for ($row, $col)" }
        return "${'A' + col}${row + 1}"
    }

    companion object {
        private val LABEL = Regex("([A-Z])([1-9][0-9]?)")

        /** Parses a label such as "B7" (case-insensitive). Returns null when malformed. */
        fun parse(label: String): Cell? {
            val match = LABEL.matchEntire(label.trim().uppercase()) ?: return null
            val col = match.groupValues[1][0] - 'A'
            val row = match.groupValues[2].toInt() - 1
            return Cell(row, col)
        }
    }
}
