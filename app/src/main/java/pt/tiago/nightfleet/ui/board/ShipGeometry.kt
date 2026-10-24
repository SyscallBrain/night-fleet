package pt.tiago.nightfleet.ui.board

/**
 * One step of a hull contour, in **cell units**: cell `i` along an axis spans `[i, i + 1]`, so the
 * whole ship is one shape across its cells. The renderer maps these units to pixels (see [ShipGeometry]).
 */
sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : PathCommand
    data object Close : PathCommand
}

/**
 * Builds the hull contour of a ship from its cell indices (row-major, [cols] columns).
 *
 * - Straight ships of 1 to 5 cells: one closed contour with rounded bow and stern.
 * - The 4-cell T-shaped seaplane (a bar of 3 cells with a wing cell beside the middle one): one closed
 *   contour with a straight wing.
 * - Any other shape: one small closed contour per cell (the fallback; never used by the game's fleet).
 *
 * Pure geometry, so the JVM tests can check the contours without Compose.
 */
object ShipGeometry {

    private const val INSET = 0.08f
    private const val HALF_WIDTH = 0.3f

    fun outline(cells: List<Int>, cols: Int): List<PathCommand> {
        require(cols > 0) { "cols must be positive" }
        if (cells.isEmpty()) return emptyList()
        val rc = cells.map { it / cols to it % cols }.distinct()
        val rows = rc.map { it.first }.toSet()
        val columns = rc.map { it.second }.toSet()
        if (rc.size == 1 || rows.size == 1 || columns.size == 1) {
            straightOrFallback(rc)?.let { return it }
        }
        if (rc.size == 4) seaplane(rc)?.let { return it }
        return fallback(rc)
    }

    private fun straightOrFallback(rc: List<Pair<Int, Int>>): List<PathCommand>? {
        if (rc.size == 1) {
            // A single cell is a one-cell hull, horizontal.
            val (row, col) = rc.single()
            return bar(col.toFloat(), row.toFloat(), 1, horizontal = true)
        }
        val sameRow = rc.map { it.first }.toSet().size == 1
        val sorted = rc.sortedBy { if (sameRow) it.second else it.first }
        val first = sorted.first()
        val last = sorted.last()
        val start = if (sameRow) first.second else first.first
        val end = if (sameRow) last.second else last.first
        // The cells must be contiguous, otherwise this is not one straight ship.
        if (end - start + 1 != rc.size) return null
        return if (sameRow) {
            bar(start.toFloat(), first.first.toFloat(), rc.size, horizontal = true)
        } else {
            bar(start.toFloat(), first.second.toFloat(), rc.size, horizontal = false)
        }
    }

    /**
     * Straight bar of [length] cells starting at cell [a0] along the axis, on the row (or column) [b].
     * Local coordinates: x runs along the ship (0..length), y across it (0..1).
     */
    private fun bar(a0: Float, b: Float, length: Int, horizontal: Boolean): List<PathCommand> {
        val n = length.toFloat()
        val left = INSET
        val right = n - INSET
        val top = 0.2f
        val bottom = 0.8f
        val cy = 0.5f
        val tip = minOf(0.25f, (right - left) * 0.3f)
        val commands = listOf(
            PathCommand.MoveTo(left + tip, top),
            PathCommand.LineTo(right - tip, top),
            PathCommand.CubicTo(right - tip + tip * 0.6f, top, right, cy - 0.2f, right, cy),
            PathCommand.CubicTo(right, cy + 0.2f, right - tip + tip * 0.6f, bottom, right - tip, bottom),
            PathCommand.LineTo(left + tip, bottom),
            PathCommand.CubicTo(left + tip * 0.4f, bottom, left, cy + 0.2f, left, cy),
            PathCommand.CubicTo(left, cy - 0.2f, left + tip * 0.4f, top, left + tip, top),
            PathCommand.Close,
        )
        return commands.map { mapLocal(it, a0, b, horizontal, flip = false) }
    }

