package pt.tiago.nightfleet.core.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.grid.Cell

class ShipClassTest {

    private fun cells(vararg pairs: Pair<Int, Int>) = pairs.map { Cell(it.first, it.second) }

    /** Connected through orthogonal steps. */
    private fun isConnected(shape: List<Cell>): Boolean {
        val remaining = shape.toMutableSet()
        val queue = ArrayDeque(listOf(shape.first()))
        remaining.remove(shape.first())
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for (n in listOf(Cell(c.row - 1, c.col), Cell(c.row + 1, c.col), Cell(c.row, c.col - 1), Cell(c.row, c.col + 1))) {
                if (remaining.remove(n)) queue.add(n)
            }
        }
        return remaining.isEmpty()
    }

    @Test
    fun `there are exactly eight ship classes`() {
        assertEquals(8, ShipClass.entries.size)
    }

    @Test
    fun `carrier has five squares`() {
        assertEquals(5, ShipClass.CARRIER.size)
    }

    @Test
    fun `battleship has four squares`() {
        assertEquals(4, ShipClass.BATTLESHIP.size)
    }

    @Test
    fun `cruiser and submarine have three squares`() {
        assertEquals(3, ShipClass.CRUISER.size)
        assertEquals(3, ShipClass.SUBMARINE.size)
    }

    @Test
    fun `destroyer and freighter have two squares`() {
        assertEquals(2, ShipClass.DESTROYER.size)
        assertEquals(2, ShipClass.FREIGHTER.size)
    }

    @Test
    fun `seaplane is a T of four squares`() {
        assertEquals(4, ShipClass.SEAPLANE.size)
    }

    @Test
    fun `midget submarine is a single square`() {
        assertEquals(1, ShipClass.MIDGET_SUB.size)
    }

    @Test
    fun `line ships have exactly two orientations`() {
        listOf(ShipClass.CARRIER, ShipClass.BATTLESHIP, ShipClass.CRUISER, ShipClass.SUBMARINE, ShipClass.DESTROYER, ShipClass.FREIGHTER)
            .forEach { assertEquals(it.name, 2, it.rotationCount) }
    }

    @Test
    fun `seaplane has four distinct rotations`() {
        assertEquals(4, ShipClass.SEAPLANE.rotationCount)
        assertEquals(4, ShipClass.SEAPLANE.rotations.toSet().size)
    }

    @Test
    fun `midget submarine has a single rotation`() {
        assertEquals(1, ShipClass.MIDGET_SUB.rotationCount)
    }

    @Test
    fun `base seaplane is a T pointing down`() {
        assertEquals(cells(0 to 0, 0 to 1, 0 to 2, 1 to 1), ShipClass.SEAPLANE.rotations[0])
    }

    @Test
    fun `seaplane rotations are the four T orientations`() {
        val expected = setOf(
            cells(0 to 0, 0 to 1, 0 to 2, 1 to 1).toSet(), // bar on top, stem down
            cells(0 to 1, 1 to 0, 1 to 1, 2 to 1).toSet(), // bar on the right, stem left
            cells(0 to 1, 1 to 0, 1 to 1, 1 to 2).toSet(), // bar at the bottom, stem up
            cells(0 to 0, 1 to 0, 2 to 0, 1 to 1).toSet(), // bar on the left, stem right
        )
        assertEquals(expected, ShipClass.SEAPLANE.rotations.map { it.toSet() }.toSet())
    }

    @Test
    fun `every seaplane rotation has a square with three neighbours in the bar`() {
        for (rotation in ShipClass.SEAPLANE.rotations) {
            val centre = rotation.single { c ->
                rotation.count { o -> kotlin.math.abs(o.row - c.row) + kotlin.math.abs(o.col - c.col) == 1 } == 3
            }
            assertEquals(3, rotation.count { o -> kotlin.math.abs(o.row - centre.row) + kotlin.math.abs(o.col - centre.col) == 1 })
        }
    }

    @Test
    fun `every rotation of every class is normalised to the origin`() {
        for (shipClass in ShipClass.entries) for (rotation in shipClass.rotations) {
            assertEquals(shipClass.name, 0, rotation.minOf { it.row })
            assertEquals(shipClass.name, 0, rotation.minOf { it.col })
        }
    }

    @Test
    fun `rotations of a class are pairwise distinct`() {
        for (shipClass in ShipClass.entries) {
            assertEquals(shipClass.name, shipClass.rotations.size, shipClass.rotations.toSet().size)
        }
    }

    @Test
    fun `every rotation keeps the same number of squares`() {
        for (shipClass in ShipClass.entries) for (rotation in shipClass.rotations) {
            assertEquals(shipClass.name, shipClass.size, rotation.size)
        }
    }

    @Test
    fun `every shape is a connected polyomino`() {
        for (shipClass in ShipClass.entries) for (rotation in shipClass.rotations) {
            assertTrue(shipClass.name, isConnected(rotation))
        }
    }

    @Test
    fun `carrier vertical rotation runs down the first column`() {
        assertEquals(cells(0 to 0, 1 to 0, 2 to 0, 3 to 0, 4 to 0), ShipClass.CARRIER.rotations[1])
    }

    @Test
    fun `cellsAt shifts the shape by the origin`() {
        assertEquals(cells(3 to 4, 3 to 5), ShipClass.DESTROYER.cellsAt(0, Cell(3, 4)))
    }

    @Test
    fun `placement cells follow the rotation index`() {
        val placement = Placement(ShipClass.CRUISER, Cell(1, 2), rotationIndex = 1)
        assertEquals(cells(1 to 2, 2 to 2, 3 to 2), placement.cells())
    }

    @Test
    fun `only the submarine dives`() {
        assertEquals(listOf(ShipClass.SUBMARINE), ShipClass.entries.filter { it.submersible })
    }

    @Test
    fun `only the freighter is cargo`() {
        assertEquals(listOf(ShipClass.FREIGHTER), ShipClass.entries.filter { it.cargo })
    }

    @Test
    fun `non-diving classes are not submersible`() {
        assertFalse(ShipClass.CARRIER.submersible)
        assertFalse(ShipClass.MIDGET_SUB.submersible)
    }
}
