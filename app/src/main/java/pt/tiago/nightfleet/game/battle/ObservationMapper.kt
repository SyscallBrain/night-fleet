package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.ShotRecord
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.ReconMark
import pt.tiago.nightfleet.ui.board.SonarMark
import pt.tiago.nightfleet.ui.board.ShipOutline

/**
 * Turns what a viewer knows into the pictures of the two seas. Everything here is
 * a function of an [Observation] plus the auto-water the player has seen. The hidden enemy fleet only
 * reaches the board through [revealEnemy], and only after the battle has ended.
 */
object ObservationMapper {

    /**
     * The enemy sea as the player knows it. [autoWater] holds the squares marked by automatic water
     * (from the engine's AutoWater events), which the observation alone cannot tell from a miss.
     */
    fun enemySea(obs: Observation, autoWater: Set<Int>, lastShot: Int?, selected: Int?): BoardRenderModel {
        val cells = obs.enemySea.mapIndexed { index, known ->
            when (known) {
                KnownCell.UNKNOWN -> CellVisual.UNKNOWN
                KnownCell.WATER -> if (index in autoWater) CellVisual.AUTO_WATER else CellVisual.WATER
                KnownCell.HIT -> CellVisual.HIT
                KnownCell.SUNK -> CellVisual.SUNK
                KnownCell.ISLAND -> CellVisual.ISLAND
                KnownCell.REEF -> CellVisual.REEF
                KnownCell.CONTACT -> CellVisual.CONTACT
                KnownCell.MINE -> CellVisual.MINE
                KnownCell.DECOY_WRECK -> CellVisual.DECOY_WRECK
                KnownCell.REPAIRED -> CellVisual.REPAIRED
            }
        }
        val width = obs.width
        val outlines = obs.enemySunk.map { sunk ->
            ShipOutline(sunk.cells.map { it.row * width + it.col }, sunk = true)
        }
        return BoardRenderModel(
            width = obs.width,
            height = obs.height,
            cells = cells,
            ships = outlines,
            lastShot = lastShot,
            selected = selected,
        )
    }

    /**
     * The player's own sea: every ship as a whole hull (hit squares marked), the shots received and
     * the islands and reefs. Empty water that nobody shot at is [CellVisual.UNKNOWN] (plain sea).
     */
    fun ownSea(obs: Observation, lastShot: Int?): BoardRenderModel {
        val own = obs.ownSea
        val board = own.board
        val cells = Array(board.size) { index ->
            when (board.terrainAt(index)) {
                Terrain.WATER -> CellVisual.UNKNOWN
                Terrain.ISLAND -> CellVisual.ISLAND
                Terrain.REEF -> CellVisual.REEF
            }
        }
        // The player's own mines are known to the player; an untouched one is drawn as a mine.
        for (mine in board.mines) if (cells[mine] == CellVisual.UNKNOWN) cells[mine] = CellVisual.MINE
        for (record in own.incoming.entries) {
            cells[record.cell] = when (record.result) {
                ShotResult.Miss -> CellVisual.WATER
                ShotResult.AutoWater -> CellVisual.AUTO_WATER
                ShotResult.Hit, is ShotResult.Sunk -> CellVisual.OWN_SHIP_HIT
                ShotResult.MineHit -> CellVisual.MINE
                ShotResult.DecoyHit -> CellVisual.DECOY_WRECK
                ShotResult.Contact -> CellVisual.CONTACT
                ShotResult.Repaired -> CellVisual.REPAIRED
                // A torpedo's path is an animation only; the squares it crosses are plain water.
                ShotResult.Trail -> CellVisual.WATER
            }
        }
        val outlines = own.ships.map { ship ->
            val squares = ship.placement.cells().map { board.index(it) }
            for (index in squares) {
                cells[index] = if (index in ship.hits) CellVisual.OWN_SHIP_HIT else CellVisual.OWN_SHIP
            }
            ShipOutline(squares, sunk = ship.sunk)
        }
        return BoardRenderModel(
            width = board.width,
            height = board.height,
            cells = cells.toList(),
            ships = outlines,
            lastShot = lastShot,
        )
    }

