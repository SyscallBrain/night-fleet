package pt.tiago.nightfleet.core.ai

/** The officer's hint of the battle screen: the best squares by HARD density. */
object OfficerHint {

    /** Up to [n] unknown squares with the highest density, best first; ties go to the lower index. */
    fun topCells(view: AiView, n: Int = 3): List<Int> {
        val k = Knowledge(view)
        val d = Density.forKnowledge(k)
        return (0 until view.size)
            .filter { k.unknown[it] }
            .sortedWith(compareByDescending<Int> { d.score[it] }.thenBy { it })
            .take(n)
    }
}

/**
 * How good one shot was, measured on the view just before it. [probabilityPermille]
 * is the HARD density of the square; [bestCell] is the square with the highest density; a shot is
 * [wasted] when its density is zero (a square that no live ship can occupy, or one already known).
 */
data class ShotQuality(
    val probabilityPermille: Int,
    val bestCell: Int,
    val bestPermille: Int,
    val wasted: Boolean,
)

object ShotAnalysis {

    fun evaluate(view: AiView, chosenCell: Int): ShotQuality {
        require(chosenCell in 0 until view.size) { "Square $chosenCell is outside the grid" }
        val k = Knowledge(view)
        val d = Density.forKnowledge(k)
        val best = (0 until view.size)
            .filter { k.unknown[it] }
            .sortedWith(compareByDescending<Int> { d.score[it] }.thenBy { it })
            .firstOrNull() ?: chosenCell
        val probability = if (k.unknown[chosenCell]) toPermille(d.score[chosenCell]) else 0
        return ShotQuality(
            probabilityPermille = probability,
            bestCell = best,
            bestPermille = toPermille(d.score[best]),
            wasted = probability == 0,
        )
    }

    private fun toPermille(scaled: Long): Int = minOf(1000L, scaled / 1000L).toInt()
}
