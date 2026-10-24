package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** Where the bubble goes: its top-left corner, and whether it sits above the anchor. */
data class BubblePlacement(val topLeft: Offset, val above: Boolean)

/**
 * The geometry of the coach mark, in pixels, with no Compose state: the JVM tests check it directly.
 * All rectangles are in the overlay's coordinates; the safe area already excludes the system bars.
 */
object TutorialLayout {

    /** The spotlight: the anchor grown by [paddingPx] on each side. */
    fun spotlight(anchor: Rect, paddingPx: Float): Rect = anchor.inflate(paddingPx)

    /**
     * Places a bubble of [bubble] size next to [anchor], inside [safeArea]. It goes above the anchor when that
     * fits, or when there is more room above than below; otherwise below. Without an anchor it is centred.
     * It never leaves the safe area, even when it has to overlap the anchor to stay there.
     */
    fun placeBubble(safeArea: Rect, anchor: Rect?, bubble: Size, gapPx: Float): BubblePlacement {
        val width = min(bubble.width, safeArea.width)
        val height = bubble.height
        val maxLeft = safeArea.right - width
        val maxTop = max(safeArea.top, safeArea.bottom - height)
        if (anchor == null) {
            val left = (safeArea.center.x - width / 2f).coerceIn(safeArea.left, maxLeft)
            val top = (safeArea.center.y - height / 2f).coerceIn(safeArea.top, maxTop)
            return BubblePlacement(Offset(left, top), above = false)
        }
        val spaceAbove = anchor.top - gapPx - safeArea.top
        val spaceBelow = safeArea.bottom - (anchor.bottom + gapPx)
        val above = spaceAbove >= height || (spaceBelow < height && spaceAbove >= spaceBelow)
        val rawTop = if (above) anchor.top - gapPx - height else anchor.bottom + gapPx
        val top = rawTop.coerceIn(safeArea.top, maxTop)
        val left = (anchor.center.x - width / 2f).coerceIn(safeArea.left, maxLeft)
        return BubblePlacement(Offset(left, top), above)
    }

    /**
     * Where the hand is at [progress] (0 to 1, one loop). A tap stays in the middle of the anchor; a drag goes
     * from 20 % to 80 % of its width and back. Null when the step has no hand.
     */
    fun handPosition(anchor: Rect, gesture: GestureKind, progress: Float): Offset? {
        val p = progress.coerceIn(0f, 1f)
        return when (gesture) {
            GestureKind.NONE -> null
            GestureKind.TAP -> anchor.center
            GestureKind.DRAG -> {
                val triangle = if (p < 0.5f) p * 2f else (1f - p) * 2f
                val x = anchor.left + anchor.width * (0.2f + 0.6f * triangle)
                Offset(x, anchor.center.y)
            }
        }
    }
}
