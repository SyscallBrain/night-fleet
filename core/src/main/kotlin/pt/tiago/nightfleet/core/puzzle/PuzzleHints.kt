package pt.tiago.nightfleet.core.puzzle

/**
 * One hint: the cell to mark, the value it must have, the technique that deduces it (null when the
 * player's mark there is wrong) and a stable [reasonKey] from [PuzzleReason].
 */
data class HintStep(
    val cell: Int,
    val value: PlayerCell,
    val technique: Technique?,
    val reasonKey: String,
)

/**
 * The next thing to tell the player. If one of their marks contradicts the solution, the hint points
 * to the first wrong cell with its correct value. Otherwise it is the first cell the logical techniques
 * deduce from the current grid, or null when there is nothing to deduce (solved, or not logic-solvable).
 */
fun nextHint(definition: PuzzleDefinition, grid: PlayerGrid): HintStep? {
    val solution = logicSolution(definition) ?: return null
    for (cell in 0 until definition.cellCount) {
        val mark = grid[cell]
        if (mark != PlayerCell.EMPTY && mark != solution[cell]) {
            return HintStep(cell, solution[cell], null, PuzzleReason.WRONG_MARK)
        }
    }
    val board = LogicBoard.fromGrid(definition, grid)
    val start = board.log.size
    saturate(board, Technique.T4, stopAfterFirst = true)
    if (board.conflict || board.log.size == start) return null
    val first = board.log[start]
    return HintStep(first.cell, first.value, first.technique, first.reasonKey)
}
