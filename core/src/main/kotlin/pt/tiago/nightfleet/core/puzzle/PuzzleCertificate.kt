package pt.tiago.nightfleet.core.puzzle

import kotlinx.serialization.Serializable

/** Version of the puzzle generator and grader. Changing observable behaviour bumps it and regenerates certificates. */
const val PUZZLE_VERSION: Int = 1

/** Proof that a puzzle has exactly one solution and the grade it was accepted with. */
@Serializable
data class PuzzleCertificate(
    val puzzleId: String,
    val puzzleHash: String,
    val solution: PlayerGrid,
    val solutionCount: Int = 1,
    val difficulty: PuzzleGrade,
    val grade: Grade,
    val puzzleVersion: Int = PUZZLE_VERSION,
)

/** Issues certificates. Returns null when the puzzle is not provably unique or not solvable by logic. */
object PuzzleCertifier {
    fun certify(definition: PuzzleDefinition): PuzzleCertificate? {
        val result = gradePuzzle(definition) as? GradeResult.Graded ?: return null
        val solution = logicSolution(definition) ?: return null
        if (!ExactSolver.countSolutions(definition).isUnique) return null
        return PuzzleCertificate(
            puzzleId = definition.id,
            puzzleHash = PuzzleHasher.hex(definition),
            solution = solution,
            difficulty = result.grade.difficulty(),
            grade = result.grade,
        )
    }
}

/**
 * Stable hash of a puzzle's content (size, islands, fleet, counts and clues). The id is not part of
 * the hash, so renaming a puzzle keeps its identity.
 */
object PuzzleHasher {
    fun canonical(definition: PuzzleDefinition): String {
        val givens = definition.givens.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.name}" }
        return "${definition.width}x${definition.height}" +
            ";islands=${definition.islands.joinToString(",")}" +
            ";fleet=${definition.fleet.joinToString(",")}" +
            ";rows=${definition.rowCounts.joinToString(",")}" +
            ";cols=${definition.colCounts.joinToString(",")}" +
            ";givens=$givens"
    }

    /** 64-bit FNV-1a over the canonical text. */
    fun hash(definition: PuzzleDefinition): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        for (byte in canonical(definition).toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }

    fun hex(definition: PuzzleDefinition): String = java.lang.Long.toHexString(hash(definition)).padStart(16, '0')
}
