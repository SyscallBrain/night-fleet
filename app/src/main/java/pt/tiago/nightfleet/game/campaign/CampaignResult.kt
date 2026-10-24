package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Rank
import pt.tiago.nightfleet.data.rank
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.meta.AchievementDef
import pt.tiago.nightfleet.meta.AchievementEvaluator
import pt.tiago.nightfleet.meta.Achievements
import pt.tiago.nightfleet.meta.GameEvent
import pt.tiago.nightfleet.meta.Paint
import pt.tiago.nightfleet.meta.PaintCatalog
import java.time.LocalDate

/** The convoy facts of a finished battle, read from the engine state (side 0 is the player). */
data class ConvoyOutcome(
    val freightersAfloat: Int,
    val freightersTotal: Int,
    val enemyFleetSunk: Boolean,
) {
    val allFreightersAfloat: Boolean get() = freightersTotal > 0 && freightersAfloat == freightersTotal

    companion object {
        fun of(state: BattleState): ConvoyOutcome {
            val own = state.sides[0].ships.filter { it.shipClass == ShipClass.FREIGHTER }
            return ConvoyOutcome(
                freightersAfloat = own.count { !it.sunk },
                freightersTotal = own.size,
                enemyFleetSunk = state.sides[1].ships.all { it.sunk },
            )
        }
    }
}

/** What the result sheet says about the next star of a campaign battle. */
sealed interface NextStar {
    /** Shots needed for the next star (campaign thresholds). */
    data class Shots(val shots: Int) : NextStar

    /** Convoy: the next star needs the three freighters afloat. */
    data object Freighters : NextStar

    /** Convoy: the next star needs the enemy fleet sunk as well. */
    data object SinkEnemy : NextStar

    /** Three stars, or no win: nothing to show. */
    data object None : NextStar
}

/** The boss series as the result sheet shows it. [decided] is true when this duel ended the series. */
data class SeriesScore(val wins: Int, val losses: Int, val decided: Boolean)

/**
 * Everything the result sheet shows beyond the battle summary: campaign stars, the next star, the achievements
 * unlocked by this battle, the rank change, a new paint and the boss series. Built once when the result is committed.
 */
data class ResultExtras(
    /** Stars of the battle under the campaign rules. */
    val stars: Int,
    val nextStar: NextStar,
    /** Achievements unlocked by this battle, in catalog order. The sheet shows at most [MAX_ACHIEVEMENT_LINES]. */
    val achievements: List<AchievementDef>,
    val rankBefore: Rank,
    val rankAfter: Rank,
    /** A paint unlocked by this battle, or null. */
    val paintUnlocked: Paint?,
    /** A boss duel ended in a turn-limit draw and is replayed once. */
    val drawReplay: Boolean,
    /** Boss series score after this duel, or null for other missions. */
    val series: SeriesScore?,
    /** A mode battle (patrol, tournament) has no campaign stars: the sheet hides them. */
    val showStars: Boolean = true,
    /** The run status of a mode battle (reserve left, duels won), or null. */
    val modeLine: String? = null,
    /** Label of the main button when it is not the default (a mode moves on to the next sea or duel). */
    val primaryLabel: String? = null,
) {
    val rankChanged: Boolean get() = rankBefore != rankAfter

    companion object {
        const val MAX_ACHIEVEMENT_LINES = 3
    }
}

/** Pure rules that turn a finished campaign battle into stars, achievements and the result sheet's facts. */
object BattleResultRules {

    /** The stars of a battle under the rules of its mission. Zero when it was not won. */
    fun campaignStars(stake: CampaignStake, summary: QuickBattle.Summary, convoy: ConvoyOutcome?): Int =
        if (stake.kind == MissionKind.CONVOY && convoy != null) {
            CampaignScoring.convoyStars(summary.won, convoy.allFreightersAfloat, convoy.enemyFleetSunk, summary.usedHint)
        } else {
            CampaignScoring.stars(summary.won, summary.shots, stake.thresholds, summary.usedHint)
        }

    /** The next star after [stars] stars. */
    fun nextStar(stake: CampaignStake, stars: Int, won: Boolean, convoy: ConvoyOutcome?): NextStar {
        if (!won || stars >= 3) return NextStar.None
        if (stake.kind == MissionKind.CONVOY) {
            return if (convoy?.allFreightersAfloat != true) NextStar.Freighters else NextStar.SinkEnemy
        }
        val thresholds = stake.thresholds ?: return NextStar.None
        return when (stars) {
            1 -> NextStar.Shots(thresholds.two)
            2 -> NextStar.Shots(thresholds.three)
            else -> NextStar.None
        }
    }

    /** The meta event of a finished battle. Fields the summary does not carry keep their defaults. */
    fun gameEvent(kind: MatchKind, difficulty: Difficulty?, summary: QuickBattle.Summary, convoy: ConvoyOutcome?): GameEvent {
        val draw = summary.reason == EndReason.TURN_LIMIT
        return GameEvent(
            kind = kind,
            result = when {
                summary.won -> LogResult.WIN
                draw -> LogResult.DRAW
                else -> LogResult.LOSS
            },
            difficulty = difficulty,
            shots = summary.shots,
            hits = summary.hits,
            hintUsed = summary.usedHint,
            shipsSunk = summary.enemyShipsSunk,
            escortPerfect = summary.won && convoy?.allFreightersAfloat == true,
            turnLimitDraw = draw,
        )
    }

    /**
     * Evaluates the achievements of [event] on [profile]. Returns the profile with the new achievement map and the
     * definitions unlocked by this event, in catalog order.
     */
    fun evaluateAchievements(profile: Profile, event: GameEvent, today: LocalDate): Pair<Profile, List<AchievementDef>> {
        val result = AchievementEvaluator.evaluate(profile, event, today)
        val defs = result.newlyUnlocked.mapNotNull { id -> Achievements.all.firstOrNull { it.id == id } }
        return profile.copy(achievements = result.achievements) to defs
    }

    /** The first paint that [after] unlocks and [before] does not (never the default), or null. */
    fun newPaint(before: Profile, after: Profile): Paint? {
        val known = PaintCatalog.unlockedIds(before)
        return PaintCatalog.all.firstOrNull { it.id !in known && it.id in PaintCatalog.unlockedIds(after) }
    }

    /** The rank of [profile] before and after a battle. */
    fun rankChange(before: Profile, after: Profile): Pair<Rank, Rank> = before.rank() to after.rank()
}
