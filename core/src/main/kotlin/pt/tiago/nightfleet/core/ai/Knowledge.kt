package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.observation.KnownCell

/** A sonar reading as a constraint: exactly [count] live, unhit squares in the window of [center]. */
internal class SonarConstraint(val center: Int, val count: Int, val box: IntArray, val inBox: BooleanArray)

/** A recon reading as a constraint on one line. */
internal class ReconConstraint(val isRow: Boolean, val index: Int, val present: Boolean, val line: IntArray, val inLine: BooleanArray)

/**
 * What a view proves about the enemy fleet, square by square. Built once per decision.
 *
 * Every rule here is exact, so a placement that breaks one of them cannot belong to any fleet that
 * is consistent with the view:
 * - ships only on unknown, open-hit, repaired or contact squares of water terrain;
 * - no ship on a mine, on a square next to a mine, or next to a sunk ship (unless ships may touch);
 * - a contact square needs a submarine;
 * - a live ship that does not cover a must-cover square (open hit, repaired, contact) must not touch it,
 *   because that square belongs to another ship (unless ships may touch);
 * - a placement is not made only of open hits, since then it would be sunk.
 */
internal class Knowledge(val view: AiView) {
    val w = view.width
    val h = view.height
    val n = w * h
    val shipsMayTouch = view.shipsMayTouch
    val diving = view.diving

    /** Squares a shot may usefully go to: unknown, contact or repaired, and not a remembered miss ([AiView.freshCells]). */
    val unknown = BooleanArray(n)
    val hit = BooleanArray(n)
    val contact = BooleanArray(n)

    /** Squares that some live ship must occupy: open hits, repaired squares and contacts. */
    val mustCover = BooleanArray(n)

    /** Water shot before an enemy move: evidence that is only weak. */
    val weak = BooleanArray(n)

    private val blocked = BooleanArray(n)
    private val strictOk = BooleanArray(n)
    private val weakOk = BooleanArray(n)

    val nb8: Array<IntArray> = Array(n) { Grid.neighbours8(it, w, h) }
    val openHits: IntArray
    val mustList: IntArray
    val sonar: List<SonarConstraint>
    val recon: List<ReconConstraint>
    val hasWeak: Boolean

    init {
        for (x in 0 until n) {
            if (view.fresh(x)) unknown[x] = true
            when (view.kind(x)) {
                KnownCell.HIT -> {
                    hit[x] = true
                    mustCover[x] = true
                }
                KnownCell.REPAIRED -> mustCover[x] = true
                KnownCell.CONTACT -> {
                    contact[x] = true
                    mustCover[x] = true
                }
                KnownCell.WATER -> weak[x] = view.isWeakWater(x)
                else -> Unit
            }
        }
        for (x in 0 until n) {
            val kind = view.kind(x)
            if (kind == KnownCell.MINE) {
                blocked[x] = true
                for (y in nb8[x]) blocked[y] = true
            }
            if (!shipsMayTouch && kind == KnownCell.SUNK) {
                blocked[x] = true
                for (y in nb8[x]) blocked[y] = true
            }
        }
        for (x in 0 until n) {
            val kind = view.kind(x)
            val live = kind == KnownCell.UNKNOWN || kind == KnownCell.HIT ||
                kind == KnownCell.REPAIRED || kind == KnownCell.CONTACT
            strictOk[x] = live && !blocked[x]
            weakOk[x] = weak[x] && !blocked[x]
        }
        openHits = (0 until n).filter { hit[it] }.toIntArray()
        mustList = (0 until n).filter { mustCover[it] }.toIntArray()
        hasWeak = weak.any { it }
        sonar = view.readings.filterIsInstance<SonarReading>().map { reading ->
            val center = view.index(reading.center)
            val box = Grid.box(center, w, h)
            SonarConstraint(center, reading.count, box, flags(box))
        }
        recon = view.readings.filterIsInstance<ReconReading>().map { reading ->
            val line = Grid.line(reading.isRow, reading.index, w, h)
            ReconConstraint(reading.isRow, reading.index, reading.present, line, flags(line))
        }
    }

    private fun flags(squares: IntArray): BooleanArray {
        val out = BooleanArray(n)
        for (x in squares) out[x] = true
        return out
    }

    /** Whether square [x] may hold a live ship square. Weak water is allowed only when [weakExcluded] is false. */
    fun allowed(x: Int, weakExcluded: Boolean): Boolean = strictOk[x] || (!weakExcluded && weakOk[x])

    /** Whether the squares [squares] can be one ship of a class ([submersible]) in some fleet consistent with the view. */
    fun candidate(submersible: Boolean, squares: IntArray, weakExcluded: Boolean): Boolean {
        var notHit = 0
        for (x in squares) {
            if (!allowed(x, weakExcluded)) return false
            if (contact[x] && !submersible) return false
            if (!hit[x]) notHit++
        }
        if (notHit == 0) return false
        if (!shipsMayTouch) {
            for (x in squares) for (y in nb8[x]) {
                if (mustCover[y] && !squares.contains(y)) return false
            }
        }
        return true
    }

    /**
     * Weight that the reading constraints give to a candidate placement, or 0 when a reading rules it
     * out. A sonar window with ship squares gets a factor; a line that the recon says is empty rules out
     * the surface ships on it (submersibles are not excluded when diving is on: they may be submerged).
     */
    fun readingWeight(squares: IntArray, submersible: Boolean): Long {
        var factor = 1L
        for (s in sonar) {
            var inside = 0
            for (x in squares) if (s.inBox[x] && !hit[x]) inside++
            if (inside > s.count) return 0L
            if (inside > 0) factor = minOf(factor * SONAR_FACTOR, FACTOR_CAP)
        }
        for (r in recon) {
            var any = 0
            for (x in squares) if (r.inLine[x] && !hit[x]) any++
            if (!r.present) {
                if (any > 0 && (!diving || !submersible)) return 0L
            } else if (any > 0) {
                factor = minOf(factor * RECON_FACTOR, FACTOR_CAP)
            }
        }
        return factor
    }

    companion object {
        const val SONAR_FACTOR = 3L
        const val RECON_FACTOR = 4L
        const val FACTOR_CAP = 65_536L
    }
}
