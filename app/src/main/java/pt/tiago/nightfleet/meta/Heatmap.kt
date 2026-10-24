package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.HEATMAP_CELLS

/** The shot heatmap of the Statistics screen: a 10 x 10 grid of counts, scaled to 0..1 by its busiest cell. */
object Heatmap {
    /** Counts padded or cut to 100 cells, negatives treated as zero. */
    fun cells(counts: List<Int>): List<Int> = List(HEATMAP_CELLS) { counts.getOrElse(it) { 0 }.coerceAtLeast(0) }

    /** True when no shot has been recorded yet, which shows the empty state. */
    fun isEmpty(counts: List<Int>): Boolean = cells(counts).all { it == 0 }

    /** Each cell divided by the largest one, so the busiest cell is 1f and an empty grid is all zeros. */
    fun normalize(counts: List<Int>): List<Float> {
        val values = cells(counts)
        val max = values.max()
        if (max == 0) return List(HEATMAP_CELLS) { 0f }
        return values.map { it.toFloat() / max }
    }
}
