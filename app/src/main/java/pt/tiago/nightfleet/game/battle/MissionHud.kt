package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.mission.BossScore
import pt.tiago.nightfleet.core.mission.BossSeries
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeriesStatus

/** Convoy HUD: rounds survived out of the required, and the freighters afloat. */
data class ConvoyHud(
    val roundsSurvived: Int,
    val roundsRequired: Int,
    val freightersAfloat: Int,
    val freightersTotal: Int,
    val minFreighters: Int,
) {
    companion object {
        /** Null for every battle that is not a convoy. */
        fun of(state: BattleState): ConvoyHud? {
            if (state.mission != MissionType.CONVOY) return null
            val convoy = state.rules.convoy ?: return null
            val own = state.sides[0]
            return ConvoyHud(
                roundsSurvived = state.round,
                roundsRequired = convoy.surviveRounds,
                freightersAfloat = own.ships.count { !it.sunk && it.shipClass == ShipClass.FREIGHTER },
                freightersTotal = state.rules.fleetFor(0).ships.count { it == ShipClass.FREIGHTER },
                minFreighters = convoy.minFreighters,
            )
        }
    }
}

/** Stars of a convoy: 1 for surviving, 2 with every freighter afloat, 3 also with the enemy fleet sunk. */
object ConvoyStars {

    /**
     * Stars of a finished convoy battle. Zero when the player lost. The enemy fleet sunk before the end counts
     * for the third star only with every freighter afloat.
     */
    fun of(result: BattleResult?, freightersAfloat: Int, freightersTotal: Int, enemyFleetSunk: Boolean): Int {
        if (result == null || result.winner != 0) return 0
        if (result.reason != EndReason.CONVOY_SURVIVED && result.reason != EndReason.FLEET_SUNK) return 0
        var stars = 1
        if (freightersTotal > 0 && freightersAfloat >= freightersTotal) {
            stars = 2
            if (enemyFleetSunk) stars = 3
        }
        return stars
    }
}

/**
 * The interstitial between the duels of a boss series: score, the duel about to start and
 * who starts it. [playerFirst] is true when side 0 (the player) opens the next duel.
 */
data class BossInterstitial(
    val duelNumber: Int,
    val score: BossScore,
    val replay: Boolean,
    val playerFirst: Boolean,
    val status: SeriesStatus,
) {
    /** "1 – 0": player wins, en dash, admiral wins. */
    val scoreText: String get() = "${score.playerWins} – ${score.admiralWins}"

    companion object {
        /** The interstitial shown before the next duel of [series]. */
        fun of(series: BossSeries): BossInterstitial = BossInterstitial(
            duelNumber = series.duelIndex + 1,
            score = series.score,
            replay = series.attempt == 2,
            playerFirst = series.startingPlayer == 0,
            status = series.status,
        )
    }
}
