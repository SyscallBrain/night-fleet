package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64

/** Shapes of the island and reef patterns. */
internal enum class TerrainKind(val family: String, val symbol: Char) {
    SCATTER("islands-scatter", '#'),
    RING("islands-ring", '#'),
    ARCHIPELAGO("archipelago-corridor", '#'),
    REEF("reef-maze", '~'),
}

/** Symmetries of a square grid: the eight elements of the dihedral group, identity first. */
internal object Symmetry {
    private val MAPS: List<(Int, Int, Int) -> Pair<Int, Int>> = listOf(
        { r, c, _ -> r to c },
        { r, c, _ -> c to r },
        { r, c, n -> r to n - 1 - c },
        { r, c, n -> n - 1 - r to c },
        { r, c, n -> n - 1 - r to n - 1 - c },
        { r, c, n -> c to n - 1 - r },
        { r, c, n -> n - 1 - c to r },
        { r, c, n -> n - 1 - c to n - 1 - r },
    )

    val COUNT: Int get() = MAPS.size

    fun apply(symmetry: Int, row: Int, col: Int, side: Int): Pair<Int, Int> = MAPS[symmetry](row, col, side)

    /** Indices of the symmetries that map [terrain] onto itself (the identity is always one of them). */
    fun invariant(terrain: List<Terrain>, side: Int): List<Int> = (0 until COUNT).filter { s ->
        (0 until side * side).all { i ->
            val (r, c) = i / side to i % side
            val (r2, c2) = apply(s, r, c, side)
            terrain[r2 * side + c2] == terrain[i]
        }
    }
}

/** Canonical text of a pattern: the smallest of its eight symmetric images. Equal keys mean symmetric duplicates. */
internal fun terrainCanonicalKey(rows: List<String>): String {
    val side = rows.size
    val symbols = rows.joinToString("")
    var best: String? = null
    for (s in 0 until Symmetry.COUNT) {
        val out = CharArray(side * side)
        for (i in 0 until side * side) {
            val (r, c) = i / side to i % side
            val (r2, c2) = Symmetry.apply(s, r, c, side)
            out[r2 * side + c2] = symbols[i]
        }
        val text = String(out)
        if (best == null || text < best) best = text
    }
    return best!!
}

/** Terrain cells of rows (`.` water, `#` island, `~` reef). */
internal fun rowsToTerrain(rows: List<String>): List<Terrain> = rows.flatMap { row ->
    row.map { symbol ->
        when (symbol) {
            '#' -> Terrain.ISLAND
            '~' -> Terrain.REEF
            else -> Terrain.WATER
        }
    }
}

/** Generates seeded patterns. Every pattern is checked against the terrain rules. */
internal object TerrainGen {
    /** Cells of islands or reefs in a `side` x `side` grid: 3 to 9 on 10x10, scaled to 8x8 and 12x12. */
    fun countRange(side: Int): IntRange = when (side) {
        8 -> 2..6
        10 -> 3..9
        else -> 4..12
    }

    /**
     * One pattern of [kind] for a `side` x `side` grid that a [fleet] can be placed on. A pattern is
     * rejected when it isolates a single water square (no ship can use it), when the fleet cannot be
     * placed on it, or when its canonical form is already in [used] (symmetric duplicates in a sea).
     * Returns null after too many rejections. Adds the accepted pattern to [used].
     */
    fun generate(side: Int, kind: TerrainKind, fleet: Fleet, rng: SplitMix64, used: MutableSet<String>): List<String>? {
        val range = countRange(side)
        repeat(MAX_TRIES) {
            val count = range.first + rng.nextInt(range.last - range.first + 1)
            val cells = pick(side, kind, count, rng) ?: return@repeat
            val grid = CharArray(side * side) { '.' }
            for (cell in cells) grid[cell] = kind.symbol
            val rows = (0 until side).map { String(grid, it * side, side) }
            if (hasIsolatedWater(rows)) return@repeat
            val key = terrainCanonicalKey(rows)
            if (key in used) return@repeat
            val board = Board(side, side, rowsToTerrain(rows), emptyList())
            val placed = RandomPlacer.place(board, fleet, SplitMix64(rng.nextLong()), PlacementStyle.BALANCED, false)
            if (placed !is PlacementResult.Success) return@repeat
            used += key
            return rows
        }
        return null
    }

    private const val MAX_TRIES = 400

    private fun pick(side: Int, kind: TerrainKind, count: Int, rng: SplitMix64): List<Int>? {
        val all = (0 until side * side).toList()
        return when (kind) {
            TerrainKind.SCATTER, TerrainKind.REEF -> sample(all, count, rng)
            TerrainKind.RING -> {
                val centre = (side - 1) / 2.0
                val ring = all.filter { i ->
                    val dr = i / side - centre
                    val dc = i % side - centre
                    val d = Math.sqrt(dr * dr + dc * dc)
                    d >= side * 0.2 && d <= side * 0.42
                }
                sample(ring, count, rng)
            }
            TerrainKind.ARCHIPELAGO -> grow(side, count, rng)
        }
    }

    private fun sample(items: List<Int>, count: Int, rng: SplitMix64): List<Int>? {
        if (items.size < count) return null
        val pool = items.toMutableList()
        for (i in 0 until count) {
            val j = i + rng.nextInt(pool.size - i)
            val tmp = pool[i]
            pool[i] = pool[j]
            pool[j] = tmp
        }
        return pool.take(count)
    }

    /** Small clumps: a new clump starts at a random cell, otherwise a cell next to the existing ones is added. */
    private fun grow(side: Int, count: Int, rng: SplitMix64): List<Int>? {
        val chosen = LinkedHashSet<Int>()
        var guard = 0
        while (chosen.size < count) {
            if (++guard > 10_000) return null
            val startNew = chosen.isEmpty() || rng.nextInt(4) == 0
            if (startNew) {
                chosen += rng.nextInt(side * side)
                continue
            }
            val base = chosen.elementAt(rng.nextInt(chosen.size))
            val r = base / side
            val c = base % side
            val options = listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1)
                .filter { (rr, cc) -> rr in 0 until side && cc in 0 until side }
                .map { (rr, cc) -> rr * side + cc }
                .filter { it !in chosen }
            if (options.isNotEmpty()) chosen += options[rng.nextInt(options.size)]
        }
        return chosen.toList()
    }

    /** True when some water square is walled in on all four sides (a single square no ship can use). */
    fun hasIsolatedWater(rows: List<String>): Boolean {
        val side = rows.size
        for (r in 0 until side) for (c in 0 until side) {
            if (rows[r][c] != '.') continue
            val open = listOf(r - 1 to c, r + 1 to c, r to c - 1, r to c + 1).count { (rr, cc) ->
                rr in 0 until side && cc in 0 until side && rows[rr][cc] == '.'
            }
            if (open == 0) return true
        }
        return false
    }
}
