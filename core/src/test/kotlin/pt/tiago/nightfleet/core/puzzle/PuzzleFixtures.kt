package pt.tiago.nightfleet.core.puzzle

/** Shared hand-made puzzles. 'S' marks a ship cell in a layout, '.' water. */
internal object PuzzleFixtures {
    /** A valid layout for [base]: 4 + 0 + 3 + 0 + 2 + 1 ships per row, 3 + 2 + 1 + 2 + 1 + 1 per column. */
    val LAYOUT: List<String> = listOf(
        "SSS..S",
        "......",
        "SS.S..",
        "......",
        "...SS.",
        "S.....",
    )

    /** Same counts and fleet as the layout, no givens. It has 15 solutions. */
    val base: PuzzleDefinition = PuzzleDefinition(
        id = "base",
        width = 6,
        height = 6,
        islands = emptyList(),
        fleet = listOf(3, 2, 2, 1, 1, 1),
        rowCounts = listOf(4, 0, 3, 0, 2, 1),
        colCounts = listOf(3, 2, 1, 2, 1, 1),
    )

    /** Ship cells of [LAYOUT] by index. */
    val layoutShips: Set<Int> = LAYOUT.indices
        .flatMap { r -> LAYOUT[r].indices.filter { LAYOUT[r][it] == 'S' }.map { r * 6 + it } }
        .toSet()

    /** [base] with every water cell given as water. Unique, solved by T1 alone. */
    val onlyWater: PuzzleDefinition = base.copy(
        id = "only-water",
        givens = (0 until 36).filter { it !in layoutShips }.associateWith { Given.WATER },
    )

    /** The demonstration puzzle (6x6, five clues). It has 2 solutions. */
    val demo: PuzzleDefinition = PuzzleDefinition(
        id = "demo-6",
        width = 6,
        height = 6,
        islands = emptyList(),
        fleet = listOf(3, 2, 2, 1, 1, 1),
        rowCounts = listOf(4, 0, 4, 1, 0, 1),
        colCounts = listOf(2, 2, 2, 1, 0, 3),
    )

    val demoGiven: PuzzleDefinition = demo.copy(
        givens = mapOf(
            0 to Given.SHIP,
            23 to Given.SHIP,
            31 to Given.SHIP,
            8 to Given.WATER,
            28 to Given.WATER,
        ),
    )

    /** A complete grid of [LAYOUT]: ships where the layout has 'S', water elsewhere. */
    fun layoutGrid(): PlayerGrid = PlayerGrid(
        6,
        6,
        (0 until 36).map { if (it in layoutShips) PlayerCell.SHIP else PlayerCell.WATER },
    )

    /** A grid from a list of rows of 'S' (ship), 'W' (water) and '.' (empty). */
    fun gridOf(rows: List<String>): PlayerGrid {
        val width = rows[0].length
        val cells = rows.flatMap { row ->
            row.map {
                when (it) {
                    'S' -> PlayerCell.SHIP
                    'W' -> PlayerCell.WATER
                    else -> PlayerCell.EMPTY
                }
            }
        }
        return PlayerGrid(width, rows.size, cells)
    }
}
