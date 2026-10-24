package pt.tiago.nightfleet.ui.board

/**
 * What one board cell looks like. The battle engine maps its own state to these values; the board
 * kit never depends on the engine packages.
 */
enum class CellVisual {
    /** Not shot yet: empty sea. */
    UNKNOWN,

    /** Water after a shot: a ring. */
    WATER,

    /** Automatic water around a sunk ship: a small dot. */
    AUTO_WATER,

    /** Hit: the theme's hit shape on a tinted cell. */
    HIT,

    /** Sunk: the hull is drawn from [ShipOutline]; the hit marks sit on top at 80 %. */
    SUNK,

    /** Submerged contact: a double wave in the accent colour. */
    CONTACT,

    /** Mine: a cross with spikes in the danger colour. */
    MINE,

    /** Wreck of a decoy: dashed hull outline. */
    DECOY_WRECK,

    /** Own ship repaired: a patch. */
    REPAIRED,

    /** Island: land fill with the theme's pattern. */
    ISLAND,

    /** Reef: sea with three wavy lines. */
    REEF,

    /** Own ship, not hit. */
    OWN_SHIP,

    /** Own ship, hit. */
    OWN_SHIP_HIT,

    /** Enemy ship left at the end of the battle: drawn at 60 % with a dashed outline. */
    REVEALED_SHIP,
}

/**
 * One ship drawn as a single hull. [cells] are row-major indices of the board. Straight ships of 1 to 5
 * cells and the 4-cell T-shaped seaplane get a continuous contour; any other shape falls back to one
 * outline per cell.
 */
data class ShipOutline(
    val cells: List<Int>,
    val sunk: Boolean,
    val revealed: Boolean = false,
)

/** A sonar reading: the number of enemy cells in the 3×3 area around [centerIndex]. */
data class SonarMark(
    val centerIndex: Int,
    val count: Int,
    /** True while the dashed 3×3 ring is still shown (the caller clears it after 2 s). */
    val fresh: Boolean,
)

/**
 * Air reconnaissance result for a whole row or column, drawn in the margin beside the board.
 * [index] is the row when [isRow] is true, the column otherwise.
 */
data class ReconMark(val isRow: Boolean, val index: Int, val present: Boolean)

/**
 * Everything the board needs to draw one frame. Pure data: no Compose types, so the JVM tests can
 * check it. [cells] is row-major: index = row * width + col.
 */
data class BoardRenderModel(
    val width: Int,
    val height: Int,
    val cells: List<CellVisual>,
    val ships: List<ShipOutline> = emptyList(),
    val sonarReadings: List<SonarMark> = emptyList(),
    val recon: List<ReconMark> = emptyList(),
    /** Index of the last shot (outline of 2 dp in accent), or null. */
    val lastShot: Int? = null,
    /** Index of the cell selected for confirmation, or null. */
    val selected: Int? = null,
    /** Hint cells (the officer's hint): drawn with a dashed accent outline. */
    val highlight: Set<Int> = emptySet(),
    /** Fog: alpha multiplier (0..1) of the water mark of a cell that is fading out. */
    val fadedMisses: Map<Int, Float> = emptyMap(),
) {
    init {
        require(width > 0 && height > 0) { "Board must have at least one cell, got ${width}x$height" }
        require(cells.size == width * height) {
            "cells must have width*height = ${width * height} entries, got ${cells.size}"
        }
        val size = cells.size
        require(lastShot == null || lastShot in 0 until size) { "lastShot $lastShot is outside the board" }
        require(selected == null || selected in 0 until size) { "selected $selected is outside the board" }
        require(highlight.all { it in 0 until size }) { "highlight has cells outside the board" }
        require(fadedMisses.keys.all { it in 0 until size }) { "fadedMisses has cells outside the board" }
        require(ships.all { ship -> ship.cells.all { it in 0 until size } }) { "a ship is outside the board" }
    }

    /** Cell index for a column and a row. */
    fun indexOf(col: Int, row: Int): Int = row * width + col
}

/**
 * Squares of the enemy sea the player may still fire at: plain sea, a submerged submarine's contact and a
 * repaired square. Matches `KnownCell.isFireable` in the core.
 */
val CellVisual.isFireable: Boolean
    get() = this == CellVisual.UNKNOWN || this == CellVisual.CONTACT || this == CellVisual.REPAIRED
