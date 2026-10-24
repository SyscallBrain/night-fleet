package pt.tiago.nightfleet.meta

/**
 * The compact map of a logbook entry: `boardWidth x boardHeight` characters, row-major, one per cell.
 * The writer is `ObservationMapper.finalMapCode`; this file is the reader's side of that contract:
 *
 * - `.` not shot yet (or unknown)
 * - `o` water after a shot, `-` automatic water around a sunk ship (both are misses)
 * - `X` hit on the enemy sea, `H` hit on an own ship
 * - `#` sunk ship
 * - `S` own ship afloat, `s` enemy ship afloat and revealed at the end
 * - `I` island, `R` reef
 */
enum class CellMark { UNKNOWN, WATER, HIT, SHIP, SUNK, ISLAND }

object CompactMap {
    fun markOf(glyph: Char): CellMark = when (glyph) {
        'o', '-' -> CellMark.WATER
        'X', 'H' -> CellMark.HIT
        '#' -> CellMark.SUNK
        'S', 's' -> CellMark.SHIP
        'I', 'R' -> CellMark.ISLAND
        else -> CellMark.UNKNOWN
    }

    /** Decodes a map of the given size. Returns null when the text does not have exactly width x height characters. */
    fun decode(text: String, width: Int, height: Int): List<CellMark>? {
        if (width <= 0 || height <= 0 || text.length != width * height) return null
        return text.map(::markOf)
    }
}
