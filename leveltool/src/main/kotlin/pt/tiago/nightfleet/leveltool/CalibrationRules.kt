package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.QuantilesDto
import pt.tiago.nightfleet.core.content.StarsDto

/**
 * Pure calibration rules: bands, quantiles, star thresholds, the hunt
 * budget and the tuning search. Everything is integer arithmetic so the results are the same on every
 * machine and thread count.
 */

/** Inclusive win-rate band in per-mille. */
internal data class Band(val low: Int, val high: Int) {
    fun contains(permille: Int): Boolean = permille in low..high

    /** How far a rate is from the band; 0 inside it. */
    fun distance(permille: Int): Int = when {
        permille < low -> low - permille
        permille > high -> permille - high
        else -> 0
    }
}

/** Target win-rate band of the simulated player of the same difficulty. */
internal fun bandOf(kind: MissionKind, difficulty: Difficulty): Band = when (kind) {
    MissionKind.DUEL, MissionKind.CONVOY -> when (difficulty) {
        Difficulty.EASY -> Band(800, 920)
        Difficulty.MEDIUM -> Band(600, 750)
        Difficulty.HARD -> Band(450, 600)
    }
    MissionKind.HUNT -> when (difficulty) {
        Difficulty.EASY -> Band(850, 950)
        Difficulty.MEDIUM -> Band(700, 850)
        Difficulty.HARD -> Band(550, 700)
    }
    MissionKind.BOSS -> when (difficulty) {
        Difficulty.EASY -> Band(720, 880)
        Difficulty.MEDIUM -> Band(500, 650)
        Difficulty.HARD -> Band(350, 500)
    }
    MissionKind.PUZZLE -> throw IllegalArgumentException("puzzles are not calibrated against the simulated player")
}

/** Share of a value list in per-mille, rounded down. */
internal fun permille(part: Int, total: Int): Int = if (total == 0) 0 else (part.toLong() * 1000L / total).toInt()

/**
 * Nearest-rank percentile of [sorted] (ascending), for [percent] in 1..100. Integer result, monotone in
 * [percent], so the quantile list never decreases.
 */
