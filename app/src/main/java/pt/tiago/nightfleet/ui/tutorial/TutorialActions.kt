package pt.tiago.nightfleet.ui.tutorial

import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleUi

/**
 * The action ids of the tutorial scripts (see [TutorialScripts]), derived from what the screens show. The screens do
 * not emit actions themselves: the campaign flow compares two snapshots and reports the changes, so the battle and
 * the placement view models keep no tutorial code.
 */
object TutorialActions {
    const val FIRED = "fired"
    const val SUNK = "sunk"
    const val SHIP_PLACED = "ship_placed"
    const val SHIP_ROTATED = "ship_rotated"
    const val AUTO_PRESSED = "auto_pressed"
    const val READY_PRESSED = "ready_pressed"
    const val HINT_PRESSED = "hint_pressed"
    const val FORMATION_SAVED = "formation_saved"
    const val RESULT_SHOWN = "result_shown"

    /** The counters of a battle that the tutorial reacts to. */
    data class BattleSnapshot(
        /** Player shots fired so far, automatic water excluded. */
        val playerShots: Int,
        /** Enemy ship classes sunk so far. */
        val enemySunk: Int,
        val hintsUsed: Int,
        val finished: Boolean,
    )

    /** The counters of the placement that the tutorial reacts to. */
    data class PlacementSnapshot(
        val placed: List<Placement>,
        val formations: Int,
    )

    fun snapshotOf(ui: BattleUi): BattleSnapshot = BattleSnapshot(
        playerShots = ui.match.state.sides[1].incoming.entries.count {
            it.attacker == BattleMatch.PLAYER && it.result !is ShotResult.AutoWater
        },
        enemySunk = ui.enemySunkClasses.size,
        hintsUsed = ui.match.hintsUsed,
        finished = ui.summary != null,
    )

    /** The actions of a battle between two snapshots, in the order they happened. [previous] null means no earlier state. */
    fun battle(previous: BattleSnapshot?, now: BattleSnapshot): List<String> {
        val before = previous ?: BattleSnapshot(0, 0, 0, false)
        return buildList {
            if (now.playerShots > before.playerShots) add(FIRED)
            if (now.enemySunk > before.enemySunk) add(SUNK)
            if (now.hintsUsed > before.hintsUsed) add(HINT_PRESSED)
            if (now.finished && !before.finished) add(RESULT_SHOWN)
        }
    }

    /**
     * The actions of the placement between two snapshots. A ship that appears is placed; a ship that stays and moves is
     * rotated; a formation that is saved adds one to the count.
     */
    fun placement(previous: PlacementSnapshot?, now: PlacementSnapshot): List<String> {
        val before = previous ?: PlacementSnapshot(emptyList(), 0)
        return buildList {
            if (now.placed.size > before.placed.size) add(SHIP_PLACED)
            if (now.placed.size == before.placed.size && now.placed != before.placed && now.placed.isNotEmpty()) add(SHIP_ROTATED)
            if (now.formations > before.formations) add(FORMATION_SAVED)
        }
    }
}