    /**
     * Adds the readings of the viewer to the enemy sea: sonar rings (fresh for the last turn of the viewer)
     * and reconnaissance marks in the margin.
     */
    fun withReadings(model: BoardRenderModel, obs: Observation): BoardRenderModel {
        val width = obs.width
        val turn = obs.publicState.turn
        val sonar = obs.sonarReadings.map {
            SonarMark(
                centerIndex = it.center.row * width + it.center.col,
                count = it.count,
                fresh = turn - it.turn <= FRESH_TURNS,
            )
        }
        val recon = obs.reconReadings.map { ReconMark(it.isRow, it.index, it.present) }
        return model.copy(sonarReadings = sonar, recon = recon)
    }

    /**
     * Misses of the viewer that fade at the end of the viewer's next turn (fog). Mapped to a
     * dimmed alpha during the turn before the fade, so the fade is announced instead of sudden.
     */
    fun fadingMisses(state: BattleState, viewer: Int): Map<Int, Float> {
        val fog = state.rules.fog ?: return emptyMap()
        val own = state.sides[viewer]
        val latest = LinkedHashMap<Int, ShotRecord>()
        for (record in state.sides[1 - viewer].incoming.entries) {
            if (record.attacker == viewer) latest[record.cell] = record
        }
        val out = LinkedHashMap<Int, Float>()
        for ((cell, record) in latest) {
            val isMissLike = record.result == ShotResult.Miss || record.result == ShotResult.Trail
            if (isMissLike && own.turnsTaken - record.ownTurn - 1 == fog.missFadeTurns - 1) out[cell] = FADING_ALPHA
        }
        return out
    }

    /** Readings this many viewer turns old or less still show their fresh ring. */
    const val FRESH_TURNS = 1

    /** Alpha of a miss that is about to fade. */
    const val FADING_ALPHA = 0.4f

    /**
     * At the end of the battle the enemy ships that were not sunk are drawn on the enemy sea
     * (60 %, dashed outline). Does nothing while the battle runs.
     */
    fun revealEnemy(model: BoardRenderModel, state: BattleState, viewer: Int): BoardRenderModel {
        if (state.result == null) return model
        val enemy = state.sides[1 - viewer]
        val board = enemy.board
        val cells = model.cells.toMutableList()
        val revealed = ArrayList<ShipOutline>()
        for (ship in enemy.ships) {
            if (ship.sunk) continue
            val squares = ship.placement.cells().map { board.index(it) }
            for (index in squares) {
                if (cells[index] == CellVisual.UNKNOWN) cells[index] = CellVisual.REVEALED_SHIP
            }
            revealed += ShipOutline(squares, sunk = false, revealed = true)
        }
        return model.copy(cells = cells, ships = model.ships + revealed)
    }

    /**
     * Compact final map for the logbook, row-major, one character per square:
     * `.` plain sea, `o` miss, `-` automatic water, `X` hit, `#` sunk, `S` own ship, `H` own hit,
     * `s` revealed ship, `I` island, `R` reef.
     */
    fun finalMapCode(model: BoardRenderModel): String = buildString(model.cells.size) {
        for (cell in model.cells) append(
            when (cell) {
                CellVisual.UNKNOWN -> '.'
                CellVisual.WATER -> 'o'
                CellVisual.AUTO_WATER -> '-'
                CellVisual.HIT -> 'X'
                CellVisual.SUNK -> '#'
                CellVisual.OWN_SHIP -> 'S'
                CellVisual.OWN_SHIP_HIT -> 'H'
                CellVisual.REVEALED_SHIP -> 's'
                CellVisual.ISLAND -> 'I'
                CellVisual.REEF -> 'R'
                else -> '?'
            },
        )
    }
}
