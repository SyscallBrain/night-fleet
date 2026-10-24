package pt.tiago.nightfleet.core.grid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GridTest {

    private val ten = Board.water(10, 10)

    @Test
    fun `label of the first square is A1`() {
        assertEquals("A1", Cell(0, 0).label())
    }

    @Test
    fun `label uses letter for column and number for row`() {
        assertEquals("B7", Cell(6, 1).label())
    }

    @Test
    fun `label of the last square of a 12 by 12 grid is L12`() {
        assertEquals("L12", Cell(11, 11).label())
    }

    @Test
    fun `labels round trip through parse for every square of a 12 by 12 grid`() {
        for (row in 0 until 12) for (col in 0 until 12) {
            val cell = Cell(row, col)
            assertEquals(cell, Cell.parse(cell.label()))
        }
    }

    @Test
    fun `parse is case insensitive`() {
        assertEquals(Cell(6, 1), Cell.parse("b7"))
    }

    @Test
    fun `parse ignores surrounding spaces`() {
        assertEquals(Cell(0, 0), Cell.parse("  A1 "))
    }

    @Test
    fun `parse rejects row zero`() {
        assertNull(Cell.parse("A0"))
    }

    @Test
    fun `parse rejects number before letter`() {
        assertNull(Cell.parse("7B"))
    }

    @Test
    fun `parse rejects empty text`() {
        assertNull(Cell.parse(""))
    }

    @Test
    fun `label rejects negative coordinates`() {
        try {
            Cell(-1, 0).label()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `index and cell are inverse`() {
        for (i in 0 until ten.size) assertEquals(i, ten.index(ten.cell(i)))
    }

    @Test
    fun `index is row major`() {
        assertEquals(23, ten.index(Cell(2, 3)))
    }

    @Test
    fun `inBounds accepts the corners and rejects one past the edge`() {
        assertTrue(ten.inBounds(0, 0))
        assertTrue(ten.inBounds(9, 9))
        assertFalse(ten.inBounds(10, 0))
        assertFalse(ten.inBounds(0, -1))
    }

    @Test
    fun `non-square board uses width and height separately`() {
        val wide = Board.water(12, 8)
        assertTrue(wide.inBounds(7, 11))
        assertFalse(wide.inBounds(11, 7))
        assertEquals(96, wide.size)
    }

    @Test
    fun `index rejects a cell outside the board`() {
        try {
            ten.index(Cell(10, 0))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `corner has two orthogonal neighbours`() {
        assertArrayEquals(intArrayOf(1, 10), ten.neighbours4(0))
    }

    @Test
    fun `edge square has three orthogonal neighbours`() {
        assertEquals(3, ten.neighbours4(5).size)
    }

    @Test
    fun `centre square has four orthogonal neighbours`() {
        assertEquals(4, ten.neighbours4(55).size)
    }

    @Test
    fun `corner has three diagonal-inclusive neighbours`() {
        assertArrayEquals(intArrayOf(1, 10, 11), ten.neighbours8(0))
    }

    @Test
    fun `edge square has five eight-neighbours`() {
        assertEquals(5, ten.neighbours8(5).size)
    }

    @Test
    fun `centre square has eight neighbours`() {
        assertEquals(8, ten.neighbours8(55).size)
    }

    @Test
    fun `eight-neighbours of a centre square are exactly the surrounding squares`() {
        assertArrayEquals(intArrayOf(44, 45, 46, 54, 56, 64, 65, 66), ten.neighbours8(55))
    }

    @Test
    fun `neighbour lists are sorted ascending for every square`() {
        for (i in 0 until ten.size) {
            val n4 = ten.neighbours4(i)
            val n8 = ten.neighbours8(i)
            assertEquals(n4.sorted(), n4.toList())
            assertEquals(n8.sorted(), n8.toList())
        }
    }

    @Test
    fun `neighbourhoods are symmetric`() {
        for (i in 0 until ten.size) for (j in ten.neighbours8(i)) {
            assertTrue(ten.neighbours8(j).contains(i))
        }
    }

    @Test
    fun `neighbours never include the square itself`() {
        for (i in 0 until ten.size) assertFalse(ten.neighbours8(i).contains(i))
    }

    @Test
    fun `neighbours of a one by one board are empty`() {
        val tiny = Board.water(1, 1)
        assertEquals(0, tiny.neighbours8(0).size)
        assertEquals(0, tiny.neighbours4(0).size)
    }

    @Test
    fun `board rejects terrain of the wrong size`() {
        try {
            Board(8, 8, List(63) { Terrain.WATER })
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `board rejects a mine on land`() {
        val terrain = List(64) { if (it == 3) Terrain.ISLAND else Terrain.WATER }
        try {
            Board(8, 8, terrain, mines = listOf(3))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `board rejects unsorted mines`() {
        try {
            Board(8, 8, List(64) { Terrain.WATER }, mines = listOf(9, 3))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `board rejects a size outside 1 to 64`() {
        try {
            Board.water(65, 1)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `isMine uses the sorted mine set`() {
        val board = Board(8, 8, List(64) { Terrain.WATER }, mines = listOf(5, 20, 40))
        assertTrue(board.isMine(20))
        assertFalse(board.isMine(21))
    }

    @Test
    fun `terrainAt returns the terrain of the index`() {
        val board = Board(8, 8, List(64) { if (it == 10) Terrain.REEF else Terrain.WATER })
        assertEquals(Terrain.REEF, board.terrainAt(10))
        assertEquals(Terrain.WATER, board.terrainAt(11))
    }

    @Test
    fun `equal boards are equal objects`() {
        assertEquals(Board.water(8, 8), Board.water(8, 8))
    }

    @Test
    fun `cell data class equality is structural`() {
        assertEquals(Cell(2, 3), Cell(2, 3))
        assertFalse(Cell(2, 3) == Cell(3, 2))
    }

    @Test
    fun `terrain enum declares water island and reef`() {
        assertEquals(listOf(Terrain.WATER, Terrain.ISLAND, Terrain.REEF), Terrain.entries.toList())
    }

    @Test
    fun `board on the 12 by 12 maximum keeps neighbour counts right`() {
        val big = Board.water(12, 12)
        assertEquals(8, big.neighbours8(5 * 12 + 5).size)
        assertEquals(3, big.neighbours8(11).size)
    }
}
