package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.data.AdmiralSpeed
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.isFireable

/** What a tap on the enemy sea means (the "Confirmar tiro" button). */
sealed class TapDecision {
    /** First tap with "confirm shot": the square is selected, nothing is fired. */
    data class Select(val index: Int) : TapDecision()

    /** The shot goes out at [index]. */
    data class Fire(val index: Int) : TapDecision()

    /** The square was already shot at, or the sea is not the player's to shoot now. */
    data object Ignored : TapDecision()
}

object BattleTaps {

    /**
     * Decides a tap. [enemyVisuals] is the enemy sea as drawn: only a square still
     * squares that are still fireable (plain sea, contacts, repaired squares) can be fired at. With [confirmShot], a second tap on the selected square fires.
     */
    fun decide(
        enemyVisuals: List<CellVisual>,
        selected: Int?,
        index: Int,
        confirmShot: Boolean,
        playerTurn: Boolean,
    ): TapDecision {
        if (!playerTurn) return TapDecision.Ignored
        if (index !in enemyVisuals.indices || !enemyVisuals[index].isFireable) return TapDecision.Ignored
        return if (!confirmShot || selected == index) TapDecision.Fire(index) else TapDecision.Select(index)
    }
}

/** Admiral's think time: 700–900 ms normal, 250 ms fast. */
object AdmiralPace {
    const val FAST_MS = 250L
    const val NORMAL_MIN_MS = 700L
    const val NORMAL_MAX_MS = 900L

    fun thinkMillis(speed: AdmiralSpeed, roll: Float): Long = when (speed) {
        AdmiralSpeed.FAST -> FAST_MS
        AdmiralSpeed.NORMAL -> NORMAL_MIN_MS + (roll.coerceIn(0f, 1f) * (NORMAL_MAX_MS - NORMAL_MIN_MS)).toLong()
    }
}

enum class LogKind { MISS, HIT, SUNK }

/** The last shot of an action, shown in the log line of the battle screen. */
data class LogLine(val byPlayer: Boolean, val cell: Cell, val kind: LogKind, val shipClass: ShipClass? = null)

object BattleLog {

    /** The last shot of [events], or null when the action was not a shot. */
    fun lineOf(events: List<Event>): LogLine? {
        val shot = events.lastOrNull { it is Event.Miss || it is Event.Hit } ?: return null
        val sunk = events.filterIsInstance<Event.Sunk>().lastOrNull()
        return when (shot) {
            is Event.Miss -> LogLine(shot.actor == BattleMatch.PLAYER, shot.cell, LogKind.MISS)
            is Event.Hit -> LogLine(
                byPlayer = shot.actor == BattleMatch.PLAYER,
                cell = shot.cell,
                kind = if (sunk != null) LogKind.SUNK else LogKind.HIT,
                shipClass = sunk?.shipClass,
            )
            else -> null
        }
    }
}
