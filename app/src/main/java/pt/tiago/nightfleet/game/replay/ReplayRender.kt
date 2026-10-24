package pt.tiago.nightfleet.game.replay

import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.ObservationMapper
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.ShipOutline

/**
 * The two seas of a replay step: the player's own sea, and the admiral's sea with the squares of
 * its ships still afloat shown. A replay is looked at afterwards, so nothing is hidden from it.
 */
object ReplayRender {

    data class Seas(val own: BoardRenderModel, val enemy: BoardRenderModel)

    /** The seas after [step] actions of [replay], with the last shot of the action before them marked. */
    fun seasAt(replay: MatchReplay, step: Int): Seas {
        val state = replay.stateAt(step)
        val last = if (step > 0) replay.actions[step - 1].shotCells().lastOrNull() else null
        val lastIndex = last?.let { it.row * state.rules.width + it.col }
        val lastByPlayer = step > 0 && replay.stateAt(step - 1).current == BattleMatch.PLAYER
        return seas(state, lastOwn = if (lastByPlayer) null else lastIndex, lastEnemy = if (lastByPlayer) lastIndex else null)
    }

    /** The seas of one [state]. */
    fun seas(state: BattleState, lastOwn: Int?, lastEnemy: Int?): Seas {
        val obs = Observation.of(state, BattleMatch.PLAYER)
        val enemy = ObservationMapper.enemySea(obs, autoWater = emptySet(), lastShot = lastEnemy, selected = null)
        val own = ObservationMapper.ownSea(obs, lastShot = lastOwn)
        return Seas(own = own, enemy = revealAfloat(enemy, state))
    }

    /** Shows the squares of the admiral's ships that are still afloat, as [ObservationMapper.revealEnemy] does at the end. */
    private fun revealAfloat(model: BoardRenderModel, state: BattleState): BoardRenderModel {
        val enemy = state.sides[BattleMatch.ADMIRAL]
        val board = enemy.board
        val cells = model.cells.toMutableList()
        val outlines = ArrayList<ShipOutline>()
        for (ship in enemy.ships) {
            if (ship.sunk) continue
            val squares = ship.placement.cells().map { board.index(it) }
            for (index in squares) if (cells[index] == CellVisual.UNKNOWN) cells[index] = CellVisual.REVEALED_SHIP
            outlines += ShipOutline(squares, sunk = false, revealed = true)
        }
        return model.copy(cells = cells, ships = model.ships + outlines)
    }
}
