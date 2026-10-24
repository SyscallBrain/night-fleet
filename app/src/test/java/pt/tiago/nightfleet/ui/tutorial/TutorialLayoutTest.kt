package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialLayoutTest {

    private val safe = Rect(left = 16f, top = 16f, right = 374f, bottom = 828f)
    private val gap = 16f

    @Test
    fun spotlightGrowsTheAnchorOnEverySide() {
        val spot = TutorialLayout.spotlight(Rect(100f, 200f, 150f, 240f), 8f)
        assertEquals(Rect(92f, 192f, 158f, 248f), spot)
    }

    @Test
    fun bubbleGoesAboveAnAnchorWhenThereIsRoom() {
        val anchor = Rect(100f, 500f, 150f, 540f)
        val placement = TutorialLayout.placeBubble(safe, anchor, Size(200f, 100f), gap)
        assertTrue(placement.above)
        assertEquals(500f - gap - 100f, placement.topLeft.y)
    }

    @Test
    fun bubbleGoesBelowAnAnchorNearTheTopEdge() {
        val anchor = Rect(100f, 30f, 150f, 70f)
        val placement = TutorialLayout.placeBubble(safe, anchor, Size(200f, 100f), gap)
        assertFalse(placement.above)
        assertEquals(70f + gap, placement.topLeft.y)
    }

    @Test
    fun bubbleGoesToTheSideWithMoreRoomWhenNeitherFits() {
        val tall = Rect(16f, 300f, 374f, 500f)
        // A 700 px bubble fits neither above nor below; below has more room, and it is pushed up to stay inside.
        val placement = TutorialLayout.placeBubble(safe, tall, Size(200f, 700f), gap)
        assertFalse(placement.above)
        assertEquals(safe.bottom - 700f, placement.topLeft.y)
    }

    @Test
    fun bubbleStaysInsideTheSafeAreaHorizontally() {
        val leftEdge = Rect(0f, 500f, 20f, 540f)
        val placement = TutorialLayout.placeBubble(safe, leftEdge, Size(200f, 100f), gap)
        assertEquals(safe.left, placement.topLeft.x)

        val rightEdge = Rect(350f, 500f, 380f, 540f)
        val right = TutorialLayout.placeBubble(safe, rightEdge, Size(200f, 100f), gap)
        assertEquals(safe.right - 200f, right.topLeft.x)
    }

    @Test
    fun bubbleWithoutAnAnchorIsCentred() {
        val placement = TutorialLayout.placeBubble(safe, null, Size(200f, 100f), gap)
        assertFalse(placement.above)
        assertEquals(safe.center.x - 100f, placement.topLeft.x)
        assertEquals(safe.center.y - 50f, placement.topLeft.y)
    }

    @Test
    fun bubbleWiderThanTheSafeAreaIsClampedToIt() {
        val placement = TutorialLayout.placeBubble(safe, null, Size(900f, 100f), gap)
        assertEquals(safe.left, placement.topLeft.x)
    }

    @Test
    fun tapHandStaysOnTheAnchorCentre() {
        val anchor = Rect(100f, 200f, 200f, 260f)
        assertEquals(Offset(150f, 230f), TutorialLayout.handPosition(anchor, GestureKind.TAP, 0.7f))
    }

    @Test
    fun dragHandSwingsBetweenTwentyAndEightyPercentOfTheWidth() {
        val anchor = Rect(100f, 200f, 200f, 260f)
        assertEquals(120f, TutorialLayout.handPosition(anchor, GestureKind.DRAG, 0f)!!.x, 0.01f)
        assertEquals(180f, TutorialLayout.handPosition(anchor, GestureKind.DRAG, 0.5f)!!.x, 0.01f)
        assertEquals(120f, TutorialLayout.handPosition(anchor, GestureKind.DRAG, 1f)!!.x, 0.01f)
        assertEquals(230f, TutorialLayout.handPosition(anchor, GestureKind.DRAG, 0.5f)!!.y, 0.01f)
    }

    @Test
    fun stepsWithoutAGestureHaveNoHand() {
        assertNull(TutorialLayout.handPosition(Rect(0f, 0f, 10f, 10f), GestureKind.NONE, 0.3f))
    }
}
