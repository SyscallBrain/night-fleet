package pt.tiago.nightfleet.core.ai

/**
 * Placement density (HARD). For each live class, every legal placement of that class adds its
 * weight to the squares it covers; each class is normalised so that it counts once, whatever its number
 * of placements, and weighted by how many ships of the class remain.
 *
 * - [score]: per square, scaled by [SCALE]; 0 on squares that are not unknown.
 * - [rowP], [colP]: per line, the scaled probability that a live ship touches it.
 * - [total]: sum of [score]. Zero means no live ship fits anywhere.
 */
internal class Density(
    val score: LongArray,
    val rowP: LongArray,
    val colP: LongArray,
    val total: Long,
    val placements: Int,
) {
    companion object {
        const val SCALE = 1_000_000L

        /** Placements that cover an open hit, in target mode, weigh this much more. */
        const val TARGET_WEIGHT = 20L

        /** Density of the view with the weak evidence excluded; falls back to using it when that leaves nothing. */
        fun forKnowledge(k: Knowledge, targetMode: Boolean = k.openHits.isNotEmpty()): Density {
            val target = targetMode
            val strict = compute(k, target, weakExcluded = true)
            if (strict.total == 0L && k.hasWeak) return compute(k, target, weakExcluded = false)
            return strict
        }

        fun compute(k: Knowledge, targetMode: Boolean, weakExcluded: Boolean): Density {
            val w = k.w
            val h = k.h
            val n = k.n
            val score = LongArray(n)
            val rowP = LongArray(h)
            val colP = LongArray(w)
            val seenRow = BooleanArray(h)
            val seenCol = BooleanArray(w)
            var placements = 0
            for (entry in k.view.enemyRemaining) {
                val shape = Geometry.of(w, h, entry.shipClass)
                val submersible = entry.shipClass.submersible
                val cell = LongArray(n)
                val rows = LongArray(h)
                val cols = LongArray(w)
                var classWeight = 0L
                for (squares in shape.squares) {
                    if (!k.candidate(submersible, squares, weakExcluded)) continue
                    val reading = k.readingWeight(squares, submersible)
                    if (reading == 0L) continue
                    // Open hits count only in target mode; repaired and contact squares always need a ship.
                    val covers = squares.any { (targetMode && k.hit[it]) || (k.mustCover[it] && !k.hit[it]) }
                    val weight = (if (covers) Density.TARGET_WEIGHT else 1L) * reading
                    classWeight += weight
                    placements++
                    for (x in squares) {
                        if (k.unknown[x]) cell[x] += weight
                        val r = x / w
                        val c = x % w
                        if (!seenRow[r]) {
                            seenRow[r] = true
                            rows[r] += weight
                        }
                        if (!seenCol[c]) {
                            seenCol[c] = true
                            cols[c] += weight
                        }
                    }
                    for (x in squares) {
                        seenRow[x / w] = false
                        seenCol[x % w] = false
                    }
                }
                if (classWeight == 0L) continue
                val count = entry.count.toLong()
                for (x in 0 until n) {
                    if (cell[x] != 0L) score[x] += count * cell[x] * SCALE / classWeight
                }
                for (r in 0 until h) {
                    if (rows[r] != 0L) rowP[r] = minOf(SCALE, rowP[r] + count * rows[r] * SCALE / classWeight)
                }
                for (c in 0 until w) {
                    if (cols[c] != 0L) colP[c] = minOf(SCALE, colP[c] + count * cols[c] * SCALE / classWeight)
                }
            }
            return Density(score, rowP, colP, score.sum(), placements)
        }
    }
}
