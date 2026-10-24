package pt.tiago.nightfleet.core.weapons

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.grid.Cell

/** Where a special weapon is aimed. Sonar, cross and repair take a square; torpedo and recon a line. */
@Serializable
sealed class WeaponTarget {
    /** A single square: the centre of a sonar sweep, the centre of a cross, or the square to repair. */
    @Serializable
    data class CellTarget(val cell: Cell) : WeaponTarget()

    /**
     * A whole row ([isRow] true, [index] is the row) or column. [fromStart] tells the torpedo which
     * margin it leaves from: the top or the left edge when true, the bottom or the right edge otherwise.
     */
    @Serializable
    data class LineTarget(val isRow: Boolean, val index: Int, val fromStart: Boolean = true) : WeaponTarget()
}