internal fun nearestRank(sorted: List<Int>, percent: Int): Int {
    require(sorted.isNotEmpty()) { "no samples" }
    require(percent in 1..100) { "percent must be 1..100" }
    val rank = ((percent.toLong() * sorted.size + 99) / 100).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

internal fun quantilesOf(shots: List<Int>): QuantilesDto {
    val sorted = shots.sorted()
    return QuantilesDto(
        p10 = nearestRank(sorted, 10),
        p35 = nearestRank(sorted, 35),
        p50 = nearestRank(sorted, 50),
        p70 = nearestRank(sorted, 70),
        p90 = nearestRank(sorted, 90),
    )
}

/** Star thresholds: two stars at the 70 % quantile, three at the 35 % quantile. */
internal fun starsOf(quantiles: QuantilesDto): StarsDto = StarsDto(two = quantiles.p70, three = quantiles.p35)

/** Budget factor of a difficulty for HUNT, per-mille: 1.60 / 1.45 / 1.30. */
internal fun baseBudgetFactorPermille(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> 1600
    Difficulty.MEDIUM -> 1450
    Difficulty.HARD -> 1300
}

/** `ceil(mean × factor)` with the mean given as `sum / count`, all in integers. Never below 1. */
internal fun huntBudget(referenceSum: Long, referenceCount: Int, factorPermille: Int): Int {
    require(referenceCount > 0) { "the reference needs at least one layout" }
    val numerator = referenceSum * factorPermille
    val denominator = referenceCount.toLong() * 1000L
    return ((numerator + denominator - 1) / denominator).toInt().coerceAtLeast(1)
}

/** Share of the reference shot counts that fit in [budget], per-mille. */
internal fun clearPermille(referenceShots: List<Int>, budget: Int): Int =
    permille(referenceShots.count { it <= budget }, referenceShots.size)

/** HARD must clear at least this share of its pool within the hunt budget. */
internal const val HUNT_CLEAR_MIN_PERMILLE = 970

/**
 * Adjustments allowed after the first measurement of a variant. The follow-up miss and the blunder take at most
 * 8; the weapon delay and the survive rounds take what is left of this budget, 12 in all.
 */
internal const val MAX_TUNING_ROUNDS = 16

/**
 * The admiral's weapon delay in turns: the profiles use 0 to 6, and 999 means the admiral never uses a weapon
 *. Below the band with the blunder at its maximum, the delay rises in the steps
 * of [WEAPON_DELAY_LADDER] (4, 8, 16, then 999), and last the admiral's weapon set is emptied.
 */
internal const val WEAPON_NEVER = 999
internal const val WEAPON_DELAY_MAX = WEAPON_NEVER
internal val WEAPON_DELAY_LADDER: List<Int> = listOf(4, 8, 16, WEAPON_NEVER)

/** Grid of the follow-up miss, per-mille: 0 to 900 in steps of 75 (13 values). */
internal const val FOLLOW_UP_STEP = 75
internal const val FOLLOW_UP_MAX = 900
internal val FOLLOW_UP_GRID: IntArray = IntArray(FOLLOW_UP_MAX / FOLLOW_UP_STEP + 1) { it * FOLLOW_UP_STEP }

/** Grid of the blunder rate, per-mille: 0 to 600 in steps of 40 (16 values). */
internal const val BLUNDER_STEP = 40
internal const val BLUNDER_MAX = 1000
internal val BLUNDER_GRID: IntArray = IntArray(BLUNDER_MAX / BLUNDER_STEP + 1) { it * BLUNDER_STEP }

/** Grid of the HUNT budget factor, per-mille: 1000 to 3000 in steps of 10 (201 values). Coarser steps jump the win rate across the band (see [tuneFactor]). */
internal const val FACTOR_STEP = 10
internal const val FACTOR_MIN = 1000
internal const val FACTOR_MAX = 3000
internal val FACTOR_GRID: IntArray = IntArray((FACTOR_MAX - FACTOR_MIN) / FACTOR_STEP + 1) { FACTOR_MIN + it * FACTOR_STEP }

/**
 * The tier escalation: when the kept result is still above the band and the admiral
 * at its weakest (no blunder, no follow-up miss) was measured above the band too, the admiral is raised one tier (Easy
 * to Medium, Medium to Hard). Null otherwise, and never above Hard. [history] is every measurement as (knobs, rate).
 */
internal fun escalationTarget(keptRate: Int, history: List<Pair<Knobs, Int>>, band: Band, tier: Difficulty): Difficulty? {
    val weakestAboveBand = history.any { (knobs, rate) -> rate > band.high && knobs.blunder == 0 && knobs.followUp == 0 }
    return if (keptRate > band.high && weakestAboveBand && tier != Difficulty.HARD) Difficulty.entries[tier.ordinal + 1] else null
}

/**
 * The knobs an admiral variant is tuned with: follow-up miss, blunder, the admiral's weapon delay (turns, the start
 * value is the effective one of the variant) and, for CONVOY only, the survive rounds and the freighters that must
 * stay afloat.
 */
internal data class Knobs(
    val followUp: Int,
    val blunder: Int,
    val weaponDelay: Int,
    val surviveRounds: Int? = null,
    val minFreighters: Int? = null,
    /** The admiral's weapon set is emptied: it never fires a weapon (the last lever, see [WEAPON_DELAY_LADDER]). */
    val weaponsOff: Boolean = false,
)

/** Index of the grid value nearest to [value]; the lower index wins a tie. */
internal fun nearestIndex(grid: IntArray, value: Int): Int = grid.indices.minBy { kotlin.math.abs(grid[it] - value) }

/**
 * Binary search over one knob, given as a sorted [grid]. The win rate grows with the index, so a rate below
 * the band moves the search up and a rate above it moves it down. It stops at the first rate inside [band],
 * when the interval is empty, or when [rateAt] returns null (budget used up). [start] is the first index.
 */
internal fun bisectIndex(grid: IntArray, start: Int, band: Band, rateAt: (Int) -> Int?) {
    var lo = 0
    var hi = grid.lastIndex
    var mid = start
    while (lo <= hi) {
        val rate = rateAt(mid) ?: return
        if (band.contains(rate)) return
        if (rate < band.low) lo = mid + 1 else hi = mid - 1
        mid = (lo + hi) ushr 1
    }
}

/** One measurement of a search: its round (0 is the first), the knobs, the rate and what the caller keeps. */
internal class Measured<K, T>(val round: Int, val knobs: K, val winPermille: Int, val payload: T)

/**
 * A budgeted search. Every measurement is a round; round 0 is the first one and at most
 * [maxRounds] adjustments follow it. The kept measurement is the one whose rate is closest to the band (the
 * earliest one on ties), so the result never depends on the order of the probes beyond their own rates.
 */
internal class TuningSearch<K, T>(val band: Band, private val maxRounds: Int = MAX_TUNING_ROUNDS) {
    private var kept: Measured<K, T>? = null
    private var rounds = 0

    /** Measurements run so far, the first one included. */
    val measurements: Int get() = rounds

    /** Adjustments that led to the kept values: 0 when the first measurement is kept. */
    val adjustments: Int get() = (kept?.round ?: 0)

    /** True when no more measurement may run. */
    val exhausted: Boolean get() = rounds > maxRounds

    /** The closest measurement so far, or null before the first one. */
    val best: Measured<K, T>? get() = kept

    /** True when the closest measurement is inside the band. */
    val inBand: Boolean get() = kept?.let { band.contains(it.winPermille) } ?: false

    /** True when the search has nothing left to do: a rate inside the band, or the budget used up. */
    val finished: Boolean get() = inBand || exhausted

    /** Runs [run] for [knobs] unless the budget is used up. Returns the rate, or null when nothing was run. */
    /** Every measurement so far, in order (the escalation rule reads the admiral's weakest one). */
    val history: List<Measured<K, T>> get() = measured

    private val measured = mutableListOf<Measured<K, T>>()

    fun measure(knobs: K, run: (K) -> Pair<Int, T>): Int? {
        if (exhausted) return null
        val (rate, payload) = run(knobs)
        val current = Measured(rounds, knobs, rate, payload)
        measured += current
        rounds++
        val previous = kept
        if (previous == null || band.distance(rate) < band.distance(previous.winPermille)) kept = current
        return rate
    }
}
/** CONVOY survive rounds: steps of 4 within 8..60. */
internal const val SURVIVE_STEP = 4
internal const val SURVIVE_MIN = 8
internal const val SURVIVE_MAX = 60

/** CONVOY freighters that must stay afloat: the player fleet always holds [FREIGHTERS] of them. */
internal const val FREIGHTERS = 3
internal const val MIN_FREIGHTERS_MAX = FREIGHTERS

/** Rounds of a CONVOY search: the mission levers come on top of the admiral levers (see [tuneConvoy]). */
internal const val CONVOY_TUNING_ROUNDS = 20

/** Survive rounds from 60 down to 8: a larger index is a shorter convoy, so the rate grows with the index. */
internal val SURVIVE_GRID: IntArray = IntArray((SURVIVE_MAX - SURVIVE_MIN) / SURVIVE_STEP + 1) { SURVIVE_MAX - it * SURVIVE_STEP }

/**
 * One admiral lever pass, as in a duel: the follow-up miss, then the blunder, each a binary search over
 * its grid while no measurement is inside the band. Then, still below the band with the blunder at its maximum (the
 * blunder only spares the shots, not the weapons), the weapons are the lever: the delay rises in the steps of
 * [WEAPON_DELAY_LADDER], and last the admiral's weapon set is emptied.
 */
private fun <T> weakenAdmiral(search: TuningSearch<Knobs, T>, start: Knobs, run: (Knobs) -> Pair<Int, T>) {
    val survive = start.surviveRounds
    val freighters = start.minFreighters
    bisectIndex(FOLLOW_UP_GRID, nearestIndex(FOLLOW_UP_GRID, start.followUp), search.band) { i ->
        search.measure(Knobs(FOLLOW_UP_GRID[i], start.blunder, start.weaponDelay, survive, freighters), run)
    }
    if (!search.finished) {
        val followUp = checkNotNull(search.best).knobs.followUp
        bisectIndex(BLUNDER_GRID, nearestIndex(BLUNDER_GRID, start.blunder), search.band) { i ->
            search.measure(Knobs(followUp, BLUNDER_GRID[i], start.weaponDelay, survive, freighters), run)
        }
    }
    val kept = checkNotNull(search.best)
    if (!search.finished && kept.knobs.blunder == BLUNDER_MAX && kept.winPermille < search.band.low) {
        val base = kept.knobs
        for (delay in WEAPON_DELAY_LADDER) {
            if (search.finished) break
            if (delay <= base.weaponDelay) continue
            search.measure(base.copy(weaponDelay = delay), run)
        }
        if (!search.finished) search.measure(base.copy(weaponDelay = WEAPON_NEVER, weaponsOff = true), run)
    }
}

/**
 * Tunes an admiral variant of a DUEL or a BOSS: the admiral levers of [weakenAdmiral], in order.
 */
internal fun <T> tuneAdmiral(start: Knobs, band: Band, run: (Knobs) -> Pair<Int, T>): TuningSearch<Knobs, T> {
    val search = TuningSearch<Knobs, T>(band)
    weakenAdmiral(search, start, run)
    return search
}

/** A binary search over the survive rounds of [base] (the rest of the knobs kept). */
private fun <T> roundsSearch(search: TuningSearch<Knobs, T>, base: Knobs, run: (Knobs) -> Pair<Int, T>) {
    val rounds = base.surviveRounds ?: return
    bisectIndex(SURVIVE_GRID, nearestIndex(SURVIVE_GRID, rounds), search.band) { i ->
        search.measure(base.copy(surviveRounds = SURVIVE_GRID[i]), run)
    }
}

/**
 * Tunes a CONVOY. The player wins by surviving the rounds with the freighters
 * afloat, so the rate is the chance of survival:
 * - above the band, the admiral is set to its strongest first (no blunder, no follow-up miss, no weapon delay);
 *   then the survive rounds grow (binary search in steps of [SURVIVE_STEP], 8 to 60); then, at the top, the
 *   freighters that must stay afloat go up from the start value to [MIN_FREIGHTERS_MAX] and the rounds are searched
 *   again for each;
 * - below the band, the survive rounds shrink first, and then the admiral is weakened as in a duel.
 */
internal fun <T> tuneConvoy(start: Knobs, band: Band, run: (Knobs) -> Pair<Int, T>): TuningSearch<Knobs, T> {
    val search = TuningSearch<Knobs, T>(band, CONVOY_TUNING_ROUNDS)
    val first = search.measure(start, run) ?: return search
    if (search.finished) return search
    if (first > band.high) {
        val strong = start.copy(followUp = 0, blunder = 0, weaponDelay = 0)
        if (strong != start) search.measure(strong, run)
        if (!search.finished) roundsSearch(search, strong, run)
        var freighters = checkNotNull(start.minFreighters)
        while (!search.finished && freighters < MIN_FREIGHTERS_MAX) {
            freighters++
            roundsSearch(search, strong.copy(minFreighters = freighters), run)
        }
    } else {
        roundsSearch(search, start, run)
        if (!search.finished) weakenAdmiral(search, checkNotNull(search.best).knobs, run)
    }
    return search
}

/**
 * Tunes the budget factor of a HUNT by binary search over [FACTOR_GRID], from [startFactor].
 * A larger factor gives a larger budget, so a rate below the band moves the search up.
 */
internal fun <T> tuneFactor(startFactor: Int, band: Band, run: (Int) -> Pair<Int, T>): TuningSearch<Int, T> {
    val search = TuningSearch<Int, T>(band, HUNT_TUNING_ROUNDS)
    bisectIndex(FACTOR_GRID, nearestIndex(FACTOR_GRID, startFactor), band) { i -> search.measure(FACTOR_GRID[i], run) }
    // The rate is noisy (64 games) and steep near a budget edge, so a bisection can stop on a rate just outside the
    // band while a neighbour factor is inside it. Then the factors next to the kept one are measured, nearest first.
    if (!search.finished) {
        val center = nearestIndex(FACTOR_GRID, checkNotNull(search.best).knobs)
        for (distance in 1..FACTOR_SWEEP_RADIUS) for (index in listOf(center - distance, center + distance)) {
            if (search.finished) break
            if (index in FACTOR_GRID.indices) search.measure(FACTOR_GRID[index], run)
        }
    }
    return search
}

/** Measurements a HUNT search may run: the bisection plus the sweep around its result (see [tuneFactor]). */
internal const val HUNT_TUNING_ROUNDS = 64

/** How many grid steps on each side of the bisection result the sweep looks at. */
internal const val FACTOR_SWEEP_RADIUS = 20
