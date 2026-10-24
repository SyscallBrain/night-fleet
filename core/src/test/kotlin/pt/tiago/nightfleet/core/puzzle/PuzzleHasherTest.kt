package pt.tiago.nightfleet.core.puzzle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.PuzzleFixtures.demoGiven

class PuzzleHasherTest {

    @Test
    fun hexIsSixteenLowercaseDigits() {
        val hex = PuzzleHasher.hex(demoGiven)
        assertEquals(16, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun hashIgnoresTheId() {
        assertEquals(PuzzleHasher.hash(demoGiven), PuzzleHasher.hash(demoGiven.copy(id = "renamed")))
    }

    @Test
    fun hashChangesWithAnyClue() {
        val moved = demoGiven.copy(givens = demoGiven.givens + (1 to Given.WATER))
        val retyped = demoGiven.copy(givens = demoGiven.givens + (0 to Given.END_N))
        assertNotEquals(PuzzleHasher.hash(demoGiven), PuzzleHasher.hash(moved))
        assertNotEquals(PuzzleHasher.hash(demoGiven), PuzzleHasher.hash(retyped))
    }

    @Test
    fun hashChangesWithIslandsAndFleet() {
        assertNotEquals(PuzzleHasher.hash(demoGiven), PuzzleHasher.hash(demoGiven.copy(islands = listOf(35))))
    }

    @Test
    fun hashIsStableAcrossCalls() {
        assertEquals(PuzzleHasher.hex(demoGiven), PuzzleHasher.hex(demoGiven.copy()))
    }

    @Test
    fun canonicalTextListsCluesInCellOrder() {
        val text = PuzzleHasher.canonical(demoGiven)
        assertTrue(text.contains("givens=0:SHIP,8:WATER,23:SHIP,28:WATER,31:SHIP"))
    }
}
