package pt.tiago.nightfleet.ui.board

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cells smaller than this (in dp) switch on the long-press magnifier. */
const val MAGNIFIER_THRESHOLD_DP = 26f

/** Width of the coordinate strip, in dp (letters on top, numbers on the left). */
const val LABEL_DP = 16f

/** Gap between cells of the main board, in dp. */
const val BOARD_GAP_DP = 2f

/** Strip reserved for recon marks, in dp, when the model has recon marks. */
const val RECON_MARGIN_DP = 14f

/**
 * Pixel layout of one board. All values are integer pixels, so every cell edge lands on a pixel boundary.
 * The cell at column [col] and row [row] starts at ([cellX], [cellY]).
 */
data class BoardLayout(
    val cols: Int,
    val rows: Int,
    /** Side of one square cell, in px. */
    val cellPx: Int,
    /** Gap between two cells, in px. */
    val gapPx: Int,
    /** Coordinate strip (0 without coordinates), in px. */
    val labelPx: Int,
    /** Margin for recon marks on the right and at the bottom (0 when not reserved), in px. */
    val marginPx: Int,
    val density: Float,
) {
    /** Distance from one cell start to the next, in px. */
    val pitch: Int get() = cellPx + gapPx

    /** Total width of the board, labels and margin included, in px. */
    val widthPx: Int get() = labelPx + cols * cellPx + (cols - 1) * gapPx + marginPx

    /** Total height of the board, labels and margin included, in px. */
    val heightPx: Int get() = labelPx + rows * cellPx + (rows - 1) * gapPx + marginPx

    /** True when a cell is smaller than [MAGNIFIER_THRESHOLD_DP]. */
    val needsMagnifier: Boolean get() = cellPx < MAGNIFIER_THRESHOLD_DP * density

    fun cellX(col: Int): Int = labelPx + col * pitch

    fun cellY(row: Int): Int = labelPx + row * pitch

    /**
     * Cell index (row-major) under a point given in board px, or null when the point is on a label,
     * in a gap, in the margin or outside the board.
     */
    fun cellAt(x: Float, y: Float): Int? {
        val col = hitIndex(x - labelPx, cols) ?: return null
        val row = hitIndex(y - labelPx, rows) ?: return null
        return row * cols + col
    }

    private fun hitIndex(local: Float, count: Int): Int? {
        if (local < 0f) return null
        val index = floor(local / pitch).toInt()
        if (index >= count) return null
        // A point in the gap after the cell is not on a cell.
        return if (local - index * pitch < cellPx) index else null
    }
}

/** Layout rules of the board. Pure functions, unit-tested. */
object BoardMetrics {

    /**
     * Largest integer cell size that fits [availableWidthPx] × [availableHeightPx] for [cols] × [rows]:
     * `cell = floor((width − label − (cols − 1) · gap − margin) / cols)`, also bounded by the height.
     * Gaps and margins are rounded to whole px, so the cells stay pixel-aligned.
     */
    fun compute(
        availableWidthPx: Int,
        availableHeightPx: Int,
        cols: Int,
        rows: Int,
        density: Float,
        showCoordinates: Boolean = true,
        gapDp: Float = BOARD_GAP_DP,
        reconMargin: Boolean = false,
    ): BoardLayout {
        require(cols > 0 && rows > 0) { "Board must have at least one cell" }
        require(density > 0f) { "density must be positive" }
        val labelPx = if (showCoordinates) dpToPx(LABEL_DP, density) else 0
        val gapPx = dpToPx(gapDp, density)
        val marginPx = if (reconMargin) dpToPx(RECON_MARGIN_DP, density) else 0
        val byWidth = floor(
            (availableWidthPx - labelPx - (cols - 1) * gapPx - marginPx).toFloat() / cols,
        ).toInt()
        val byHeight = floor(
            (availableHeightPx - labelPx - (rows - 1) * gapPx - marginPx).toFloat() / rows,
        ).toInt()
        return BoardLayout(
            cols = cols,
            rows = rows,
            cellPx = max(1, min(byWidth, byHeight)),
            gapPx = gapPx,
            labelPx = labelPx,
            marginPx = marginPx,
            density = density,
        )
    }

    /** dp to px, rounded to the nearest whole pixel. */
    fun dpToPx(dp: Float, density: Float): Int = (dp * density).roundToInt()
}
