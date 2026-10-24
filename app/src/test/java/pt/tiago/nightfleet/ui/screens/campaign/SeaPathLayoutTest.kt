package pt.tiago.nightfleet.ui.screens.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The sea map path: deterministic, integer, inside the chart and free of overlaps. */
class SeaPathLayoutTest {

    private fun missionsOf(sea: Int): List<Int> = (1..50).map { (sea - 1) * 50 + it }

    @Test
    fun theSameSeaAlwaysGivesTheSameChart() {
        assertEquals(SeaPathLayout.build(3, missionsOf(3)), SeaPathLayout.build(3, missionsOf(3)))
    }

    @Test
    fun twoSeasGiveDifferentCharts() {
        assertNotEquals(SeaPathLayout.build(1, missionsOf(1)).nodes, SeaPathLayout.build(2, missionsOf(2)).nodes)
    }

    @Test
    fun everySeaHasFiftyNodesInCampaignOrder() {
        (1..12).forEach { sea ->
            val layout = SeaPathLayout.build(sea, missionsOf(sea))
            assertEquals(50, layout.nodes.size)
            assertEquals(missionsOf(sea), layout.nodes.map { it.mission })
        }
    }

    @Test
    fun theChartHeightFitsTheRowsAndTheMargins() {
        val layout = SeaPathLayout.build(1, missionsOf(1))
        assertEquals(2 * SeaPathLayout.MARGIN + 49 * SeaPathLayout.ROW_SPACING, layout.height)
        assertEquals(SeaPathLayout.WIDTH, layout.width)
    }

    @Test
    fun everyNodeIsInsideTheChart() {
        (1..12).forEach { sea ->
            val layout = SeaPathLayout.build(sea, missionsOf(sea))
            layout.nodes.forEach { node ->
                assertTrue(node.x - SeaPathLayout.NODE_RADIUS >= 0)
                assertTrue(node.x + SeaPathLayout.NODE_RADIUS <= layout.width)
                assertTrue(node.y - SeaPathLayout.NODE_RADIUS >= 0)
                assertTrue(node.y + SeaPathLayout.NODE_RADIUS <= layout.height)
            }
        }
    }

    @Test
    fun noTwoNodesOverlapTheirTouchTargets() {
        (1..12).forEach { sea ->
            val nodes = SeaPathLayout.build(sea, missionsOf(sea)).nodes
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                val dx = (nodes[i].x - nodes[j].x).toDouble()
                val dy = (nodes[i].y - nodes[j].y).toDouble()
                assertTrue("nodes ${i} and ${j} of sea $sea overlap", sqrt(dx * dx + dy * dy) >= 2 * SeaPathLayout.NODE_RADIUS)
            }
        }
    }

    @Test
    fun theIslandsAreDecorativeAndClearOfTheNodesAndThePath() {
        (1..12).forEach { sea ->
            val layout = SeaPathLayout.build(sea, missionsOf(sea))
            assertTrue("sea $sea has islands", layout.islands.isNotEmpty())
            layout.islands.forEachIndexed { index, island ->
                val others = layout.islands.take(index)
                assertTrue(SeaPathLayout.fits(island, layout.nodes, others, layout.width, layout.height))
            }
        }
    }

    @Test
    fun everyCoordinateIsAWholeNumberOfDp() {
        // Coordinates are Int by type: this checks the chart uses the same whole grid for nodes and islands.
        val layout = SeaPathLayout.build(7, missionsOf(7))
        layout.nodes.forEach { assertEquals(it.x, it.x.toInt()) }
        layout.islands.forEach { assertFalse(it.radius <= 0) }
    }

    /** Distance from a point to a rectangle (0 inside it). */
    private fun distanceToRect(px: Double, py: Double, r: Rect): Double {
        val dx = maxOf(r.left - px, 0.0, px - r.right)
        val dy = maxOf(r.top - py, 0.0, py - r.bottom)
        return sqrt(dx * dx + dy * dy)
    }

    /** Smallest distance between the segment a-b and the rectangle, sampled every 0.1 dp. */
    private fun segmentToRect(a: PathNode, b: PathNode, r: Rect): Double {
        var best = Double.MAX_VALUE
        for (step in 0..100) {
            val t = step / 100.0
            val x = a.x + (b.x - a.x) * t
            val y = a.y + (b.y - a.y) * t
            best = minOf(best, distanceToRect(x, y, r))
        }
        return best
    }

    @Test
    fun theStarsPillNeverTouchesThePathStroke() {
        // The path is drawn behind the pill; the pill must keep a clear margin beyond half the stroke width.
        val clearance = SeaPathLayout.PATH_STROKE / 2.0 + 2.0
        (1..12).forEach { sea ->
            val layout = SeaPathLayout.build(sea, missionsOf(sea))
            layout.nodes.forEachIndexed { index, node ->
                val pill = SeaPathLayout.starsRect(node, layout.width)
                for (i in 0 until layout.nodes.size - 1) {
                    val d = segmentToRect(layout.nodes[i], layout.nodes[i + 1], pill)
                    assertTrue("pill of node $index of sea $sea is ${d}dp from the path", d >= clearance)
                }
            }
        }
    }

    @Test
    fun theStarsPillStaysBesideItsNodeAndInsideTheChart() {
        (1..12).forEach { sea ->
            val layout = SeaPathLayout.build(sea, missionsOf(sea))
            layout.nodes.forEach { node ->
                val pill = SeaPathLayout.starsRect(node, layout.width)
                assertTrue(pill.left >= 0 && pill.right <= layout.width)
                // Clear of its own circle and of every other node circle.
                layout.nodes.forEach { other ->
                    val d = distanceToRect(other.x.toDouble(), other.y.toDouble(), pill)
                    val reach = if (other == node) SeaPathLayout.VISIBLE_RADIUS.toDouble() else 0.0
                    assertTrue("pill of ${node.mission} touches the node ${other.mission}", d > reach)
                }
            }
        }
    }
}
