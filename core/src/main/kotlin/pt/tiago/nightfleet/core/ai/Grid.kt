package pt.tiago.nightfleet.core.ai

import kotlin.math.abs
import kotlin.math.max

/** Index arithmetic on a row-major grid. Every helper returns indices in ascending order. */
internal object Grid {

    private val DIRECTIONS = arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    /** The eight neighbours of [index] (diagonals included), ascending. */
    fun neighbours8(index: Int, w: Int, h: Int): IntArray {
        val r = index / w
        val c = index % w
        val out = ArrayList<Int>(8)
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val rr = r + dr
            val cc = c + dc
            if (rr in 0 until h && cc in 0 until w) out += rr * w + cc
        }
        return out.toIntArray()
    }

    /** The four orthogonal neighbours of [index], ascending. */
    fun orthogonal(index: Int, w: Int, h: Int): IntArray {
        val r = index / w
        val c = index % w
        val out = ArrayList<Int>(4)
        for ((dr, dc) in DIRECTIONS) {
            val rr = r + dr
            val cc = c + dc
            if (rr in 0 until h && cc in 0 until w) out += rr * w + cc
        }
        return out.sorted().toIntArray()
    }

    /** The square itself and its eight neighbours. */
    fun closed8(index: Int, w: Int, h: Int): IntArray = (neighbours8(index, w, h) + index).sorted().toIntArray()

    /** The 3 by 3 window centred on [center], clipped to the grid. */
    fun box(center: Int, w: Int, h: Int): IntArray = closed8(center, w, h)

    /** The cross of [center]: the centre and its orthogonal neighbours, clipped, ascending. */
    fun cross(center: Int, w: Int, h: Int): IntArray = (orthogonal(center, w, h) + center).sorted().toIntArray()

    /** Squares of a whole row ([isRow]) or column, in ascending order. */
    fun line(isRow: Boolean, index: Int, w: Int, h: Int): IntArray =
        if (isRow) IntArray(w) { index * w + it } else IntArray(h) { it * w + index }

    fun isEdge(index: Int, w: Int, h: Int): Boolean {
        val r = index / w
        val c = index % w
        return r == 0 || c == 0 || r == h - 1 || c == w - 1
    }

    /** Distance from the centre of the grid, doubled and in Chebyshev metric: 0 at the centre. */
    fun centreDistance(index: Int, w: Int, h: Int): Int {
        val r = index / w
        val c = index % w
        return max(abs(2 * r - (h - 1)), abs(2 * c - (w - 1)))
    }
}
