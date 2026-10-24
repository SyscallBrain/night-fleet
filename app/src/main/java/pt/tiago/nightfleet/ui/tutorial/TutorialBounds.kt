package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import pt.tiago.nightfleet.ui.board.BoardLayout

/**
 * Window bounds of the squares of a board, for the tutorial anchors `cell:<A-L><1-12>`.
 * Pure: the screen gives the layout of its board and the window position of the board's top left corner.
 */
object TutorialBounds {

    /**
     * The window bounds of the square [anchorId] on a board drawn with [layout] at [boardOrigin] (window px), or null
     * when [anchorId] is not a square of this board (not a `cell:` id, or outside the board's columns and rows).
     */
    fun cellBounds(anchorId: String, layout: BoardLayout, boardOrigin: Offset): Rect? {
        val (col, row) = parseCell(anchorId) ?: return null
        if (col >= layout.cols || row >= layout.rows) return null
        val left = boardOrigin.x + layout.cellX(col)
        val top = boardOrigin.y + layout.cellY(row)
        return Rect(left, top, left + layout.cellPx, top + layout.cellPx)
    }

    /** Column and row (both zero based) of a `cell:D4` id, or null for any other id. */
    fun parseCell(anchorId: String): Pair<Int, Int>? {
        if (!anchorId.startsWith("cell:")) return null
        val name = anchorId.removePrefix("cell:")
        if (name.length < 2) return null
        val letter = name[0]
        if (letter !in 'A'..'L') return null
        val number = name.substring(1).toIntOrNull() ?: return null
        if (number !in 1..12) return null
        return (letter - 'A') to (number - 1)
    }
}
