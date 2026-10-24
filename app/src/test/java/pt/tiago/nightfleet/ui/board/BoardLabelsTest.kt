package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** TalkBack labels of the sea squares: coordinates, state keys and the strings they point to. */
class BoardLabelsTest {

    @Test
    fun `coordinates count columns by letter and rows from one`() {
        assertEquals("A1", BoardLabels.coordinate(0, 0))
        assertEquals("D4", BoardLabels.coordinate(3, 3))
        assertEquals("L12", BoardLabels.coordinate(11, 11))
    }

    @Test
    fun `row-major indices map to the right square`() {
        // 10 columns: index 0 is A1, index 10 is A2, index 23 is D3.
        assertEquals("A1", BoardLabels.coordinateOf(0, 10))
        assertEquals("A2", BoardLabels.coordinateOf(10, 10))
        assertEquals("D3", BoardLabels.coordinateOf(23, 10))
        assertEquals("L12", BoardLabels.coordinateOf(143, 12))
    }

    @Test
    fun `bad coordinates are refused`() {
        expectRefused { BoardLabels.coordinate(-1, 0) }
        expectRefused { BoardLabels.coordinate(26, 0) }
        expectRefused { BoardLabels.coordinate(0, -1) }
        expectRefused { BoardLabels.coordinateOf(-1, 10) }
    }

    @Test
    fun `every visual state has a key on both seas`() {
        for (visual in CellVisual.entries) {
            for (enemy in listOf(true, false)) {
                val key = BoardLabels.stateKey(visual, enemy)
                assertTrue("$visual/$enemy gives $key", key in BoardLabels.stateKeys)
            }
        }
    }

    @Test
    fun `an unknown square reads differently on the enemy sea and on the own sea`() {
        assertEquals("unknown", BoardLabels.stateKey(CellVisual.UNKNOWN, enemy = true))
        assertEquals("empty_sea", BoardLabels.stateKey(CellVisual.UNKNOWN, enemy = false))
        assertEquals(BoardLabels.stateKey(CellVisual.WATER, true), BoardLabels.stateKey(CellVisual.AUTO_WATER, true))
    }

    @Test
    fun `every state key has a string in English and in European Portuguese`() {
        val english = keysOf("src/main/res/values/strings.xml")
        val portuguese = keysOf("src/main/res/values-pt/strings.xml")
        for (key in BoardLabels.stateKeys) {
            val name = "board_state_$key"
            assertTrue("missing $name in values", name in english)
            assertTrue("missing $name in values-pt", name in portuguese)
        }
        assertTrue("board_cell_label missing", "board_cell_label" in english && "board_cell_label" in portuguese)
    }

    private fun keysOf(path: String): Set<String> {
        val file = File(path)
        assertTrue("missing ${file.absolutePath}", file.isFile)
        return Regex("<string name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()
    }

    private fun expectRefused(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("accepted a bad coordinate")
    }
}
