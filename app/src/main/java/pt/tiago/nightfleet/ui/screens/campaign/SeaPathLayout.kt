package pt.tiago.nightfleet.ui.screens.campaign

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Geometry of the sea map path: the nodes of a sea on a winding path between decorative islands.
 * Pure and deterministic: the same sea number always gives the same chart. Every coordinate is an integer in dp of
 * the logical chart (the chart is drawn at its own width, centred), so nothing lands on a fractional pixel.
 */
data class PathNode(val mission: Int, val x: Int, val y: Int)

data class PathIsland(val x: Int, val y: Int, val radius: Int)

/** An axis-aligned rectangle in whole dp of the chart: [left, right) by [top, bottom). */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class SeaChart(val width: Int, val height: Int, val nodes: List<PathNode>, val islands: List<PathIsland>)

object SeaPathLayout {
    /** Logical width of the chart, in dp. */
    const val WIDTH = 360

    /** Distance between two consecutive nodes on the vertical axis, in dp. The visible node is 44 dp. */
    const val ROW_SPACING = 72

    /** Half of the 48 dp touch target: the centre of a node is at least this far from any other node's edge. */
    const val NODE_RADIUS = 24

    /** Space above the first node and below the last one. */
    const val MARGIN = 48

    /** Largest horizontal step of the path between two consecutive nodes. */
    private const val MAX_STEP = 36

    /** Distance kept between an island and any node or path segment, beyond the island radius. */
    private const val CLEARANCE = 14

    private const val ISLAND_TRIES = 60

    /** The chart of [sea] for the missions [missions] (in campaign order, the boss last). */
    fun build(sea: Int, missions: List<Int>, width: Int = WIDTH): SeaChart {
        val rng = Mix(sea.toLong() * GOLDEN + SALT)
        val half = width / 2
        val reach = half - 56
        var offset = 0
        val nodes = missions.mapIndexed { index, mission ->
            if (index > 0) {
                offset = (offset + rng.int(-MAX_STEP, MAX_STEP)).coerceIn(-reach, reach)
            }
            PathNode(mission, half + offset, MARGIN + index * ROW_SPACING)
        }
        val height = MARGIN * 2 + max(0, missions.size - 1) * ROW_SPACING
        val islands = ArrayList<PathIsland>()
        for (k in 0 until ISLAND_COUNT) {
            for (attempt in 0 until ISLAND_TRIES) {
                val island = PathIsland(
                    x = rng.int(0, width),
                    y = rng.int(0, height),
                    radius = rng.int(ISLAND_MIN_RADIUS, ISLAND_MAX_RADIUS),
                )
                if (fits(island, nodes, islands, width, height)) {
                    islands += island
                    break
                }
            }
        }
        return SeaChart(width, height, nodes, islands)
    }

    /** True when [island] is inside the chart, clear of every node and every path segment, and of the other islands. */
    internal fun fits(island: PathIsland, nodes: List<PathNode>, others: List<PathIsland>, width: Int, height: Int): Boolean {
        if (island.x - island.radius < 0 || island.x + island.radius > width) return false
        if (island.y - island.radius < 0 || island.y + island.radius > height) return false
        for (node in nodes) {
            if (distance(island.x, island.y, node.x, node.y) < island.radius + NODE_RADIUS + CLEARANCE) return false
        }
        for (i in 0 until nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (segmentDistance(island.x, island.y, a.x, a.y, b.x, b.y) < island.radius + CLEARANCE) return false
        }
        for (other in others) {
            if (distance(island.x, island.y, other.x, other.y) < island.radius + other.radius + CLEARANCE) return false
        }
        return true
    }

    private fun distance(ax: Int, ay: Int, bx: Int, by: Int): Double {
        val dx = (ax - bx).toDouble()
        val dy = (ay - by).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance from point (px, py) to the segment (ax, ay)-(bx, by). */
    private fun segmentDistance(px: Int, py: Int, ax: Int, ay: Int, bx: Int, by: Int): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return distance(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /** Visible radius of a node (the circle is 44 dp). */
    const val VISIBLE_RADIUS = 22

    /** Width and height of the stars pill, in dp. The pill holds three star glyphs. */
    const val STARS_WIDTH = 48
    const val STARS_HEIGHT = 20

    /** Gap between the node circle and the stars pill, in dp. */
    const val STARS_GAP = 4

    /** Width of the path stroke, in dp. */
    const val PATH_STROKE = 3

    /**
     * The stars pill of [node]: beside the node, on the side of the chart centre, so the path that leaves the node
     * up or down never crosses it. Its vertical centre is the node centre.
     */
    fun starsRect(node: PathNode, width: Int = WIDTH): Rect {
        val edge = VISIBLE_RADIUS + STARS_GAP
        val left = if (node.x >= width / 2) node.x - edge - STARS_WIDTH else node.x + edge
        val top = node.y - STARS_HEIGHT / 2
        return Rect(left, top, left + STARS_WIDTH, top + STARS_HEIGHT)
    }

    private const val ISLAND_COUNT = 9
    private const val ISLAND_MIN_RADIUS = 22
    private const val ISLAND_MAX_RADIUS = 40
    private const val SALT = 0x4E16L
    private const val GOLDEN = -7046029254386353131L

    /** A small SplitMix64 stream: the chart must not depend on the platform random source. */
    private class Mix(seed: Long) {
        private var state = seed

        fun next(): Long {
            state += GOLDEN
            var z = state
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            return z xor (z ushr 31)
        }

        /** An integer in [low, high], both included. */
        fun int(low: Int, high: Int): Int = low + Math.floorMod(next(), (high - low + 1).toLong()).toInt()
    }
}
