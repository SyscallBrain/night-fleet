package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.random.SplitMix64

class PuzzleGeneratorTest {

    private fun islandsFor(size: Int): Int = when (size) {
        6 -> 1
        8 -> 2
        else -> 3
    }

    @Test
    fun fleetsMatchTheContentSpecification() {
        assertEquals(listOf(3, 2, 2, 1, 1, 1), PuzzleGenerator.fleetFor(6))
        assertEquals(listOf(4, 3, 2, 2, 1, 1, 1), PuzzleGenerator.fleetFor(8))
        assertEquals(listOf(4, 3, 3, 2, 2, 2, 1, 1, 1, 1), PuzzleGenerator.fleetFor(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSizeIsRejected() {
        PuzzleGenerator.fleetFor(12)
    }

    @Test
    fun sameSeedGivesTheSamePuzzle() {
        val a = PuzzleGenerator.generate(8, PuzzleGrade.MEDIUM, 2, SplitMix64(0xC0FFEEL))
        val b = PuzzleGenerator.generate(8, PuzzleGrade.MEDIUM, 2, SplitMix64(0xC0FFEEL))
        assertNotNull(a)
        assertEquals(PuzzleCodec.encode(a!!), PuzzleCodec.encode(b!!))
        assertEquals(PuzzleHasher.hex(a), PuzzleHasher.hex(b))
    }

    @Test
    fun differentSeedsGiveDifferentPuzzles() {
        val a = PuzzleGenerator.generate(6, PuzzleGrade.EASY, 1, SplitMix64(1L))
        val b = PuzzleGenerator.generate(6, PuzzleGrade.EASY, 1, SplitMix64(2L))
        assertNotNull(a)
        assertNotNull(b)
        assertNotEquals(PuzzleCodec.encode(a!!), PuzzleCodec.encode(b!!))
    }

    @Test
    fun generatedPuzzleHasTheRequestedIslandsAndFleet() {
        for (size in listOf(6, 8, 10)) {
            val def = PuzzleGenerator.generate(size, PuzzleGrade.HARD, islandsFor(size), SplitMix64(size.toLong()))
            assertNotNull("size $size", def)
            assertEquals(islandsFor(size), def!!.islands.size)
            assertEquals(PuzzleGenerator.fleetFor(size), def.fleet)
            assertTrue(def.givens.keys.none { it in def.islands })
        }
    }

    @Test
    fun generatedPuzzleIsCertifiedWithOneSolution() {
        val def = PuzzleGenerator.generate(6, PuzzleGrade.MEDIUM, 1, SplitMix64(5L))
        assertNotNull(def)
        val certificate = PuzzleCertifier.certify(def!!)
        assertNotNull(certificate)
        assertEquals(1, certificate!!.solutionCount)
        assertEquals(PuzzleHasher.hex(def), certificate.puzzleHash)
        assertEquals(PUZZLE_VERSION, certificate.puzzleVersion)
        assertEquals(ExactSolver.solve(def), certificate.solution)
        assertTrue(PuzzleValidator.isSolved(def, certificate.solution))
    }

    @Test
    fun puzzleWithSeveralSolutionsIsNotCertified() {
        assertNull(PuzzleCertifier.certify(PuzzleFixtures.demo))
    }

    /**
     * Batch: 20 puzzles per size and grade. Every puzzle must have exactly one solution, be graded by
     * logic with the requested difficulty, and agree with the exact solver. Timing is printed, not asserted.
     */
    @Test
    fun batchOfPuzzlesIsUniqueAndGradedAsRequested() {
        for (size in listOf(6, 8, 10)) {
            var totalNanos = 0L
            var generated = 0
            for (grade in PuzzleGrade.values()) {
                val rng = SplitMix64(0x5EED0000L + size * 10 + grade.ordinal)
                var gradeNanos = 0L
                val techniques = java.util.TreeMap<String, Int>()
                for (index in 0 until 20) {
                    val start = System.nanoTime()
                    val def = PuzzleGenerator.generate(size, grade, islandsFor(size), rng)
                    val elapsed = System.nanoTime() - start
                    gradeNanos += elapsed
                    generated++
                    assertNotNull("no $grade puzzle for size $size at index $index", def)
                    val solutions = ExactSolver.countSolutions(def!!)
                    assertTrue("not unique: $size $grade $index", solutions.isUnique)
                    val graded = gradePuzzle(def) as? GradeResult.Graded
                    assertNotNull("not logic-solvable: $size $grade $index", graded)
                    assertEquals("grade: $size $grade $index", grade, graded!!.grade.difficulty())
                    assertEquals(ExactSolver.solve(def), logicSolution(def))
                    val key = graded.grade.maxTechnique.name
                    techniques[key] = (techniques[key] ?: 0) + 1
                }
                totalNanos += gradeNanos
                println("BATCH ${size}x$size $grade avgGenerationMs=${gradeNanos / 20 / 1_000_000} techniques=$techniques")
            }
            println("BATCH ${size}x$size total puzzles=$generated avgGenerationMs=${totalNanos / 1_000_000 / generated}")
        }
    }
}
