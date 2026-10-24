package pt.tiago.nightfleet.core.weapons

import pt.tiago.nightfleet.core.grid.Board

/** Pure geometry of the weapons, on indices of one [Board] (row-major). */
object Lines {

    /** The 3 by 3 square around [center], clipped to the board, ascending. */
    fun sonarArea(board: Board, center: Int): List<Int> {
        val r = center / board.width
        val c = center % board.width
        val out = ArrayList<Int>(9)
        for (dr in -1..1) for (dc in -1..1) {
            if (board.inBounds(r + dr, c + dc)) out += (r + dr) * board.width + (c + dc)
        }
        return out.sorted()
    }

    /** Centre, then up, right, down and left, skipping squares outside the board. */
    fun crossArea(board: Board, center: Int): List<Int> {
        val r = center / board.width
        val c = center % board.width
        return listOf(0 to 0, -1 to 0, 0 to 1, 1 to 0, 0 to -1)
            .filter { (dr, dc) -> board.inBounds(r + dr, c + dc) }
            .map { (dr, dc) -> (r + dr) * board.width + (c + dc) }
    }

    /** The squares of a whole row or column in the order a torpedo travels from its margin. */
    fun line(board: Board, isRow: Boolean, index: Int, fromStart: Boolean): List<Int> {
        val length = if (isRow) board.width else board.height
        val order = if (fromStart) (0 until length).toList() else (length - 1 downTo 0).toList()
        return order.map { k -> if (isRow) index * board.width + k else k * board.width + index }
    }

    fun lineExists(board: Board, isRow: Boolean, index: Int): Boolean =
        if (isRow) index in 0 until board.height else index in 0 until board.width
}
