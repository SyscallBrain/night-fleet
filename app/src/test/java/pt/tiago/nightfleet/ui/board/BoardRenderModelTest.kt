package pt.tiago.nightfleet.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The render model rejects inconsistent boards before they reach the Canvas. */
class BoardRenderModelTest {

    private fun cells(w: Int, h: Int) = List(w * h) { CellVisual.UNKNOWN }

    @Test
    fun cellCountMustBeWidthTimesHeight() {
        assertNotNull(BoardRenderModel(10, 10, cells(10, 10)))
        for (bad in listOf(cells(10, 9), cells(9, 10), cells(11, 10))) {
            try {
                BoardRenderModel(10, 10, bad)
                throw AssertionError("expected IllegalArgumentException for ${bad.size} cells")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun rectangularBoardsAreAllowed() {
        val model = BoardRenderModel(12, 8, cells(12, 8))
        assertEquals(12 * 8, model.cells.size)
        assertEquals(12 + 3, model.indexOf(3, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBoardIsRejected() {
        BoardRenderModel(0, 0, emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun lastShotOutsideTheBoardIsRejected() {
        BoardRenderModel(8, 8, cells(8, 8), lastShot = 64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shipOutsideTheBoardIsRejected() {
        BoardRenderModel(8, 8, cells(8, 8), ships = listOf(ShipOutline(listOf(63, 64), sunk = true)))
    }

    @Test
    fun optionalMarksDefaultToNothing() {
        val model = BoardRenderModel(8, 8, cells(8, 8))
        assertNull(model.lastShot)
        assertNull(model.selected)
        assertEquals(emptySet<Int>(), model.highlight)
    }
}