    /** T-shaped seaplane: bar of 3 cells with a wing on one side of the middle cell. */
    private fun seaplane(rc: List<Pair<Int, Int>>): List<PathCommand>? {
        val set = rc.toSet()
        // The junction is the only cell with three neighbours in the set.
        val junction = rc.firstOrNull { (r, c) ->
            listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1).count { it in set } == 3
        } ?: return null
        val (jr, jc) = junction
        val horizontalBar = (jr to jc - 1) in set && (jr to jc + 1) in set
        val verticalBar = (jr - 1 to jc) in set && (jr + 1 to jc) in set
        val wingSign: Int
        val wingCell: Pair<Int, Int>
        val expected: Set<Pair<Int, Int>>
        return if (horizontalBar) {
            wingSign = if ((jr - 1 to jc) in set) -1 else 1
            wingCell = jr + wingSign to jc
            expected = setOf(jr to jc - 1, jr to jc, jr to jc + 1, wingCell)
            if (expected != set) return null
            seaplaneContour(a0 = (jc - 1).toFloat(), b = jr.toFloat(), horizontal = true, flip = wingSign > 0)
        } else if (verticalBar) {
            wingSign = if ((jr to jc - 1) in set) -1 else 1
            wingCell = jr to jc + wingSign
            expected = setOf(jr - 1 to jc, jr to jc, jr + 1 to jc, wingCell)
            if (expected != set) return null
            seaplaneContour(a0 = (jr - 1).toFloat(), b = jc.toFloat(), horizontal = false, flip = wingSign > 0)
        } else {
            null
        }
    }

    private fun seaplaneContour(a0: Float, b: Float, horizontal: Boolean, flip: Boolean): List<PathCommand> {
        // Local coordinates with the wing above the bar (negative y); `flip` mirrors it below.
        val local = listOf(
            PathCommand.MoveTo(0.12f, 0.2f),
            PathCommand.LineTo(1.3f, 0.2f),
            PathCommand.LineTo(1.3f, -0.8f),
            PathCommand.LineTo(1.7f, -0.8f),
            PathCommand.LineTo(1.7f, 0.2f),
            PathCommand.LineTo(2.7f, 0.2f),
            PathCommand.CubicTo(2.9f, 0.2f, 2.98f, 0.4f, 2.98f, 0.5f),
            PathCommand.CubicTo(2.98f, 0.6f, 2.9f, 0.8f, 2.7f, 0.8f),
            PathCommand.LineTo(0.3f, 0.8f),
            PathCommand.CubicTo(0.1f, 0.8f, 0.02f, 0.6f, 0.02f, 0.5f),
            PathCommand.CubicTo(0.02f, 0.4f, 0.1f, 0.2f, 0.12f, 0.2f),
            PathCommand.Close,
        )
        return local.map { mapLocal(it, a0, b, horizontal, flip) }
    }

    /** Per-cell fallback: one rectangle per cell. */
    private fun fallback(rc: List<Pair<Int, Int>>): List<PathCommand> {
        val inset = 0.12f
        return rc.flatMap { (row, col) ->
            val l = col + inset
            val r = col + 1 - inset
            val t = row + inset
            val bt = row + 1 - inset
            listOf(
                PathCommand.MoveTo(l, t),
                PathCommand.LineTo(r, t),
                PathCommand.LineTo(r, bt),
                PathCommand.LineTo(l, bt),
                PathCommand.Close,
            )
        }
    }

    /**
     * Maps a local point (x along the ship from [a0], y across it from row/column [b]) to board cell units.
     * With [horizontal] false the axes swap: the ship runs down the rows.
     */
    private fun mapLocal(command: PathCommand, a0: Float, b: Float, horizontal: Boolean, flip: Boolean): PathCommand {
        fun u(x: Float, y: Float): Pair<Float, Float> {
            val yy = if (flip) 1f - y else y
            return if (horizontal) (a0 + x) to (b + yy) else (b + yy) to (a0 + x)
        }
        return when (command) {
            is PathCommand.MoveTo -> u(command.x, command.y).let { PathCommand.MoveTo(it.first, it.second) }
            is PathCommand.LineTo -> u(command.x, command.y).let { PathCommand.LineTo(it.first, it.second) }
            is PathCommand.CubicTo -> {
                val c1 = u(command.x1, command.y1)
                val c2 = u(command.x2, command.y2)
                val end = u(command.x, command.y)
                PathCommand.CubicTo(c1.first, c1.second, c2.first, c2.second, end.first, end.second)
            }
            PathCommand.Close -> PathCommand.Close
        }
    }
}
