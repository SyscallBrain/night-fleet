package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import java.util.concurrent.ConcurrentHashMap

/** Every on-board placement of one ship class on one grid size, with its squares as row-major indices. */
internal class ShapeSet(val placements: List<Placement>, val squares: Array<IntArray>)

/** Cached geometry: pure, so caching cannot change any decision. */
internal object Geometry {
    private val cache = ConcurrentHashMap<Long, ShapeSet>()

    fun of(width: Int, height: Int, shipClass: ShipClass): ShapeSet {
        val key = ((width.toLong() * 64 + height) shl 8) or shipClass.ordinal.toLong()
        return cache.getOrPut(key) { build(width, height, shipClass) }
    }

    private fun build(width: Int, height: Int, shipClass: ShipClass): ShapeSet {
        val placements = ArrayList<Placement>()
        val squares = ArrayList<IntArray>()
        for (rotation in shipClass.rotations.indices) for (r in 0 until height) for (c in 0 until width) {
            val placement = Placement(shipClass, Cell(r, c), rotation)
            val cells = placement.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width }) continue
            placements += placement
            squares += IntArray(cells.size) { cells[it].row * width + cells[it].col }
        }
        return ShapeSet(placements, squares.toTypedArray())
    }
}
