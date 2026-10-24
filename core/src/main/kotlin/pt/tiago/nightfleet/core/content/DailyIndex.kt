package pt.tiago.nightfleet.core.content

import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * Daily challenge selection. Pure and shared by leveltool and the app.
 *
 * The index of a day is `(a * (day mod N) + b) mod N`, where `N` is the number of entries of the pool for the
 * kind and difficulty ([DAILY_POOL_SIZE] for the designed pool). Because `a` is coprime with `N`, the map is a
 * permutation of the residues: any `N` consecutive days use every entry exactly once, so no entry repeats inside
 * that window. `a` and `b` come from the pool version, the kind and the difficulty, so each version shuffles
 * differently.
 */
object DailyIndex {
    const val DAILY_POOL_SIZE: Int = 400

    /** Parameters `(a, b)` of the permutation for a pool [version], [kind], [difficulty] and [size] entries. */
    fun parameters(version: Int, kind: DailyKind, difficulty: Difficulty, size: Int = DAILY_POOL_SIZE): Pair<Int, Int> {
        require(version >= 1) { "Daily pool version must be at least 1" }
        require(size >= 1) { "Daily pool must have at least one entry" }
        val stream = (kind.ordinal.toLong() * 3L + difficulty.ordinal.toLong()) + 1L
        val seed = SplitMix64.split(version.toLong(), stream)
        val n = size.toLong()
        var a = java.lang.Long.remainderUnsigned(seed, n)
        // The multiplier must be coprime with N. Stepping by one always reaches 1, which is coprime with everything.
        while (gcd(a, n) != 1L) a = (a + 1) % n
        val b = java.lang.Long.remainderUnsigned(SplitMix64.mix(seed), n)
        return a.toInt() to b.toInt()
    }

    /** Index into the pool for [epochDay] (days since 1970-01-01, as `LocalDate.toEpochDay`). */
    fun indexOf(
        epochDay: Long,
        version: Int,
        kind: DailyKind,
        difficulty: Difficulty,
        size: Int = DAILY_POOL_SIZE,
    ): Int {
        val (a, b) = parameters(version, kind, difficulty, size)
        val n = size.toLong()
        val residue = Math.floorMod(epochDay, n)
        return Math.floorMod(a.toLong() * residue + b.toLong(), n).toInt()
    }

    private fun gcd(x: Long, y: Long): Long = if (y == 0L) x else gcd(y, x % y)
}
